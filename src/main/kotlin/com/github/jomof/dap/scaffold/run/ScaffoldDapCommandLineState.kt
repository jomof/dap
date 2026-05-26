package com.github.jomof.dap.scaffold.run

import com.github.jomof.dap.scaffold.locator.AdapterUnavailableException
import com.github.jomof.dap.scaffold.locator.CodeLldbAdapterProvisioner
import com.github.jomof.dap.scaffold.locator.DelveDapLocator
import com.github.jomof.dap.scaffold.locator.LldbDapAdapterProvisioner
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts the DAP server process and exposes its [ProcessHandler] + console.
 *
 * Critically, the handler returned here is a [DapServerProcessHandler] that
 * **does not consume the adapter's stdout** — that stream is reserved for
 * the DAP JSON-RPC client. Only stderr (adapter diagnostics) reaches the
 * run console. Application output that the *debuggee* produces is surfaced
 * separately via DAP `output` events.
 *
 * For Phase 1 this only knows about lldb-dap; Phase 2 / Phase 5 add other
 * adapter binaries by branching on [config.adapterKind].
 */
class ScaffoldDapCommandLineState(
    environment: ExecutionEnvironment,
    private val config: ScaffoldDapRunConfiguration,
) : CommandLineState(environment) {

    init {
        consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(environment.project)
    }

    override fun startProcess(): ProcessHandler {
        val binary: Path = resolveAdapterBinary()
        val (command, blueprint) = buildCommand(binary)
        val builder = ProcessBuilder(command)
        if (config.workingDir.isNotBlank()) {
            builder.directory(java.io.File(config.workingDir))
        }
        builder.redirectErrorStream(false)
        val process = try {
            builder.start()
        } catch (throwable: Throwable) {
            throw ExecutionException("Failed to start ${config.adapterKind}: ${throwable.message}", throwable)
        }
        return DapServerProcessHandler(process, command.joinToString(" "), blueprint)
    }

    private fun resolveAdapterBinary(): Path {
        val binary = try {
            when (config.adapterKind) {
                DapAdapterKind.LLDB_DAP -> LldbDapAdapterProvisioner.provision()
                DapAdapterKind.CODE_LLDB -> CodeLldbAdapterProvisioner.provision()
                DapAdapterKind.DELVE_DAP -> DelveDapLocator.resolve()
                    ?: throw ExecutionException(
                        "dlv not found. Install Delve (https://github.com/go-delve/delve), set DAP_DELVE, " +
                            "or put dlv on PATH.",
                    )
            }
        } catch (failure: AdapterUnavailableException) {
            throw ExecutionException(failure.message, failure)
        }
        if (!Files.isExecutable(binary)) {
            throw ExecutionException("${config.adapterKind} binary at $binary is not executable.")
        }
        return binary
    }

    /**
     * Returns the OS command to spawn and the matching [DapTransportBlueprint].
     *
     *  - **lldb-dap**: stdio, no extra flags.
     *  - **CodeLLDB**: TCP. The adapter ships a built-in TCP server (no
     *    stdio mode) so we pre-pick a free port, pass `--port=N` plus
     *    `--liblldb=<path>` (the second is required — the adapter
     *    aborts at startup without it), and the blueprint races a
     *    socket connect against the spawned process.
     *  - **delve-dap**: TCP via `dlv dap --listen=...`.
     */
    private fun buildCommand(binary: Path): Pair<List<String>, DapTransportBlueprint> =
        when (config.adapterKind) {
            DapAdapterKind.LLDB_DAP -> listOf(binary.toString()) to DapTransportBlueprint.Stdio

            DapAdapterKind.CODE_LLDB -> {
                val port = pickFreePort()
                val cmd = mutableListOf(binary.toString(), "--port=$port")
                CodeLldbAdapterProvisioner.liblldbFor(binary)?.let { liblldb ->
                    cmd += "--liblldb=$liblldb"
                }
                cmd to DapTransportBlueprint.TcpAfterStartup(host = "127.0.0.1", port = port)
            }

            DapAdapterKind.DELVE_DAP -> {
                val port = pickFreePort()
                val cmd = listOf(
                    binary.toString(),
                    "dap",
                    "--listen=127.0.0.1:$port",
                    "--log-output=dap",
                )
                cmd to DapTransportBlueprint.TcpAfterStartup(
                    host = "127.0.0.1",
                    port = port,
                )
            }
        }

    private fun pickFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val processHandler = startProcess()
        val console: ConsoleView = createConsole(executor) ?: error("No console builder configured")
        console.attachToProcess(processHandler)
        console.print(
            "Starting ${config.adapterKind} session for ${config.program}\n",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )
        // The platform gates every per-session debug action (Pause, Resume,
        // Step Over, …) on `ProcessHandler.isStartNotified()`. Until we fire
        // startNotify(), all of them stay greyed out — only "Stop" works —
        // because the IDE doesn't know the process has actually started.
        processHandler.startNotify()
        return DefaultExecutionResult(console, processHandler)
    }

    fun config(): ScaffoldDapRunConfiguration = config
}
