package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * CodeLLDB twin of [LldbDapPauseResumeIntegrationTest]: drives the
 * adapter through several pause → continue → pause cycles against the
 * spin fixture to verify that the IDE Pause button keeps working
 * after the first cycle.
 *
 * The lldb-dap variant exists because lldb-dap rejects `threads()`
 * with `notStopped` while the inferior runs, which broke the IDE
 * pause flow. CodeLLDB is significantly more permissive on this
 * front, but we still pin the cached-thread-id approach so we don't
 * regress if CodeLLDB tightens its behaviour in a future release.
 */
class CodeLldbPauseResumeIntegrationTest {

    private var fixture: CodeLldbProcessFixture? = null
    private var client: DapClient? = null

    @Before fun setUp() {
        assumeTrue("Set DAP_INTEGRATION_TESTS=1 to enable", System.getenv("DAP_INTEGRATION_TESTS") == "1")
        assumeTrue(
            "spin binary not built — run src/test/resources/fixtures/c/build.sh",
            Files.isExecutable(SPIN_BINARY),
        )
        fixture = CodeLldbTestFixtures.tryProvisionAndSpawn()
        assumeTrue("CodeLLDB could not be provisioned on this host", fixture != null)
    }

    @After fun tearDown() {
        client?.dispose()
        fixture?.dispose()
    }

    @Test fun `codelldb can be paused repeatedly within a single session`() = runBlocking {
        val fx = fixture!!
        val client = DapClient(fx.transport).also { it.connect() }
        this@CodeLldbPauseResumeIntegrationTest.client = client

        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { stoppedEvents.addLast(it) }
        }
        try {
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(InitializeRequestArguments().apply {
                    clientID = "dap.intellij.integration-test"
                    clientName = "IntelliJ DAP (CodeLLDB pause/resume test)"
                    adapterID = "lldb"
                    pathFormat = "path"
                    linesStartAt1 = true
                    columnsStartAt1 = true
                })
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
            withTimeout(REQUEST_TIMEOUT) {
                client.continueExecution(ContinueArguments().apply { this.threadId = cachedThreadId })
            }

            repeat(PAUSE_CYCLES) {
                withTimeout(REQUEST_TIMEOUT) {
                    client.pause(PauseArguments().apply { this.threadId = cachedThreadId })
                }
                withTimeout(PAUSE_TIMEOUT) { takeNextStop(stoppedEvents) }
                withTimeout(REQUEST_TIMEOUT) {
                    client.continueExecution(ContinueArguments().apply { this.threadId = cachedThreadId })
                }
            }
            assertTrue("pause/resume cycles completed", true)
        } finally {
            collector.cancel()
        }
    }

    private suspend fun takeNextStop(queue: java.util.concurrent.BlockingDeque<DapEvent.Stopped>): DapEvent.Stopped {
        while (true) {
            queue.pollFirst()?.let { return it }
            kotlinx.coroutines.delay(20.milliseconds)
        }
    }

    private companion object {
        val HANDSHAKE_TIMEOUT = 20.seconds
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
