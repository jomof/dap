package com.github.jomof.dap.integration

import com.github.jomof.dap.breakpoints.DapSyntheticPauseGate
import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
import com.github.jomof.dap.client.DapTransport
import com.github.jomof.dap.scaffold.locator.LldbDapLocator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
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
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.junit.After
import org.junit.Assert.assertEquals
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
 * End-to-end verification of [DapSyntheticPauseGate] driving a real
 * `lldb-dap` through the **exact IDE flow** the user reported broken:
 *
 *  1. Launch the spin fixture **without `stopOnEntry`** — so the
 *     adapter never emits an initial `stopped` event the way the
 *     stop-on-entry path does.
 *  2. Without any other user interaction, send a mid-session
 *     `setBreakpoints` through `wrappedSetBreakpoints` (the production
 *     gate, NOT the inline copy in the older test).
 *  3. Assert that the breakpoint actually fires.
 *
 * The pre-fix `DapDebugProcess` failed step 2 silently: it had no
 * cached thread id (because [DapEvent.Stopped] is the only source it
 * looked at, and no stop had happened yet), so the wrapper bypassed
 * the pause-install-resume dance and lldb-dap quietly dropped the
 * install. The fix routes `Continued` and `Thread` events into the
 * gate so a thread id is available immediately after launch, AND adds
 * a `threads()` fallback + sentinel for the truly cold-start case.
 *
 * Skipped unless `DAP_INTEGRATION_TESTS=1` is set and the spin
 * fixture has been built (`bash src/test/resources/fixtures/c/build.sh`).
 */
class LldbDapMidSessionBreakpointIdeFlowIntegrationTest {

    private var process: Process? = null
    private var client: DapClient? = null

    @Before fun setUp() {
        assumeTrue("Set DAP_INTEGRATION_TESTS=1 to enable", System.getenv("DAP_INTEGRATION_TESTS") == "1")
        val binary = LldbDapLocator.resolve()
        assumeTrue("lldb-dap not found on this host", binary != null && Files.isExecutable(binary))
        assumeTrue(
            "spin binary not built — run src/test/resources/fixtures/c/build.sh",
            Files.isExecutable(SPIN_BINARY),
        )
        process = ProcessBuilder(binary!!.toString()).redirectErrorStream(false).start()
    }

    @After fun tearDown() {
        client?.dispose()
        process?.destroy()
    }

    @Test fun `production gate arms mid-session breakpoint after launch with no prior stop`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapMidSessionBreakpointIdeFlowIntegrationTest.client = client

        // Wire the production gate exactly as DapDebugProcess does.
        val gate = DapSyntheticPauseGate(
            pauseAdapter = { tid -> client.pause(PauseArguments().apply { threadId = tid }) },
            continueAdapter = { tid ->
                client.continueExecution(ContinueArguments().apply { threadId = tid })
            },
            setBreakpointsRaw = { args -> client.setBreakpoints(args) },
            getThreadsLastResort = {
                try { client.threads().threads?.firstOrNull()?.id } catch (_: Throwable) { null }
            },
        )

        val stoppedEvents = java.util.concurrent.LinkedBlockingDeque<DapEvent.Stopped>()
        val allStopsTrace = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is DapEvent.Continued -> gate.recordContinued(event.payload.threadId)
                    is DapEvent.Thread -> gate.recordThreadStarted(event.payload.threadId)
                    is DapEvent.Stopped -> {
                        val isSynthetic = gate.consumeIfSynthetic(
                            event.payload.reason,
                            event.payload.threadId,
                            event.payload.hitBreakpointIds?.toList(),
                        )
                        // Trace EVERY stop with its full classification so a
                        // failure reproduces with enough info to triage.
                        allStopsTrace += "[${if (isSynthetic) "synthetic" else "surfaced"}] " +
                            "reason=${event.payload.reason} " +
                            "threadId=${event.payload.threadId} " +
                            "hitBp=${event.payload.hitBreakpointIds?.toList()}"
                        // `addLast` not `put`: the deque is unbounded so
                        // `put`'s "wait for capacity" semantic is dead
                        // code, but its `@Blocking` annotation lights up
                        // `BlockingMethodInNonBlockingContext` inside the
                        // suspend collector. `addLast` is the equivalent
                        // primitive that's correctly typed as non-blocking.
                        if (!isSynthetic) stoppedEvents.addLast(event)
                    }
                    else -> { /* irrelevant for this test */ }
                }
            }
        }

        try {
            // ── handshake: launch with NO stopOnEntry ────────────────────────
            withTimeout(HANDSHAKE_TIMEOUT) {
                client.initialize(
                    InitializeRequestArguments().apply {
                        clientID = "dap.intellij.integration-test"
                        clientName = "IntelliJ DAP (IDE-flow mid-session bp test)"
                        adapterID = "lldb-dap"
                        pathFormat = "path"
                        linesStartAt1 = true
                        columnsStartAt1 = true
                    },
                )
                val launchAck = client.launch(
                    mapOf<String, Any>("program" to SPIN_BINARY.toString()),
                )
                client.awaitInitialized()
                client.configurationDone()
                launchAck.await()
            }
            // The adapter is now running the spin loop. Mark inferior as
            // running just like DapDebugProcess.start() does at this point.
            gate.markRunning()
            delay(RUNNING_GRACE)

            // ── user toggles a breakpoint in the gutter mid-flight ───────────
            // This goes through the production wrapper, which must do
            // pause → setBreakpoints → continue (the synthetic stop is
            // consumed by the collector above).
            val args = SetBreakpointsArguments().apply {
                source = Source().apply {
                    path = SPIN_SOURCE.toString()
                    name = "spin.c"
                }
                breakpoints = arrayOf(SourceBreakpoint().apply { line = LOOP_BODY_LINE })
                sourceModified = false
            }
            val response = withTimeout(WRAPPER_TIMEOUT) { gate.wrappedSetBreakpoints(args) }
            val installed = response.breakpoints?.firstOrNull()
            requireNotNull(installed) { "adapter returned no breakpoint entry" }
            println(
                "IDE-flow install: requested line=$LOOP_BODY_LINE, " +
                    "responded line=${installed.line}, verified=${installed.isVerified}",
            )

            // ── the breakpoint MUST actually fire on the next loop iteration ─
            // and CRUCIALLY at line $LOOP_BODY_LINE. The previous version of
            // this test only checked reason ∈ {breakpoint, exception, ...},
            // which the leaked synthetic-pause stop accidentally satisfied —
            // masking the bug the user reported ("stops at a different line").
            val hit = withTimeout(BREAKPOINT_HIT_TIMEOUT) {
                while (true) {
                    stoppedEvents.pollFirst()?.let { return@withTimeout it }
                    delay(20.milliseconds)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val tid = hit.payload.threadId
                ?: error("surfaced stop event has no threadId; trace=$allStopsTrace")
            val stackResponse = withTimeout(WRAPPER_TIMEOUT) {
                client.stackTrace(StackTraceArguments().apply {
                    threadId = tid
                    startFrame = 0
                    levels = 1
                })
            }
            val topFrame = stackResponse.stackFrames?.firstOrNull()
                ?: error("no top frame returned; trace=$allStopsTrace")
            println(
                "post-install stop: reason=${hit.payload.reason} threadId=$tid " +
                    "topFrame=${topFrame.source?.name}:${topFrame.line} " +
                    "(name=${topFrame.name}) trace=$allStopsTrace",
            )
            assertTrue(
                "the surfaced stop must be the user's breakpoint hitting in the loop body, " +
                    "not the synthetic pause leaking through. Trace: $allStopsTrace",
                hit.payload.reason in setOf("breakpoint", "exception", "instruction breakpoint"),
            )
            assertEquals(
                "the stop MUST be at the breakpoint line (line $LOOP_BODY_LINE in spin.c). " +
                    "If this fails with the wrong line, the synthetic pause is leaking through " +
                    "to the IDE and the user sees the inferior parked wherever pause() happened " +
                    "to land. Trace: $allStopsTrace",
                LOOP_BODY_LINE,
                topFrame.line,
            )
            assertEquals(
                "the source file MUST be spin.c — anything else means we stopped in libc/runtime",
                "spin.c",
                topFrame.source?.name,
            )
        } finally {
            collector.cancel()
        }
    }

    private companion object {
        val HANDSHAKE_TIMEOUT = 15.seconds
        val WRAPPER_TIMEOUT = 10.seconds
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
