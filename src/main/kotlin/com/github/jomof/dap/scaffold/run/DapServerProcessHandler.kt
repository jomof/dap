package com.github.jomof.dap.scaffold.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ProcessHandler that owns a DAP adapter [Process] but **does not drain
 * stdin/stdout** so the DAP client can use them exclusively for the
 * JSON-RPC channel. Only stderr is forwarded as text events so the user
 * can still see adapter diagnostics in the run console.
 *
 * Process death is detected by a single background thread that calls
 * `Process.waitFor()`; on exit it fires `processTerminated`.
 */
class DapServerProcessHandler(
    private val process: Process,
    private val commandLine: String,
    /**
     * How to materialise a [com.github.jomof.dap.client.DapTransport] for the
     * spawned adapter. Defaults to [DapTransportBlueprint.Stdio] for the
     * lldb-dap / CodeLLDB family; delve-dap overrides this to a
     * [DapTransportBlueprint.TcpAfterStartup] pointing at its `--listen` port.
     */
    val transportBlueprint: DapTransportBlueprint = DapTransportBlueprint.Stdio,
) : ProcessHandler() {

    private val stderrPump = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dap-adapter-stderr").apply { isDaemon = true }
    }
    private val waitThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dap-adapter-wait").apply { isDaemon = true }
    }
    private val stderrDrained = AtomicBoolean(false)

    init {
        stderrPump.submit {
            try {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { notifyTextAvailable(it + "\n", ProcessOutputType.STDERR) }
                }
            } catch (ignored: Throwable) {
                // Stream closure during shutdown is expected.
            } finally {
                stderrDrained.set(true)
            }
        }
        waitThread.submit {
            try {
                val exit = process.waitFor()
                notifyProcessTerminated(exit)
            } catch (ignored: InterruptedException) {
                notifyProcessTerminated(-1)
            } finally {
                stderrPump.shutdownNow()
                waitThread.shutdown()
            }
        }
    }

    override fun destroyProcessImpl() {
        process.destroy()
        if (!process.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
    }

    override fun detachProcessImpl() {
        // No "detach" semantics — adapter death is always our death.
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    /** Direct access to the underlying [Process]; used by [ScaffoldDapDebugRunner]. */
    fun underlyingProcess(): Process = process

    /** Description text shown in run-config UI. */
    @Suppress("unused")
    fun commandLineDescription(): String = commandLine

    @Suppress("unused")
    fun appendBanner(message: String, key: Key<*> = ProcessOutputType.SYSTEM) {
        notifyTextAvailable(message, key)
    }
}
