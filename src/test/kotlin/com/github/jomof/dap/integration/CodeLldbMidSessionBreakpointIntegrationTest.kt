package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * CodeLLDB twin of [LldbDapMidSessionBreakpointIntegrationTest]. The
 * lldb-dap suite ships with one of these tests `@Ignore`'d to document
 * a known LLVM 23 limitation; we run both shapes against CodeLLDB to
 * find out whether the same workaround is required (the synthetic-
 * pause wrapper) or whether CodeLLDB handles mid-flight installs
 * natively. Either result is useful documentation.
 *
 * Skipped unless `DAP_INTEGRATION_TESTS=1` is set and the spin fixture
 * has been built (`bash src/test/resources/fixtures/c/build.sh`).
 */
class CodeLldbMidSessionBreakpointIntegrationTest {

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

    /**
     * The "expected to work" path that pauses first, installs a
     * breakpoint while stopped, then resumes. This is the path our
     * production synthetic-pause wrapper uses, and is the lowest
     * common denominator for both lldb-dap and CodeLLDB.
     */
    @Test fun `pause then setBreakpoints then continue causes the breakpoint to hit`() = runBlocking {
        val fx = fixture!!
        val client = DapClient(fx.transport).also { it.connect() }
        this@CodeLldbMidSessionBreakpointIntegrationTest.client = client

        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { stoppedEvents.put(it) }
        }
        try {
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(
                    InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (CodeLLDB pause-install-resume test)"
                        adapterID = "lldb"
                        pathFormat = "path"
                        linesStartAt1 = true
                        columnsStartAt1 = true
                    },
                )
                val launchAck = client.launch(mapOf<String, Any>("program" to SPIN_BINARY.toString()))
                client.awaitInitialized()
                client.configurationDone()
                launchAck.await()
            }
            delay(RUNNING_GRACE)

            client.pause(PauseArguments().apply { threadId = 1 })
            val pauseStop = withTimeout(BREAKPOINT_HIT_TIMEOUT) { takeNextStop(stoppedEvents) }
            val tid = pauseStop.payload.threadId ?: client.threads().threads?.firstOrNull()?.id
                ?: error("no thread id available after pause stop")

            val setResponse = withTimeout(REQUEST_TIMEOUT) {
                client.setBreakpoints(
                    SetBreakpointsArguments().apply {
                        source = Source().apply {
                            path = SPIN_SOURCE.toString()
                            name = "spin.c"
                        }
                        breakpoints = arrayOf(SourceBreakpoint().apply { line = LOOP_BODY_LINE })
                        sourceModified = false
                    },
                )
            }
            val installed = setResponse.breakpoints?.firstOrNull()
            assertNotNull("adapter returned a breakpoint entry", installed)
            println(
                "CodeLLDB pause-install: requested line=$LOOP_BODY_LINE, " +
                    "responded line=${installed!!.line}, verified=${installed.isVerified}",
            )

            client.continueExecution(ContinueArguments().apply { threadId = tid })
            val hit = withTimeout(BREAKPOINT_HIT_TIMEOUT) { takeNextStop(stoppedEvents) }
            println("CodeLLDB post-resume stopped event: reason=${hit.payload.reason}, threadId=${hit.payload.threadId}")
            assertTrue(
                "the post-resume stop should be our breakpoint",
                hit.payload.reason in setOf("breakpoint", "exception", "instruction breakpoint"),
            )
        } finally {
            collector.cancel()
        }
    }

    /**
     * The aggressive path: send `setBreakpoints` while the inferior is
     * running. lldb-dap rejects this silently (the matching test in
     * the lldb-dap suite is `@Ignore`'d to document the limitation).
     * If CodeLLDB *does* honour mid-flight installs, we can short-
     * circuit the synthetic-pause workaround for it. Either outcome is
     * informative — we don't enforce a result.
     *
     * Implementation: wrap the assertion in a 5s timeout and report
     * success on either outcome (`PASS — verified mid-flight install`
     * vs `INFO — workaround still required`). The test fails only when
     * something genuinely unexpected happens (handshake error, adapter
     * crash, no stopped event of any kind).
     */
    @Test fun `setBreakpoints sent while running — informational only`() = runBlocking {
        val fx = fixture!!
        val client = DapClient(fx.transport).also { it.connect() }
        this@CodeLldbMidSessionBreakpointIntegrationTest.client = client

        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { stoppedEvents.put(it) }
        }
        try {
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(
                    InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (CodeLLDB mid-flight install probe)"
                        adapterID = "lldb"
                        pathFormat = "path"
                        linesStartAt1 = true
                        columnsStartAt1 = true
                    },
                )
                val launchAck = client.launch(mapOf<String, Any>("program" to SPIN_BINARY.toString()))
                client.awaitInitialized()
                client.configurationDone()
                launchAck.await()
            }
            delay(RUNNING_GRACE)

            val setResponse = withTimeout(REQUEST_TIMEOUT) {
                client.setBreakpoints(
                    SetBreakpointsArguments().apply {
                        source = Source().apply {
                            path = SPIN_SOURCE.toString()
                            name = "spin.c"
                        }
                        breakpoints = arrayOf(SourceBreakpoint().apply { line = LOOP_BODY_LINE })
                        sourceModified = false
                    },
                )
            }
            val installed = setResponse.breakpoints?.firstOrNull()
            assertNotNull("adapter returned a breakpoint entry", installed)
            println(
                "CodeLLDB mid-flight install: requested line=$LOOP_BODY_LINE, " +
                    "responded line=${installed!!.line}, verified=${installed.isVerified}",
            )

            // Probe — but don't fail — whether a stop arrives.
            val hit = runCatching {
                withTimeout(MID_FLIGHT_PROBE_TIMEOUT) { takeNextStop(stoppedEvents) }
            }.getOrNull()
            if (hit != null) {
                println("CodeLLDB PASS: mid-flight breakpoint fired (reason=${hit.payload.reason}); " +
                    "synthetic-pause workaround can be skipped for CodeLLDB.")
            } else {
                println("CodeLLDB INFO: mid-flight breakpoint did NOT fire within " +
                    "${MID_FLIGHT_PROBE_TIMEOUT}; synthetic-pause workaround still required.")
            }
        } finally {
            collector.cancel()
        }
    }

    private suspend fun takeNextStop(queue: java.util.concurrent.BlockingDeque<DapEvent.Stopped>): DapEvent.Stopped {
        while (true) {
            queue.pollFirst()?.let { return it }
            delay(20)
        }
    }

    private companion object {
        val HANDSHAKE_TIMEOUT = 20.seconds
        val REQUEST_TIMEOUT = 10.seconds
        val RUNNING_GRACE = 1.seconds
        val BREAKPOINT_HIT_TIMEOUT = 10.seconds
        val MID_FLIGHT_PROBE_TIMEOUT = 5.seconds

        /** Editor-visible line of `printf("tick %ld\n", counter);` in spin.c. */
        const val LOOP_BODY_LINE = 20

        val SPIN_BINARY: Path = Paths.get(
            System.getProperty("user.dir"), "src", "test", "resources", "fixtures", "c", "spin",
        )
        val SPIN_SOURCE: Path = Paths.get(
            System.getProperty("user.dir"), "src", "test", "resources", "fixtures", "c", "spin.c",
        )
    }
}
