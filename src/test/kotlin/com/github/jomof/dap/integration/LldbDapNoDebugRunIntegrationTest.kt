package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
import com.github.jomof.dap.client.DapTransport
import com.github.jomof.dap.scaffold.locator.LldbDapLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration test that drives a real lldb-dap through the
 * full `initialize → launch(noDebug=true) → configurationDone` sequence
 * against a tiny C smoke binary, and asserts the program's stdout flows
 * back as DAP `output` events.
 *
 * This test specifically guards against the lldb-dap quirk where the
 * `initialized` event ships with a non-spec `$__lldb_statistics` body;
 * the in-flight sanitiser inside `DapClient.connect()` strips that off
 * before LSP4J's dispatch sees it. If the sanitiser regresses, the
 * handshake hangs and this test times out.
 *
 * Skipped automatically when:
 *  - `DAP_INTEGRATION_TESTS=1` is not set (CI default), OR
 *  - `lldb-dap` cannot be resolved on this host, OR
 *  - the smoke binary hasn't been built (run `bash
 *    src/test/resources/fixtures/c/build.sh`).
 */
class LldbDapNoDebugRunIntegrationTest {

    private var process: Process? = null
    private var client: DapClient? = null

    @Before fun setUp() {
        assumeTrue("Set DAP_INTEGRATION_TESTS=1 to enable", System.getenv("DAP_INTEGRATION_TESTS") == "1")
        val binary = LldbDapLocator.resolve()
        assumeTrue("lldb-dap not found on this host", binary != null && Files.isExecutable(binary))
        assumeTrue("smoke binary not built — run src/test/resources/fixtures/c/build.sh", Files.isExecutable(SMOKE_BINARY))
        process = ProcessBuilder(binary!!.toString()).redirectErrorStream(false).start()
    }

    @After fun tearDown() {
        client?.dispose()
        process?.destroy()
    }

    @Test
    fun `lldb-dap noDebug launches binary and forwards stdout`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapNoDebugRunIntegrationTest.client = client

        val eventTrace = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collected = StringBuilder()
        coroutineScope {
            val outputJob = async {
                withTimeout(15.seconds) {
                    client.events
                        .onEach { event ->
                            eventTrace += event::class.simpleName ?: "?"
                            if (event is DapEvent.Output) {
                                event.payload.output?.let { synchronized(collected) { collected.append(it) } }
                            }
                        }
                        .takeWhile { event ->
                            event !is DapEvent.Terminated && event !is DapEvent.Exited &&
                                !synchronized(collected) { collected.toString() }.contains(EXPECTED_OUTPUT)
                        }
                        .collect { /* drained for side effects above */ }
                }
                synchronized(collected) { collected.toString() }
            }

            launch {
                withTimeout(15.seconds) {
                    val capabilities = client.initialize(InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (Run integration test)"
                        adapterID = "lldb-dap"
                        pathFormat = "path"
                        linesStartAt1 = true
                        columnsStartAt1 = true
                        supportsRunInTerminalRequest = true
                    })
                    val launchArgs = mapOf<String, Any>(
                        "program" to SMOKE_BINARY.toString(),
                        "noDebug" to true,
                    )
                    // Don't await launch response yet — lldb-dap (per DAP spec)
                    // only settles it after we send configurationDone.
                    val launchAck = client.launch(launchArgs)
                    client.awaitInitialized()
                    if (capabilities.supportsConfigurationDoneRequest) {
                        client.configurationDone()
                    }
                    launchAck.await()
                }
            }

            val captured = outputJob.await()
            assertTrue(
                "Expected lldb-dap to forward program stdout containing \"$EXPECTED_OUTPUT\". " +
                    "Events: $eventTrace. Collected output: <<<$captured>>>",
                captured.contains(EXPECTED_OUTPUT),
            )
        }
    }

    private companion object {
        const val EXPECTED_OUTPUT = "hello from dap test"
        val SMOKE_BINARY: Path = Paths.get(
            System.getProperty("user.dir"),
            "src",
            "test",
            "resources",
            "fixtures",
            "c",
            "hello",
        )
    }
}
