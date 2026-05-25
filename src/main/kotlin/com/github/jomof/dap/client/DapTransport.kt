package com.github.jomof.dap.client

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * The wire-level transport used to talk to a DAP server.
 *
 * Phase 1 ships [Stdio]; [Tcp] is added in Phase 5 when we need to talk to
 * delve-dap, which typically listens on a TCP port rather than communicating
 * over stdio.
 *
 * Transports are owned by [DapClient] and closed exactly once during disposal.
 */
sealed interface DapTransport {
    val input: InputStream
    val output: OutputStream

    fun close()

    /** Standard input/output of a child process running the DAP adapter. */
    class Stdio(
        override val input: InputStream,
        override val output: OutputStream,
    ) : DapTransport {
        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    /** A TCP connection to a DAP adapter listening on a host:port. */
    class Tcp(private val socket: Socket) : DapTransport {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
