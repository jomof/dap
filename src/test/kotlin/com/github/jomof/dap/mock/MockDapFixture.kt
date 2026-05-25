package com.github.jomof.dap.mock

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapTransport
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors

/**
 * Wires a [MockDapServer] and a real [DapClient] together via in-memory
 * piped streams, simulating a stdio-mode adapter without spawning any
 * processes. Use in tests that need to exercise the full request/response
 * loop.
 *
 * Lifecycle: build the fixture inside `@Before`/`setUp`, call [close] in
 * `@After`/`tearDown`.
 */
class MockDapFixture {

    /** Mock adapter instance; mutate its scripted state in tests. */
    val server: MockDapServer = MockDapServer()

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "mock-dap-fixture").apply { isDaemon = true }
    }

    // Server stdout → client stdin.
    private val serverToClient = PipedOutputStream()
    private val clientReadsFromServer = PipedInputStream(serverToClient, BUFFER)

    // Client stdout → server stdin.
    private val clientToServer = PipedOutputStream()
    private val serverReadsFromClient = PipedInputStream(clientToServer, BUFFER)

    val transport: DapTransport = DapTransport.Stdio(clientReadsFromServer, clientToServer)
    val client: DapClient = DapClient(transport)

    init {
        // Server side runs on the executor; client side runs on the calling thread.
        DSPLauncher.createServerLauncher(
            server,
            serverReadsFromClient,
            serverToClient,
            executor,
        ) { it }.also { launcher ->
            executor.submit { launcher.startListening() }
            server.bindRemoteProxy(launcher.remoteProxy)
        }
        client.connect()
    }

    /** Bare server proxy if a test needs to bypass [DapClient]. */
    fun serverProxy(): IDebugProtocolServer? = null // not exposed; use [client]

    fun close() {
        client.dispose()
        runCatching { serverToClient.close() }
        runCatching { clientToServer.close() }
        runCatching { clientReadsFromServer.close() }
        runCatching { serverReadsFromClient.close() }
        executor.shutdownNow()
    }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}
