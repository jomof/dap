package com.github.jomof.dap.breakpoints

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encapsulates the "pause → setBreakpoints → continue" workaround that
 * spec-conformant DAP adapters (notably lldb-dap up to LLVM 23) need so
 * that mid-session breakpoint additions actually take effect.
 *
 * ## Background
 *
 * `setBreakpoints` sent while the inferior is running is reported back
 * as `verified=true` by lldb-dap, but the breakpoint is **not armed**
 * against the running process image — it only takes effect on the
 * adapter's next stop. End user observation: "I click the gutter while
 * the program is running and the red dot just gets ignored."
 *
 * The fix is to briefly pause the inferior, send `setBreakpoints`
 * while it's stopped, and immediately resume. The synthetic pause is
 * filtered out before it reaches the IDE UI so the user never sees a
 * spurious "paused" state.
 *
 * ## Why this is its own class
 *
 * Previously inlined in `DapDebugProcess`, which made it untestable
 * without spinning up the IntelliJ Platform. Hoisting it gives us a
 * clean unit-test seam and lets us repair a real bug that lived in
 * the inline version: when no `Stopped` event had ever arrived
 * (i.e. the very common "launch without stopOnEntry, immediately
 * toggle a breakpoint" path), the wrapper had no cached thread id and
 * silently bypassed the pause-install-resume dance — falling straight
 * back into the bug it was supposed to work around.
 *
 * ## How the gate stays informed
 *
 * Thread ids are populated from multiple DAP signals — whichever one
 * arrives first is fine:
 *
 *  - `thread` events with `reason="started"` (lldb-dap and codelldb
 *    both emit one for the main thread before the first instruction
 *    executes).
 *  - `continued` events (emitted by the adapter when it resumes,
 *    including the post-`configurationDone` resume).
 *  - `stopped` events (the original source — still wired).
 *  - As a last resort, [wrappedSetBreakpoints] asks the adapter via
 *    [getThreadsLastResort] if it can. Some adapters (CodeLLDB; older
 *    lldb-dap) accept `threads()` while running; others reject with
 *    `notStopped`. We tolerate either outcome.
 *
 * The gate is thread-safe; mutations to running/threadId/pending
 * fields use `@Volatile` reads + atomic counter writes; the
 * pause-install-resume dance is serialised by an internal mutex so
 * concurrent breakpoint flushes don't interleave their pause/continue
 * pairs.
 */
class DapSyntheticPauseGate(
    private val pauseAdapter: suspend (Int) -> Unit,
    private val continueAdapter: suspend (Int) -> Unit,
    private val setBreakpointsRaw: suspend (SetBreakpointsArguments) -> SetBreakpointsResponse,
    /**
     * Optional last-resort: ask the adapter for its threads when no
     * other source has yielded an id. Returns the first id, or `null`
     * if the request fails or the adapter has no threads. Implementors
     * should swallow `notStopped`-style errors and return `null`.
     */
    private val getThreadsLastResort: suspend () -> Int? = { null },
    /** Tunable so tests run fast; production defaults are generous. */
    private val pauseAckTimeoutMs: Long = 2_000,
    private val pauseAckPollMs: Long = 5,
) {

    private val log = logger<DapSyntheticPauseGate>()

    /**
     * Whether the adapter's inferior is currently executing. Flipped
     * to `false` on every observed `stopped` event and back to `true`
     * on every `continued`/`thread started` event AND every
     * user-driven resume/step the host wires through [markRunning].
     */
    @Volatile var inferiorRunning: Boolean = false
        private set

    /**
     * Last thread id we know about. Populated by every event that
     * carries one, in order to give [wrappedSetBreakpoints] a working
     * pause target even before any stop has happened.
     */
    @Volatile var lastKnownThreadId: Int? = null
        private set

    /**
     * Number of synthetic stops we expect to consume before passing a
     * `Stopped` event on to the IDE. Incremented before each
     * `pause()` we issue, decremented by [consumeIfSynthetic] when the
     * matching stop arrives.
     */
    private val pendingSyntheticPauses: AtomicInteger = AtomicInteger(0)

    private val syntheticPauseMutex: Mutex = Mutex()

    // ---- event sinks: callers route DAP events here ----------------

    /** Host calls this after the launch ack and after every user resume/step. */
    fun markRunning() {
        inferiorRunning = true
    }

    /**
     * Host calls this from its `onContinued` handler so the gate sees
     * adapter-initiated continues (e.g. the post-`configurationDone`
     * resume that gets the program off the launch line). The threadId
     * field is required by spec, but we accept null to keep this
     * tolerant of edge cases.
     */
    fun recordContinued(threadId: Int?) {
        inferiorRunning = true
        if (threadId != null) lastKnownThreadId = threadId
    }

    /**
     * Host calls this for every `thread` event. We only care about
     * `started` for thread-id discovery — `exited` doesn't invalidate
     * the cache because the user may toggle a breakpoint after one
     * thread exits but while others are still running.
     */
    fun recordThreadStarted(threadId: Int?) {
        if (threadId != null) lastKnownThreadId = threadId
    }

    /**
     * Host calls this from its `onStopped` handler **before** dispatching
     * to the IDE UI. Returns `true` when this stop was caused by a
     * synthetic pause we issued — the host should then swallow the
     * event (no `positionReached`, no UI update) because the user
     * never asked to pause.
     *
     * ## Why we don't filter by `reason`
     *
     * An earlier version of this code gated consumption on
     * `reason == "pause"`, on the theory that lldb-dap always reports
     * pause-induced stops that way. In practice lldb-dap is creative
     * with the reason string: pause-induced stops have been observed
     * as `"pause"`, `"exception"`, `"breakpoint"`, and `"signal"`
     * across versions and platforms (the existing pause/resume
     * integration test deliberately doesn't assert on the value for
     * this reason). The reason-string gate therefore failed to
     * consume the synthetic stop, it surfaced to the IDE, the wrapper
     * took the "real stop preempted us" branch (and crucially **did
     * not resume**), and the user's just-toggled breakpoint never got
     * a chance to fire. Symptom: "I added a breakpoint mid-session
     * and the IDE stopped on a different line."
     *
     * Trade-off: a real breakpoint / exception / signal that happens
     * to fire in the ~10ms window between our `pause()` and the
     * resulting stop event WILL be swallowed. We accept that risk —
     * the alternative is mid-session breakpoints being unconditionally
     * broken. Detection heuristics for the corner case can be added
     * later if it turns out to bite in practice.
     */
    fun consumeIfSynthetic(reason: String?, threadId: Int?): Boolean {
        inferiorRunning = false
        if (threadId != null) lastKnownThreadId = threadId
        if (pendingSyntheticPauses.get() <= 0) return false
        // Decrement only if still positive — preserves invariant.
        val newValue = pendingSyntheticPauses.decrementAndGet()
        // `reason` is logged so adapter-quirk reports can be triaged
        // without re-instrumenting; functionally we ignore it.
        if (newValue >= 0) {
            log.debug("consumeIfSynthetic: swallowed synthetic-pause stop (reason=$reason threadId=$threadId)")
        }
        return newValue >= 0
    }

    // ---- the wrapper itself ----------------------------------------

    /**
     * Sends `setBreakpoints` with the pause-install-resume workaround
     * when the inferior is running, otherwise as a direct passthrough.
     *
     * Acquires the synthetic-pause mutex so concurrent flushes don't
     * leak each other's pause/continue pairs. Re-checks
     * [inferiorRunning] inside the lock to handle the race where a
     * real stop arrives while we were waiting for the mutex.
     */
    suspend fun wrappedSetBreakpoints(args: SetBreakpointsArguments): SetBreakpointsResponse {
        if (!inferiorRunning) return setBreakpointsRaw(args)
        return syntheticPauseMutex.withLock {
            if (!inferiorRunning) return@withLock setBreakpointsRaw(args)
            val threadId = lastKnownThreadId
                ?: getThreadsLastResort.invoke()?.also { lastKnownThreadId = it }
            if (threadId == null) {
                // No thread id from any source. lldb-dap's `pause`
                // pauses the whole process regardless of the id we
                // pass, so a sentinel of 1 (the universal "main
                // thread" convention) is overwhelmingly likely to
                // work. The alternative — skipping the dance and
                // sending setBreakpoints directly — is the bug we're
                // fixing, so prefer aggressive over silent.
                log.warn(
                    "synthetic pause: no cached thread id and threads() returned null; " +
                        "attempting pause with sentinel threadId=1",
                )
                return@withLock doPauseInstallResume(args, FALLBACK_THREAD_ID)
            }
            doPauseInstallResume(args, threadId)
        }
    }

    private suspend fun doPauseInstallResume(
        args: SetBreakpointsArguments,
        threadId: Int,
    ): SetBreakpointsResponse {
        pendingSyntheticPauses.incrementAndGet()
        var pauseTookEffect = false
        // `true` once we've already rolled the counter back along an
        // error path; prevents the `finally` clean-up from double-
        // decrementing into a negative counter (which would silently
        // swallow the next real stop event).
        var counterRolledBack = false
        try {
            log.debug("synthetic pause: pausing threadId=$threadId before mid-session setBreakpoints")
            try {
                pauseAdapter(threadId)
            } catch (failure: Throwable) {
                // The pause itself failed (rare — adapter unresponsive
                // or thread id rejected). Roll back the counter so
                // consumeIfSynthetic doesn't decrement it on some
                // unrelated future stop, and send setBreakpoints
                // directly. May not arm, but it's our best effort.
                pendingSyntheticPauses.decrementAndGet()
                counterRolledBack = true
                log.warn("synthetic pause: pause request failed; sending setBreakpoints directly", failure)
                return setBreakpointsRaw(args)
            }

            val deadline = System.currentTimeMillis() + pauseAckTimeoutMs
            while (inferiorRunning && System.currentTimeMillis() < deadline) {
                delay(pauseAckPollMs)
            }
            if (inferiorRunning) {
                log.warn("synthetic pause: pause didn't take effect in ${pauseAckTimeoutMs}ms; sending setBreakpoints anyway")
                pendingSyntheticPauses.decrementAndGet()
                counterRolledBack = true
                return setBreakpointsRaw(args)
            }
            // The inferior is now stopped. Was it OUR pause (counter
            // was decremented by consumeIfSynthetic) or did a real
            // stop preempt us (counter still pending)?
            pauseTookEffect = pendingSyntheticPauses.get() < 1
            return setBreakpointsRaw(args)
        } finally {
            when {
                pauseTookEffect -> try {
                    inferiorRunning = true
                    continueAdapter(threadId)
                } catch (failure: Throwable) {
                    log.warn("synthetic pause: failed to resume after install", failure)
                }
                counterRolledBack -> {
                    // Already cleaned up on the error path; nothing to do.
                }
                else -> {
                    // A real stop preempted us. Don't resume — the user
                    // is expecting to see the debugger stopped at whatever
                    // caused it. Roll back our counter since the matching
                    // stop event won't arrive.
                    pendingSyntheticPauses.decrementAndGet()
                    log.debug("synthetic pause: real stop preempted our pause; leaving inferior stopped")
                }
            }
        }
    }

    /**
     * Test seam: read the current pending-pause counter. Production
     * never needs to inspect it.
     */
    internal fun pendingSyntheticPauseCount(): Int = pendingSyntheticPauses.get()

    companion object {
        /**
         * Universal "main thread" sentinel used by lldb-dap when no
         * other thread id is known. lldb-dap pauses the entire
         * process regardless of the id passed, so this works as long
         * as the adapter speaks the lldb-dap dialect.
         */
        private const val FALLBACK_THREAD_ID: Int = 1
    }
}
