package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
import com.github.jomof.dap.client.DapTransport
import com.github.jomof.dap.scaffold.locator.LldbDapLocator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Reproduces the user-reported behavior "if I start debugging and then set a
 * breakpoint, the breakpoint never hits even though the line is being
 * executed repeatedly."
 *
 * The test drives a real `lldb-dap` against the spin fixture (which prints
 * one tick every 100ms in an infinite loop). After configurationDone — when
 * the inferior is definitely running and producing output — we send a
 * `setBreakpoints` request for a line inside the loop body and assert that
 * a `stopped` event arrives within a reasonable window.
 *
 * What this proves:
 *  - PASS  → lldb-dap honours mid-session `setBreakpoints`; any IDE-side
 *            failure to break is a bug in our plugin code, not the adapter.
 *  - FAIL  → lldb-dap silently swallows mid-session breakpoint installs;
 *            we need to pause before set + resume after, or use some other
 *            workaround (e.g. ask for `supportsAsync*` capability).
 *
 * Skipped unless `DAP_INTEGRATION_TESTS=1` is set and both `lldb-dap` and
 * the spin binary are available (run `bash src/test/resources/fixtures/c/build.sh`).
 */
class LldbDapMidSessionBreakpointIntegrationTest {

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

    /**
     * Pinned as `@Ignore` because it documents a confirmed limitation of
     * lldb-dap (LLVM 23.0.0git as of this writing): a mid-session
     * `setBreakpoints` against a running inferior is acknowledged with
     * `verified=true` but the breakpoint is NOT armed in the live process
     * image. The breakpoint only takes effect after the next stop.
     *
     * This was the motivating bug for the synthetic-pause wrapper. Kept
     * as `@Ignore` (not deleted) so that future LLVM versions can be
     * re-tested simply by removing the annotation — if it ever passes, we
     * can drop the workaround.
     */
    @Ignore("lldb-dap LLVM 23 limitation; covered by synthetic-pause wrapper test below")
    @Test
    fun `setBreakpoints sent while inferior is running causes a stop`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapMidSessionBreakpointIntegrationTest.client = client

        // Capture every Stopped event into a thread-safe queue. We must
        // subscribe BEFORE configurationDone — if the breakpoint is hit
        // very quickly (which is exactly what we want to test), the
        // stopped event could arrive before a late subscriber sees it.
        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { stoppedEvents.put(it) }
        }

        try {
            // ---- handshake (no breakpoints yet) ----
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(
                    InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (mid-session bp integration test)"
                        adapterID = "lldb-dap"
                        pathFormat = "path"
                        linesStartAt1 = true
                        columnsStartAt1 = true
                    },
                )
                val launchAck = client.launch(
                    mapOf<String, Any>(
                        // Deliberately NOT stopOnEntry — we want the inferior
                        // to run freely so the next setBreakpoints we send
                        // happens against a live, executing process.
                        "program" to SPIN_BINARY.toString(),
                    ),
                )
                client.awaitInitialized()
                client.configurationDone()
                launchAck.await()
            }

            // Give the spin loop time to start producing ticks — we want
            // mid-session install, not a race against the entry point.
            delay(RUNNING_GRACE)

            // ---- install a breakpoint inside the loop while running ----
            // spin.c line 20 is `printf("tick %ld\n", counter);` — hit
            // every loop iteration, so a working install will fire on the
            // very next tick.
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
            // We log this so a failure shows the resolved line and verified
            // flag — useful for triaging "BP installed at the wrong line".
            println(
                "mid-session install: requested line=$LOOP_BODY_LINE, " +
                    "responded line=${installed!!.line}, verified=${installed.isVerified}, " +
                    "message=${installed.message ?: "(none)"}",
            )

            // ---- wait for the breakpoint to fire ----
            val hit = withTimeout(BREAKPOINT_HIT_TIMEOUT) { takeNextStop(stoppedEvents) }
            // lldb-dap is permissive about reason strings, but a mid-loop
            // hit is almost always "breakpoint". Print the actual value
            // for triage rather than asserting on it strictly.
            println("stopped event received: reason=${hit.payload.reason}, threadId=${hit.payload.threadId}")
            assertTrue(
                "the stop must be attributable to our breakpoint, not some random signal",
                hit.payload.reason in setOf("breakpoint", "exception", "instruction breakpoint"),
            )
        } finally {
            collector.cancel()
        }
    }

    /**
     * Counterpart to the running-install test: pause first, install the
     * breakpoint while the inferior is stopped, then resume. This is the
     * "expected to work" path — DAP adapters universally support
     * setBreakpoints when stopped, and the install takes effect on the
     * very next resume.
     *
     * If this passes and the running-install test fails, the production
     * fix is to wrap mid-session breakpoint installs in a
     * pause → setBreakpoints → continue sequence when the inferior is
     * currently running.
     */
    @Test
    fun `pause then setBreakpoints then continue causes the breakpoint to hit`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapMidSessionBreakpointIntegrationTest.client = client

        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { stoppedEvents.put(it) }
        }

        try {
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(
                    InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (pause-install-resume test)"
                        adapterID = "lldb-dap"
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

            // Pause the running inferior to get into a state where the
            // adapter actually installs the breakpoint into the live
            // process image.
            client.pause(PauseArguments().apply { threadId = 1 })
            val pauseStop = withTimeout(BREAKPOINT_HIT_TIMEOUT) { takeNextStop(stoppedEvents) }
            val tid = pauseStop.payload.threadId ?: client.threads().threads?.firstOrNull()?.id
                ?: error("no thread id available after pause stop")

            // Install the breakpoint while stopped.
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
                "pause-install: requested line=$LOOP_BODY_LINE, responded line=${installed!!.line}, " +
                    "verified=${installed.isVerified}",
            )

            // Resume and expect the next loop iteration to hit the breakpoint.
            client.continueExecution(ContinueArguments().apply { threadId = tid })
            val hit = withTimeout(BREAKPOINT_HIT_TIMEOUT) { takeNextStop(stoppedEvents) }
            println("post-resume stopped event: reason=${hit.payload.reason}, threadId=${hit.payload.threadId}")
            assertTrue(
                "the post-resume stop should be our breakpoint",
                hit.payload.reason in setOf("breakpoint", "exception", "instruction breakpoint"),
            )
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
            delay(20)
        }
    }

    /**
     * End-to-end verification of our production fix: a synthetic-pause
     * wrapper around `setBreakpoints` that activates only when the
     * inferior is running. This test mirrors what `DapDebugProcess`
     * does internally, against a real lldb-dap, so it pins the workaround
     * in place independently of the IDE plumbing.
     *
     * Procedure:
     *  1. Launch spin, let it run freely.
     *  2. Subscribe to stopped events and remember whether each one
     *     should be surfaced (real) or consumed (synthetic).
     *  3. Install a breakpoint via a `pause → setBreakpoints → continue`
     *     wrapper, exactly matching production code.
     *  4. Assert that the very next *surfaced* stop is the breakpoint
     *     hitting in the loop body, not the synthetic pause we used to
     *     install it.
     */
    @Test
    fun `synthetic-pause wrapper installs breakpoint mid-flight without surfacing the pause`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapMidSessionBreakpointIntegrationTest.client = client

        // Surfaced stops (real BP hits, real pause) go here. Synthetic
        // stops triggered by our wrapper are intercepted before they
        // reach this queue.
        val surfacedStops = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()

        // Synthetic-pause bookkeeping, exactly mirroring DapDebugProcess.
        val pendingSyntheticPauses = AtomicInteger(0)
        val inferiorRunning = AtomicBoolean(false)
        val lastKnownThreadId = AtomicReference<Int?>(null)
        val syntheticPauseMutex = Mutex()

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.filterIsInstance<DapEvent.Stopped>().collect { event ->
                inferiorRunning.set(false)
                event.payload.threadId?.let { lastKnownThreadId.set(it) }
                if (pendingSyntheticPauses.get() > 0 &&
                    pendingSyntheticPauses.decrementAndGet() >= 0
                ) {
                    // Synthetic stop — consume silently, exactly as the
                    // production onStopped() does. Don't enqueue it.
                    return@collect
                }
                surfacedStops.put(event)
            }
        }

        try {
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(
                    InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (synthetic-pause wrapper test)"
                        adapterID = "lldb-dap"
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
            inferiorRunning.set(true)
            delay(RUNNING_GRACE)

            // Production-equivalent synthetic-pause wrapper. Apologies for
            // duplicating the production logic here — it's deliberate. This
            // test is the canary that lets us catch regressions at the DAP
            // wire level, independent of any IntelliJ-side refactors.
            suspend fun wrappedSetBreakpoints(args: SetBreakpointsArguments) =
                if (!inferiorRunning.get()) {
                    client.setBreakpoints(args)
                } else {
                    syntheticPauseMutex.withLock {
                        val tid = lastKnownThreadId.get()
                            ?: client.threads().threads?.firstOrNull()?.id
                            ?: error("no thread id available for synthetic pause")
                        lastKnownThreadId.set(tid)
                        pendingSyntheticPauses.incrementAndGet()
                        client.pause(PauseArguments().apply { threadId = tid })
                        val deadline = System.currentTimeMillis() + 2_000
                        while (inferiorRunning.get() && System.currentTimeMillis() < deadline) {
                            delay(5)
                        }
                        try {
                            client.setBreakpoints(args)
                        } finally {
                            inferiorRunning.set(true)
                            client.continueExecution(ContinueArguments().apply { threadId = tid })
                        }
                    }
                }

            val setResponse = withTimeout(REQUEST_TIMEOUT) {
                wrappedSetBreakpoints(
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
                "synthetic-pause install: requested line=$LOOP_BODY_LINE, " +
                    "responded line=${installed!!.line}, verified=${installed.isVerified}",
            )

            // The next *surfaced* stop must be the breakpoint firing in the
            // loop body, NOT our synthetic pause (which was consumed in the
            // collector above). If the workaround broke and the synthetic
            // pause leaked through, the surfaced stop would have reason="pause"
            // and the loop-body breakpoint would never fire.
            val hit = withTimeout(BREAKPOINT_HIT_TIMEOUT) { takeNextStop(surfacedStops) }
            println("surfaced stop after wrapper: reason=${hit.payload.reason}, threadId=${hit.payload.threadId}")
            assertTrue(
                "surfaced stop must be a real breakpoint hit, not the synthetic pause",
                hit.payload.reason in setOf("breakpoint", "exception", "instruction breakpoint"),
            )
        } finally {
            collector.cancel()
        }
    }

    private companion object {
        val HANDSHAKE_TIMEOUT = 15.seconds
        val REQUEST_TIMEOUT = 10.seconds
        val RUNNING_GRACE = 1.seconds
        val BREAKPOINT_HIT_TIMEOUT = 10.seconds

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
