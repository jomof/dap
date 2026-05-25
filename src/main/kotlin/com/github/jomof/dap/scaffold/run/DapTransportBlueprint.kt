package com.github.jomof.dap.scaffold.run

import com.github.jomof.dap.client.DapTransport
import java.io.IOException
import java.net.Socket

/**
 * A scaffold-side recipe describing *how* to obtain a [DapTransport] for an
 * adapter [Process] that the run-config has already spawned.
 *
 * The runner spawns the process synchronously inside the command-line state
 * so the user sees the process tree appear immediately, but stdio vs. TCP
 * dictates very different teardown semantics. Encapsulating the connect
 * step here lets the runner stay transport-agnostic.
 */
sealed interface DapTransportBlueprint {

    fun connect(process: Process): DapTransport

    /** Wraps the spawned adapter's stdin/stdout — the path used by lldb-dap and CodeLLDB. */
    object Stdio : DapTransportBlueprint {
        override fun connect(process: Process): DapTransport =
            DapTransport.Stdio(process.inputStream, process.outputStream)
    }

    /**
     * Connects to a TCP listener the adapter is expected to bring up shortly
     * after start-up (delve-dap, for example). Polls until the socket accepts
     * a connection or [readyTimeoutMs] elapses, whichever comes first.
     */
    data class TcpAfterStartup(
        val host: String,
        val port: Int,
        val readyTimeoutMs: Long = 15_000,
        val pollIntervalMs: Long = 100,
    ) : DapTransportBlueprint {

        override fun connect(process: Process): DapTransport {
            val deadline = System.currentTimeMillis() + readyTimeoutMs
            var lastFailure: Throwable? = null
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    throw IOException(
                        "DAP server at $host:$port exited before accepting a connection " +
                            "(exit=${runCatching { process.exitValue() }.getOrElse { -1 }})",
                    )
                }
                try {
                    return DapTransport.Tcp(Socket(host, port))
                } catch (failure: IOException) {
                    lastFailure = failure
                    Thread.sleep(pollIntervalMs)
                }
            }
            throw IOException(
                "Timed out after ${readyTimeoutMs}ms waiting for DAP server at $host:$port",
                lastFailure,
            )
        }
    }
}
