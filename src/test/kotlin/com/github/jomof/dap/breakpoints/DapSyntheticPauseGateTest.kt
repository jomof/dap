package com.github.jomof.dap.breakpoints

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [DapSyntheticPauseGate]. Each test wires the gate to a
 * tiny recording set of adapter callbacks and drives both halves of the
 * dance — the wrapper coroutine on one side, the simulated adapter
 * (which fires the `stopped` event back into the gate via
 * [DapSyntheticPauseGate.consumeIfSynthetic]) on the other.
 *
 * The "bug we're fixing" regression test is
 * [`wrappedSetBreakpoints pauses even with no prior stopped event`].
 * The old inline wrapper inside `DapDebugProcess` skipped the
 * pause-install-resume dance whenever `lastKnownThreadId` was null —
 * exactly the case the user hit when toggling a breakpoint on a freshly
 * launched (no stopOnEntry) program. The new gate uses a sentinel +
 * threads() fallback so the dance always runs, and the breakpoint is
 * actually armed.
 */
class DapSyntheticPauseGateTest {

    @Test fun `passthrough when inferior is not running — no pause issued`() = runBlocking {
        val recorder = AdapterRecorder()
        val gate = recorder.gate()
        // Don't markRunning; inferiorRunning stays false.
        val response = withTimeout(TIMEOUT) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        assertEquals(1, response.breakpoints.size)
        assertEquals(0, recorder.pauseCalls.get())
        assertEquals(0, recorder.continueCalls.get())
        assertEquals(1, recorder.setBpCalls.get())
    }

    @Test fun `pauses and installs and resumes when running with cached threadId`() = runBlocking {
        val recorder = AdapterRecorder()
        val gate = recorder.gate()
        gate.recordThreadStarted(7) // adapter emitted `thread started` event
        gate.markRunning()

        // Simulate the adapter firing a stopped event in response to
        // our synthetic pause. The wrapper polls `inferiorRunning`,
        // so we coroutine-race a stop on the side.
        val wrapper = async(start = CoroutineStart.UNDISPATCHED) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        // Wait until the wrapper has actually issued the pause. The
        // pendingSyntheticPauses counter ticks up inside doPauseInstallResume,
        // immediately before the pause() call.
        withTimeout(TIMEOUT) {
            while (gate.pendingSyntheticPauseCount() == 0) delay(2.milliseconds)
        }
        // Fire the simulated stop with the canonical reason. The gate
        // should consume it.
        val consumed = gate.consumeIfSynthetic(reason = "pause", threadId = 7)
        assertTrue("gate should claim the synthetic stop", consumed)

        val response = withTimeout(TIMEOUT) { wrapper.await() }
        assertEquals(1, response.breakpoints.size)
        assertEquals("pause should have been issued exactly once", 1, recorder.pauseCalls.get())
        assertEquals("continue should have been issued exactly once", 1, recorder.continueCalls.get())
        assertEquals("setBreakpoints should run AFTER pause completes", 1, recorder.setBpCalls.get())
        assertEquals(7, recorder.lastPauseThreadId.get())
        assertEquals(7, recorder.lastContinueThreadId.get())
        assertEquals("counter should drain back to 0", 0, gate.pendingSyntheticPauseCount())
        assertTrue("inferior should be running again after the dance", gate.inferiorRunning)
    }

    @Test fun `wrappedSetBreakpoints pauses even with no prior stopped event`() = runBlocking {
        // ─── REGRESSION TEST FOR THE BUG ──────────────────────────────────────
        // Old behaviour (inline wrapper in DapDebugProcess): if no Stopped
        // event has fired yet, `lastKnownThreadId` is null and the wrapper
        // falls through to a direct `setBreakpoints` call — which lldb-dap
        // accepts but silently fails to arm against the running process.
        //
        // New behaviour (gate): with threads() as the next fallback, the
        // gate finds a thread id, runs the full pause-install-resume dance,
        // and the breakpoint actually fires. If neither path yields a
        // thread id, the gate uses a sentinel of 1 (which lldb-dap's
        // pause-the-whole-process semantics tolerate) rather than skipping.
        val recorder = AdapterRecorder(threadsLastResort = 3)
        val gate = recorder.gate()
        gate.markRunning()
        // Deliberately do NOT call recordThreadStarted / recordContinued
        // / recordStopped — this mirrors the user's scenario exactly.

        val wrapper = async(start = CoroutineStart.UNDISPATCHED) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        withTimeout(TIMEOUT) {
            while (gate.pendingSyntheticPauseCount() == 0) delay(2.milliseconds)
        }
        gate.consumeIfSynthetic(reason = "pause", threadId = 3)

        val response = withTimeout(TIMEOUT) { wrapper.await() }
        assertEquals(1, response.breakpoints.size)
        assertEquals(
            "BUG FIX: pause MUST have been issued even with no prior stop",
            1,
            recorder.pauseCalls.get(),
        )
        assertEquals(
            "BUG FIX: continue MUST have been issued after install",
            1,
            recorder.continueCalls.get(),
        )
        assertEquals(3, recorder.lastPauseThreadId.get())
    }

    @Test fun `wrappedSetBreakpoints uses sentinel threadId when threads() also returns null`() = runBlocking {
        val recorder = AdapterRecorder(threadsLastResort = null)
        val gate = recorder.gate()
        gate.markRunning()
        // No thread id from any source.

        val wrapper = async(start = CoroutineStart.UNDISPATCHED) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        withTimeout(TIMEOUT) {
            while (gate.pendingSyntheticPauseCount() == 0) delay(2.milliseconds)
        }
        // Adapter would emit a stop with whatever thread id it picked;
        // the gate doesn't care which one we surface here.
        gate.consumeIfSynthetic(reason = "pause", threadId = 99)

        withTimeout(TIMEOUT) { wrapper.await() }
        assertEquals(
            "sentinel threadId 1 (universal main-thread convention) must be used",
            1,
            recorder.lastPauseThreadId.get(),
        )
        assertEquals(1, recorder.pauseCalls.get())
        assertEquals(1, recorder.continueCalls.get())
    }

    @Test fun `recordContinued captures threadId so the next wrap can pause immediately`() = runBlocking {
        val recorder = AdapterRecorder()
        val gate = recorder.gate()
        gate.recordContinued(13)
        assertEquals(13, gate.lastKnownThreadId)

        val wrapper = async(start = CoroutineStart.UNDISPATCHED) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        withTimeout(TIMEOUT) {
            while (gate.pendingSyntheticPauseCount() == 0) delay(2.milliseconds)
        }
        gate.consumeIfSynthetic(reason = "pause", threadId = 13)
        withTimeout(TIMEOUT) { wrapper.await() }
        assertEquals(13, recorder.lastPauseThreadId.get())
    }

    @Test fun `recordThreadStarted captures threadId`() {
        val gate = AdapterRecorder().gate()
        assertNull(gate.lastKnownThreadId)
        gate.recordThreadStarted(21)
        assertEquals(21, gate.lastKnownThreadId)
    }

    @Test fun `consumeIfSynthetic ignores the reason string entirely`() {
        // The gate used to filter by reason=="pause" — but lldb-dap
        // emits pause-induced stops with a wild assortment of reasons
        // ("pause", "exception", "breakpoint", "signal") across
        // versions. The gate must therefore consume *any* stop that
        // arrives while a synthetic pause is pending.
        val gate = AdapterRecorder().gate()
        // Without a pending pause, nothing is consumed regardless of reason.
        assertFalse(gate.consumeIfSynthetic(reason = "pause", threadId = 7))
        assertFalse(gate.consumeIfSynthetic(reason = "breakpoint", threadId = 7))
        // lastKnownThreadId still updates on every observed stop.
        assertEquals(7, gate.lastKnownThreadId)
    }

    @Test fun `synthetic stop with reason!=pause is still consumed — the bug we fix`() = runBlocking {
        // ─── REGRESSION ─────────────────────────────────────────────────
        // Real lldb-dap reproducer: when the pause-induced stop arrives
        // with reason="exception" (or "breakpoint"), the old reason-
        // string gate failed to consume it. The IDE saw the synthetic
        // pause, then the wrapper took the "real stop preempted" branch
        // and never resumed — the user's new breakpoint was installed
        // but the inferior was parked on the random line where the
        // pause happened to land.
        val recorder = AdapterRecorder()
        val gate = recorder.gate()
        gate.recordThreadStarted(7)
        gate.markRunning()

        val wrapper = async(start = CoroutineStart.UNDISPATCHED) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        withTimeout(TIMEOUT) {
            while (gate.pendingSyntheticPauseCount() == 0) delay(2.milliseconds)
        }
        // lldb-dap quirk: pause-induced stop arrives with reason="exception".
        val consumed = gate.consumeIfSynthetic(reason = "exception", threadId = 7)
        assertTrue(
            "BUG FIX: lldb-dap's reason=\"exception\" pause stop MUST be swallowed by the gate",
            consumed,
        )

        withTimeout(TIMEOUT) { wrapper.await() }
        assertEquals(1, recorder.pauseCalls.get())
        assertEquals(
            "BUG FIX: gate MUST resume after install when the stop was synthetic, " +
                "even when reason was \"exception\" — otherwise the breakpoint never gets to fire",
            1,
            recorder.continueCalls.get(),
        )
    }

    @Test fun `real breakpoint hit is not consumed while synthetic pause is pending`() = runBlocking {
        val recorder = AdapterRecorder()
        val gate = recorder.gate()
        gate.recordThreadStarted(7)
        gate.markRunning()

        val wrapper = async(start = CoroutineStart.UNDISPATCHED) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        withTimeout(TIMEOUT) {
            while (gate.pendingSyntheticPauseCount() == 0) delay(2.milliseconds)
        }

        val consumed = gate.consumeIfSynthetic(
            reason = "breakpoint",
            threadId = 7,
            hitBreakpointIds = arrayOf(123),
        )
        assertFalse(
            "a stop with concrete hitBreakpointIds is the user's breakpoint hit, not our synthetic pause",
            consumed,
        )

        val response = withTimeout(TIMEOUT) { wrapper.await() }
        assertEquals(1, response.breakpoints.size)
        assertEquals(1, recorder.pauseCalls.get())
        assertEquals(
            "real breakpoint hits must remain stopped for the IDE instead of being auto-resumed",
            0,
            recorder.continueCalls.get(),
        )
        assertEquals(
            "counter should be rolled back after preserving the real stop",
            0,
            gate.pendingSyntheticPauseCount(),
        )
        assertFalse("inferior should remain stopped on the real breakpoint hit", gate.inferiorRunning)
    }

    @Test fun `pause failure falls back to direct setBreakpoints without leaking the counter`() = runBlocking {
        val recorder = AdapterRecorder(pauseThrows = RuntimeException("simulated adapter crash"))
        val gate = recorder.gate()
        gate.recordThreadStarted(7)
        gate.markRunning()
        val response = withTimeout(TIMEOUT) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        assertEquals(1, response.breakpoints.size)
        // We attempted the pause (and it threw), so the counter went up
        // by one and must be rolled back to 0 — otherwise the NEXT real
        // stop event would be silently swallowed as "synthetic".
        assertEquals(0, gate.pendingSyntheticPauseCount())
        assertEquals(1, recorder.setBpCalls.get())
        assertEquals(0, recorder.continueCalls.get())
    }

    @Test fun `pause that never arrives — wrapper times out and falls back gracefully`() = runBlocking {
        // Tight timeout so the test stays fast.
        val recorder = AdapterRecorder()
        val gate = DapSyntheticPauseGate(
            pauseAdapter = recorder.pauseAdapter,
            continueAdapter = recorder.continueAdapter,
            setBreakpointsRaw = recorder.setBreakpointsRaw,
            getThreadsLastResort = { recorder.threadsLastResort },
            pauseAckTimeoutMs = 100,
            pauseAckPollMs = 5,
        )
        gate.recordThreadStarted(7)
        gate.markRunning()
        // Never fire any stopped event. Wrapper should time out, log a
        // warning, and send setBreakpoints anyway.
        val response = withTimeout(TIMEOUT) {
            gate.wrappedSetBreakpoints(buildArgs(line = 42))
        }
        assertEquals(1, response.breakpoints.size)
        assertEquals(0, gate.pendingSyntheticPauseCount())
        assertEquals(1, recorder.pauseCalls.get())
        assertEquals("no stop arrived, so no resume either", 0, recorder.continueCalls.get())
    }

    // ─── test helpers ────────────────────────────────────────────────────────

    private fun buildArgs(line: Int): SetBreakpointsArguments = SetBreakpointsArguments().apply {
        source = Source().apply {
            path = "/tmp/test.cpp"
            name = "test.cpp"
        }
        breakpoints = arrayOf(SourceBreakpoint().apply { this.line = line })
        sourceModified = false
    }

    private companion object {
        val TIMEOUT = 5.seconds
    }

    /**
     * Records all adapter-callback invocations and produces canned
     * responses. Configurable failure modes for the "what if pause
     * throws / times out" scenarios.
     */
    private class AdapterRecorder(
        val threadsLastResort: Int? = null,
        val pauseThrows: Throwable? = null,
    ) {
        val pauseCalls = AtomicInteger(0)
        val continueCalls = AtomicInteger(0)
        val setBpCalls = AtomicInteger(0)
        val lastPauseThreadId = AtomicReference<Int?>(null)
        val lastContinueThreadId = AtomicReference<Int?>(null)

        val pauseAdapter: suspend (Int) -> Unit = { tid ->
            pauseCalls.incrementAndGet()
            lastPauseThreadId.set(tid)
            pauseThrows?.let { throw it }
        }
        val continueAdapter: suspend (Int) -> Unit = { tid ->
            continueCalls.incrementAndGet()
            lastContinueThreadId.set(tid)
        }
        val setBreakpointsRaw: suspend (SetBreakpointsArguments) -> SetBreakpointsResponse = { args ->
            setBpCalls.incrementAndGet()
            SetBreakpointsResponse().apply {
                breakpoints = (args.breakpoints ?: emptyArray()).mapIndexed { idx, src ->
                    Breakpoint().apply {
                        id = idx + 1
                        isVerified = true
                        line = src.line
                    }
                }.toTypedArray()
            }
        }

        fun gate(): DapSyntheticPauseGate = DapSyntheticPauseGate(
            pauseAdapter = pauseAdapter,
            continueAdapter = continueAdapter,
            setBreakpointsRaw = setBreakpointsRaw,
            getThreadsLastResort = { threadsLastResort },
        )
    }
}
