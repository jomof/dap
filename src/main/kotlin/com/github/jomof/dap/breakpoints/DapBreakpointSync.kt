package com.github.jomof.dap.breakpoints

import com.github.jomof.dap.client.DapCapabilities
import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.session.DapSessionScope
import com.github.jomof.dap.session.DapSourceMapper
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.SourceBreakpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coalesces per-file breakpoint mutations and ships them to the DAP server
 * with one `setBreakpoints` request per file. DAP requires the *full set* of
 * breakpoints for a file on every update, so we keep a local mirror and
 * resync on every change.
 *
 * A short debounce smooths over the burst of `registerBreakpoint` calls the
 * platform issues when the user toggles many breakpoints in quick succession
 * (e.g. "Disable all breakpoints"). A per-file mutex serialises in-flight
 * `setBreakpoints` requests so the adapter never sees an out-of-order update.
 *
 * Phase 2 additions:
 *  - Conditional / log / hit-count fields are pulled from the IDE breakpoint
 *    and dropped onto the [SourceBreakpoint] when the negotiated capabilities
 *    indicate the adapter supports them.
 *  - The adapter-issued `Breakpoint.id` is recorded so async `breakpoint`
 *    events (verified / message changes) can be routed back to the right
 *    IDE breakpoint by [com.github.jomof.dap.session.DapDebugProcess].
 */
class DapBreakpointSync(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
    private val sourceMapper: DapSourceMapper,
    private val capabilitiesProvider: () -> DapCapabilities?,
    private val onAdapterResponse: (XLineBreakpoint<*>, Breakpoint) -> Unit,
    /**
     * Wrapper around the raw `setBreakpoints` request. The default is a
     * straight passthrough; production code in
     * [com.github.jomof.dap.session.DapDebugProcess] supplies a wrapper that
     * performs a pause → setBreakpoints → continue cycle when the inferior
     * is running.
     *
     * Why the indirection: lldb-dap accepts `setBreakpoints` while running
     * and reports every entry as `verified=true`, but **does not actually
     * arm the breakpoints in the live process image** until the next time
     * the inferior stops. That makes mid-session breakpoint additions
     * silently inert — the user clicks the gutter, sees a red dot, and
     * watches their program ignore it (reproduced in
     * `LldbDapMidSessionBreakpointIntegrationTest`).
     *
     * Pause-install-resume is the lldb-dap-friendly workaround: a brief
     * synthetic pause flips the adapter into the state where breakpoints
     * are installed eagerly, then we resume.
     */
    private val setBreakpointsRequest: suspend (SetBreakpointsArguments) -> SetBreakpointsResponse =
        { args -> client.setBreakpoints(args) },
) {

    private val log = logger<DapBreakpointSync>()

    /** Mirror of every active breakpoint, grouped by file path. */
    private val state = mutableMapOf<String, MutableMap<XLineBreakpoint<*>, SourceBreakpoint>>()

    /**
     * Transient (one-shot) breakpoints — currently set by Run to Cursor.
     * They are tracked in parallel with [state] and merged into the
     * `setBreakpoints` payload, but never surfaced to IntelliJ as
     * `XLineBreakpoint`s. The adapter-issued id is captured in
     * [transientByDapId] so we can detect hits and drop the entry without
     * disturbing the user's own breakpoints in the same file.
     */
    private val transients = mutableMapOf<String, MutableList<TransientEntry>>()

    private val mutexes = mutableMapOf<String, Mutex>()
    private val pendingFlushes = mutableMapOf<String, Job>()

    /** DAP-issued id → originating IDE breakpoint, used to route async events. */
    private val byDapId = mutableMapOf<Int, XLineBreakpoint<*>>()

    /** DAP-issued id → transient breakpoint entry, used by [consumeHitTransients]. */
    private val transientByDapId = mutableMapOf<Int, TransientEntry>()

    @Synchronized
    fun set(breakpoint: XLineBreakpoint<*>) {
        val file = breakpoint.fileVirtualFile()
        if (file == null) {
            log.warn("set(): breakpoint has no resolvable VirtualFile, dropping: $breakpoint")
            return
        }
        val key = file.path
        val source = buildSourceBreakpoint(breakpoint)
        state.getOrPut(key) { linkedMapOf() }[breakpoint] = source
        log.info("set(): tracked ${breakpoint.type.id} at $key:${breakpoint.line + 1} (total in file=${state[key]?.size})")
        scheduleFlush(file)
    }

    @Synchronized
    fun remove(breakpoint: XLineBreakpoint<*>) {
        val file = breakpoint.fileVirtualFile() ?: return
        val key = file.path
        val perFile = state[key] ?: return
        perFile.remove(breakpoint)
        // Drop any id mappings that pointed at this breakpoint.
        byDapId.entries.removeIf { it.value === breakpoint }
        if (perFile.isEmpty()) {
            state.remove(key)
        }
        log.info("remove(): untracked ${breakpoint.type.id} at $key:${breakpoint.line + 1} (remaining in file=${perFile.size})")
        scheduleFlush(file)
    }

    /**
     * Synchronously flushes every tracked file *now*, bypassing the debounce,
     * and suspends until each `setBreakpoints` round-trip has completed.
     *
     * This is intended for the post-`initialized`, pre-`configurationDone`
     * window: per the DAP spec the adapter is free to resume the debuggee
     * immediately after `configurationDone`, so all breakpoints have to be
     * registered with the adapter before that signal is sent — otherwise a
     * fast-running program (Hello World, for example) can exit before any
     * breakpoint reaches the adapter.
     */
    suspend fun flushAllAndAwait() {
        // Cancel pending debounced flushes; we're going to issue them ourselves
        // and don't want a duplicate request landing right behind us.
        val files = synchronized(this) {
            pendingFlushes.values.forEach { it.cancel() }
            pendingFlushes.clear()
            state.keys.toList()
        }
        if (files.isEmpty()) {
            log.info("flushAllAndAwait(): no tracked breakpoints, nothing to flush")
            return
        }
        log.info("flushAllAndAwait(): flushing ${files.size} file(s) synchronously before configurationDone")
        files.forEach { path ->
            val anyBp = synchronized(this) { state[path]?.keys?.firstOrNull() } ?: return@forEach
            val virtualFile = anyBp.fileVirtualFile() ?: return@forEach
            try {
                flush(virtualFile)
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("flushAllAndAwait(): flush failed for $path", throwable)
            }
        }
    }

    /**
     * Phase 2: async `breakpoint` events from the server are dispatched here
     * so the IDE can refresh the breakpoint's verified state and message.
     */
    @Synchronized
    fun findByDapId(dapId: Int?): XLineBreakpoint<*>? {
        if (dapId == null) return null
        return byDapId[dapId]
    }

    @Synchronized
    private fun scheduleFlush(file: VirtualFile) {
        val key = file.path
        pendingFlushes[key]?.cancel()
        pendingFlushes[key] = sessionScope.scope.launch {
            try {
                delay(DEBOUNCE)
                flush(file)
            } catch (ce: CancellationException) {
                // Cancelled by a newer scheduleFlush; safe to drop.
                if (ce.message?.contains("disposed", ignoreCase = true) == true) throw ce
            }
        }
    }

    private suspend fun flush(file: VirtualFile) {
        val key = file.path
        val mutex = synchronized(this) { mutexes.getOrPut(key) { Mutex() } }
        mutex.withLock {
            // Snapshot both user breakpoints and transients in one critical
            // section so the two halves can't drift between collection and
            // the response-mapping pass below.
            data class Snapshot(
                val user: List<Pair<XLineBreakpoint<*>, SourceBreakpoint>>,
                val transient: List<TransientEntry>,
            )
            val snapshot = synchronized(this) {
                Snapshot(
                    user = state[key]?.entries?.map { it.key to it.value } ?: emptyList(),
                    transient = transients[key]?.toList() ?: emptyList(),
                )
            }
            val source = sourceMapper.toDapSource(file)
            val combinedSources: Array<SourceBreakpoint> =
                (snapshot.user.map { it.second } + snapshot.transient.map { it.source }).toTypedArray()
            val args = SetBreakpointsArguments().apply {
                this.source = source
                this.breakpoints = combinedSources
                this.sourceModified = false
            }
            log.info(
                "flush(): sending setBreakpoints — file=$key userLines=${snapshot.user.map { it.second.line }} " +
                    "transientLines=${snapshot.transient.map { it.source.line }}",
            )
            try {
                val response = setBreakpointsRequest(args)
                val responses = response.breakpoints ?: emptyArray()
                synchronized(this) {
                    responses.forEachIndexed { index, bp ->
                        when {
                            index < snapshot.user.size -> {
                                val bridge = snapshot.user[index].first
                                bp.id?.let { id -> byDapId[id] = bridge }
                                log.info(
                                    "flush(): user-bp response — file=$key line=${bp.line} verified=${bp.isVerified} " +
                                        "dapId=${bp.id} message=${bp.message ?: "(none)"}",
                                )
                                onAdapterResponse(bridge, bp)
                            }
                            else -> {
                                val tIndex = index - snapshot.user.size
                                val entry = snapshot.transient.getOrNull(tIndex) ?: return@forEachIndexed
                                val newId = bp.id
                                // Drop the previous id mapping (the adapter may renumber the
                                // breakpoint on every setBreakpoints call) and install the new
                                // one so consumeHitTransients() can match the hit reliably.
                                entry.dapId?.let { transientByDapId.remove(it) }
                                entry.dapId = newId
                                if (newId != null) transientByDapId[newId] = entry
                                log.info(
                                    "flush(): transient-bp response — file=$key line=${bp.line} verified=${bp.isVerified} dapId=$newId",
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("setBreakpoints failed for $key", throwable)
            }
        }
    }

    /**
     * Adds a transient (one-shot) source breakpoint at [file]:[line] and
     * synchronously ships the full per-file breakpoint set to the adapter.
     *
     * Returns when the adapter has acknowledged the `setBreakpoints` request,
     * which lets [com.github.jomof.dap.session.DapDebugProcess.runToPosition]
     * resume execution only once the breakpoint is actually armed.
     *
     * The transient does NOT appear in the IDE's breakpoints panel; it is
     * removed automatically by [consumeHitTransients] when the adapter
     * reports a stop on its DAP id.
     */
    suspend fun installTransientLineBreakpoint(file: VirtualFile, line: Int) {
        // Cancel any pending debounced flush for this file before mutating —
        // we want our own awaited flush to be the next thing the adapter sees,
        // not a stale earlier snapshot.
        val key = file.path
        synchronized(this) {
            pendingFlushes.remove(key)?.cancel()
            val source = SourceBreakpoint().apply {
                this.line = sourceMapper.toDapLine(line)
            }
            transients.getOrPut(key) { mutableListOf() }.add(TransientEntry(file = file, source = source))
            log.info("installTransientLineBreakpoint(): added at $key:${line + 1}")
        }
        flush(file)
    }

    /**
     * If any of [hitBreakpointIds] matches a tracked transient, removes the
     * transient from the per-file mirror and asynchronously reflushes that
     * file's breakpoint set (so the now-spent transient is unarmed for the
     * next continue).
     *
     * Idempotent and cheap when there's nothing to do — the common case.
     */
    fun consumeHitTransients(hitBreakpointIds: List<Int>?) {
        if (hitBreakpointIds.isNullOrEmpty()) return
        val affectedFiles: List<VirtualFile> = synchronized(this) {
            val matched = hitBreakpointIds.mapNotNull { transientByDapId[it] }
            if (matched.isEmpty()) return
            val files = mutableSetOf<VirtualFile>()
            matched.forEach { entry ->
                transients[entry.file.path]?.remove(entry)
                entry.dapId?.let { transientByDapId.remove(it) }
                files += entry.file
                log.info("consumeHitTransients(): dropped transient at ${entry.file.path}:${entry.source.line}")
            }
            files.toList()
        }
        affectedFiles.forEach { scheduleFlush(it) }
    }

    private fun buildSourceBreakpoint(breakpoint: XLineBreakpoint<*>): SourceBreakpoint {
        val capabilities = capabilitiesProvider()
        return SourceBreakpoint().apply {
            line = sourceMapper.toDapLine(breakpoint.line)
            if (capabilities?.supportsConditionalBreakpoints == true) {
                breakpoint.conditionExpression?.expression?.takeIf { it.isNotBlank() }?.let { condition = it }
            }
            if (capabilities?.supportsLogPoints == true) {
                breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }?.let {
                    logMessage = toDapLogMessage(it)
                }
            }
            // The IntelliJ XBreakpoint API doesn't expose a first-class hit-count field
            // on every breakpoint type; Phase 2 leaves hitCondition empty and revisits
            // once we add a dedicated UI for it.
        }
    }

    /** Cancels any pending flushes; called from [com.github.jomof.dap.session.DapDebugProcess.stop]. */
    @Synchronized
    fun shutdown() {
        pendingFlushes.values.forEach { it.cancel() }
        pendingFlushes.clear()
        transients.clear()
        transientByDapId.clear()
    }

    /**
     * Bookkeeping for a transient (one-shot) breakpoint. Mutable because the
     * adapter may issue a fresh id on every `setBreakpoints` round-trip and
     * we need [consumeHitTransients] to match against the latest one.
     */
    private class TransientEntry(
        val file: VirtualFile,
        val source: SourceBreakpoint,
        @Volatile var dapId: Int? = null,
    )

    private fun XLineBreakpoint<*>.fileVirtualFile(): VirtualFile? {
        val url = fileUrl ?: return null
        return com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(url)
    }

    companion object {
        private val DEBOUNCE: Duration = 80.milliseconds

        /**
         * Translates an IntelliJ "Evaluate and log" expression into a DAP
         * [SourceBreakpoint.logMessage] value.
         *
         * DAP's `logMessage` is a format string in which substrings of the
         * form `{expr}` get evaluated and substituted by the adapter
         * server-side (rest of the string is emitted verbatim). IntelliJ's
         * `logExpressionObject` is a single target-language expression with
         * no template syntax, so we wrap it in braces to ask the adapter
         * to evaluate the whole thing.
         *
         * Heuristic for "already a DAP-style template": if the expression
         * contains a `{...}` pair we assume the user (or a future IntelliJ
         * feature) already provided a template and pass it through as-is.
         * Single trailing/leading braces inside a real expression (e.g. a
         * C++ lambda body) intentionally tip us into the wrap path because
         * the adapter then evaluates the lambda exactly once and prints the
         * result, which matches the user's likely intent.
         */
        internal fun toDapLogMessage(intellijExpression: String): String {
            val trimmed = intellijExpression.trim()
            val looksLikeTemplate = TEMPLATE_PROBE.containsMatchIn(trimmed)
            return if (looksLikeTemplate) intellijExpression else "{$intellijExpression}"
        }

        /** `{...}` with non-empty payload — the marker that this is a DAP-style template. */
        private val TEMPLATE_PROBE = Regex("""\{[^{}]+}""")
    }
}
