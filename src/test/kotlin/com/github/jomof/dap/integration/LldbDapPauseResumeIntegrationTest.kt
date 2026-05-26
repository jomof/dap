package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
import com.github.jomof.dap.client.DapTransport
import com.github.jomof.dap.scaffold.locator.LldbDapLocator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.PauseArguments
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
 * End-to-end test that drives a real `lldb-dap` through several
 * `pause → continue → pause` cycles against a long-running spin binary.
 *
 * Motivating bug: the IDE Pause button stopped working after the first
 * cycle. We need to know whether the failure is at the DAP level (lldb-dap
 * silently dropping the second pause) or in our IntelliJ integration. This
 * test answers the first question by talking to lldb-dap directly.
 *
 * Skipped automatically unless `DAP_INTEGRATION_TESTS=1` is set and both
 * `lldb-dap` and the spin fixture binary are available (run
 * `bash src/test/resources/fixtures/c/build.sh` to build the latter).
 */
class LldbDapPauseResumeIntegrationTest {

    private var process: Process? = null
    private var client: DapClient? = null

    @Before fun setUp() {
        assumeTrue("Set DAP_INTEGRATION_TESTS=1 to enable", System.getenv("DAP_INTEGRATION_TESTS") == "1")
        val binary = LldbDapLocator.resolve()
        assumeTrue("lldb-dap not found on this host", binary != null && Files.isExecutable(binary))
        assumeTrue("spin binary not built — run src/test/resources/fixtures/c/build.sh", Files.isExecutable(SPIN_BINARY))
        process = ProcessBuilder(binary!!.toString()).redirectErrorStream(false).start()
    }

    @After fun tearDown() {
        client?.dispose()
        process?.destroy()
    }

    @Test
    fun `lldb-dap can be paused repeatedly within a single session`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapPauseResumeIntegrationTest.client = client

        // Capture every Stopped event into a queue we drive the test from.
        // The events flow is `replay = 0`, so we MUST subscribe before the
        // first stop arrives (lldb-dap can fire the entry stop very quickly
        // after configurationDone). Running this on the test scope lets us
        // cancel cleanly at the end.
        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { stoppedEvents.put(it) }
        }
        try {
            // ---- handshake ----
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(InitializeRequestArguments().apply {
                    clientID = "dap.intellij.integration-test"
                    clientName = "IntelliJ DAP (pause/resume integration test)"
                    adapterID = "lldb-dap"
                    pathFormat = "path"
                    linesStartAt1 = true
                    columnsStartAt1 = true
                })
                // stopOnEntry forces lldb-dap to emit a `stopped` event
                // before the program starts running. That gives us a clean
                // window to capture the thread id while the adapter is
                // willing to answer `threads()`. Once we continue, lldb-dap
                // rejects metadata requests with `notStopped` while the
                // program is executing — which is the exact bug that broke
                // our IDE Pause button on the second click (startPausing()
                // calls threads() first).
                val launchAck = client.launch(
                    mapOf<String, Any>(
                        "program" to SPIN_BINARY.toString(),
                        "stopOnEntry" to true,
                    ),
                )
                client.awaitInitialized()
                client.configurationDone()
                launchAck.await()
            }

            val entryStop = withTimeout(STARTUP_DRAIN) { takeNextStop(stoppedEvents) }
            val cachedThreadId = entryStop.payload.threadId
                ?: client.threads().threads?.firstOrNull()?.id
                ?: error("entry stop had no threadId and `threads()` returned empty")
            // Resume so the program is actually running when we start the
            // pause/resume cycle (otherwise the first pause is a no-op).
            withTimeout(REQUEST_TIMEOUT) {
                client.continueExecution(ContinueArguments().apply { this.threadId = cachedThreadId })
            }

            // ---- pause / resume loop ----
            // Three cycles is enough to demonstrate that the failure isn't
            // a one-off "second-time" quirk — if pause stops working at
            // cycle N it stays broken for N+1, N+2, …
            //
            // What this test proves: a cached thread id from the entry
            // stop lets us pause the inferior repeatedly without calling
            // `threads()` while the program is running. The previous IDE
            // bug was that startPausing() called threads() first, which
            // lldb-dap rejects with `notStopped` once execution has
            // resumed; the rejection threw, the pause request was never
            // issued, and the user saw nothing happen.
            //
            // We don't assert on `Stopped.reason` because lldb-dap is
            // creative with it — pause-induced stops can come back as
            // "pause", "exception", or even "breakpoint" depending on
            // platform and adapter version. The platform doesn't care
            // about the string value; any stopped event suspends the
            // session.
            repeat(PAUSE_CYCLES) {
                withTimeout(REQUEST_TIMEOUT) {
                    client.pause(PauseArguments().apply { this.threadId = cachedThreadId })
                }
                withTimeout(PAUSE_TIMEOUT) { takeNextStop(stoppedEvents) }
                withTimeout(REQUEST_TIMEOUT) {
                    client.continueExecution(ContinueArguments().apply { this.threadId = cachedThreadId })
                }
            }

            // If we got here without timing out, lldb-dap supports repeated
            // pause/continue cycles using a cached thread id — so the IDE
            // bug is on our side (asking threads() before each pause), not
            // the adapter's.
            assertTrue("pause/resume cycles completed", true)
        } finally {
            collector.cancel()
        }
    }

    /**
     * Pull the next stopped event from [queue] without blocking the calling
     * coroutine's thread. Polled with a short delay so we can be wrapped in
     * `withTimeout` and abort cleanly if no event arrives.
     */
    private suspend fun takeNextStop(queue: java.util.concurrent.BlockingDeque<DapEvent.Stopped>): DapEvent.Stopped {
        while (true) {
            queue.pollFirst()?.let { return it }
            kotlinx.coroutines.delay(20)
        }
    }

    private companion object {
        val HANDSHAKE_TIMEOUT = 15.seconds
        val REQUEST_TIMEOUT = 5.seconds
        val PAUSE_TIMEOUT = 10.seconds
        val STARTUP_DRAIN = 10.seconds
        const val PAUSE_CYCLES = 3
        val SPIN_BINARY: Path = Paths.get(
            System.getProperty("user.dir"),
            "src",
            "test",
            "resources",
            "fixtures",
            "c",
            "spin",
        )
    }
}
