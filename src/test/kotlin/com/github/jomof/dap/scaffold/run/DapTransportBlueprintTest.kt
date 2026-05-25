package com.github.jomof.dap.scaffold.run

import com.github.jomof.dap.client.DapTransport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DapTransportBlueprintTest {

    @Test fun `tcp blueprint connects once the listener accepts`() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { runCatching { server.accept() } }
            val blueprint = DapTransportBlueprint.TcpAfterStartup(
                host = "127.0.0.1",
                port = server.localPort,
                readyTimeoutMs = 2_000,
                pollIntervalMs = 25,
            )
            val transport = blueprint.connect(NullProcess())
            try {
                assertTrue(transport is DapTransport.Tcp)
                assertNotNull(transport.input)
                assertNotNull(transport.output)
            } finally {
                transport.close()
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
            server.close()
        }
    }

    @Test fun `tcp blueprint fails when process exits before listener is up`() {
        // Reserve and immediately release a port; nothing is listening on it now.
        val freePort = ServerSocket(0).use { it.localPort }
        val blueprint = DapTransportBlueprint.TcpAfterStartup(
            host = "127.0.0.1",
            port = freePort,
            readyTimeoutMs = 250,
            pollIntervalMs = 25,
        )
        try {
            blueprint.connect(DeadProcess())
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exited") == true || e.message?.contains("Timed out") == true)
        }
    }

    /** Minimal stand-in for [Process] that reports "alive" for the duration of a successful test. */
    private class NullProcess : Process() {
        override fun getOutputStream() = error("not used")
        override fun getInputStream() = error("not used")
        override fun getErrorStream() = error("not used")
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() {}
        override fun isAlive(): Boolean = true
    }

    /** Reports "not alive" so [DapTransportBlueprint.TcpAfterStartup] bails out fast. */
    private class DeadProcess : Process() {
        override fun getOutputStream() = error("not used")
        override fun getInputStream() = error("not used")
        override fun getErrorStream() = error("not used")
        override fun waitFor() = 1
        override fun exitValue() = 1
        override fun destroy() {}
        override fun isAlive(): Boolean = false
    }

    @Suppress("unused") private val ignoredSocket: Socket? = null
}
