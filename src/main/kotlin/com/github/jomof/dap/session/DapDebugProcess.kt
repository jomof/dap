package com.github.jomof.dap.session

import com.github.jomof.dap.breakpoints.DapBreakpointHandler
import com.github.jomof.dap.breakpoints.DapBreakpointSync
import com.github.jomof.dap.breakpoints.DapExceptionBreakpoints
import com.github.jomof.dap.breakpoints.DapSyntheticPauseGate
import com.github.jomof.dap.client.DapCapabilities
import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapErrors
import com.github.jomof.dap.client.DapEvent
import com.github.jomof.dap.disassembly.DapDisassemblyProvider
import com.github.jomof.dap.disassembly.DapDisassemblyTab
import com.github.jomof.dap.evaluation.DapEditorsProvider
import com.github.jomof.dap.frames.DapExecutionStack
import com.github.jomof.dap.frames.DapStackFrame
import com.github.jomof.dap.frames.DapSuspendContext
import com.github.jomof.dap.language.DapLanguageProfile
import com.github.jomof.dap.output.DapOutputAdapter
import com.github.jomof.dap.reverse.DapRunInTerminalHandler
import com.github.jomof.dap.stepping.DapDropFrameHandler
import com.github.jomof.dap.stepping.DapSmartStepIntoHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler
import com.intellij.xdebugger.ui.XDebugTabLayouter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XDropFrameHandler
import com.intellij.xdebugger.frame.XSuspendContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.TerminateArguments
import org.eclipse.lsp4j.debug.ThreadsResponse

/**
 * The platform-facing bridge between IntelliJ's [XDebugProcess] contract and a
 * running DAP session. This is the **central transplantable component**: it
 * owns the [DapClient], the per-session coroutine scope, and the bookkeeping
 * for breakpoints, frames, and output forwarding.
 *
 * Lifecycle (Phase 1):
 *
 *   1. [start] — drives `initialize → launch|attach → wait Initialized
 *      → setBreakpoints (replay) → configurationDone`. Runs on the session
 *      coroutine scope; failures translate to [XDebugSession.reportError].
 *   2. Event pump — runs concurrently, translating DAP `stopped`,
 *      `continued`, `output`, `terminated`, `exited` into platform calls.
 *   3. User input — IntelliJ calls [resume], [startStepOver], …; each
 *      forwards to the adapter on the session scope.
 *   4. [stop] — best-effort `disconnect`/`terminate`, scope cancellation,
 *      transport teardown.
 */
class DapDebugProcess(
    session: XDebugSession,
    private val client: DapClient,
    private val profile: DapLanguageProfile,
    private val launchSpec: DapLaunchSpec,
    private val processHandler: ProcessHandler,
    private val console: ConsoleView,
    breakpointTypeClasses: List<Class<out XLineBreakpointType<*>>>,
    runInTerminalHandler: DapRunInTerminalHandler? = null,
    pathRemap: DapPathRemap = DapPathRemap.EMPTY,
    private val clientId: String = DEFAULT_CLIENT_ID,
) : XDebugProcess(session) {

    private val log = logger<DapDebugProcess>()

    private val sessionScope = DapSessionScope(session.sessionName)
    private val sourceReferenceCache = DapSourceReferenceCache(client, sessionScope)
    private val sourceMapper = DapSourceMapper(
        remap = pathRemap,
        sourceReferenceCache = sourceReferenceCache,
    )
    private val outputAdapter = DapOutputAdapter(console)

    // The breakpoint sync needs to dispatch into the handlers, but the handlers also
    // hold a reference to the sync; break the cycle with a lateinit list assigned below.
    private val handlers: MutableList<DapBreakpointHandler> = mutableListOf()

    /**
     * Encapsulates the pause-install-resume workaround needed for
     * mid-session breakpoint installs against running inferiors (lldb-dap
     * silently fails to arm them otherwise). The gate consumes the
     * synthetic `Stopped` event it triggers so the IDE UI never sees a
     * spurious pause.
     */
    private val syntheticPauseGate: DapSyntheticPauseGate = DapSyntheticPauseGate(
        pauseAdapter = { tid -> client.pause(PauseArguments().apply { threadId = tid }) },
        continueAdapter = { tid ->
            client.continueExecution(ContinueArguments().apply { threadId = tid })
        },
        setBreakpointsRaw = { args -> client.setBreakpoints(args) },
        getThreadsLastResort = {
            // Some adapters (CodeLLDB, older lldb-dap) accept `threads()`
            // while the inferior is running; others reject with
            // `notStopped`. The gate falls back to a sentinel id if this
            // returns null, so swallow any failure quietly.
            try {
                client.threads().threads?.firstOrNull()?.id
            } catch (failure: Throwable) {
                log.debug("getThreadsLastResort: threads() rejected while running", failure)
                null
            }
        },
    )

    private val breakpointSync: DapBreakpointSync = DapBreakpointSync(
        client = client,
        sessionScope = sessionScope,
        sourceMapper = sourceMapper,
        capabilitiesProvider = { capabilities },
        onAdapterResponse = ::dispatchAdapterResponse,
        setBreakpointsRequest = syntheticPauseGate::wrappedSetBreakpoints,
    )

    private val editorsProvider = DapEditorsProvider(profile)
    private val breakpointHandlers: Array<XBreakpointHandler<*>>

    private val runInTerminalAdvertised: Boolean = runInTerminalHandler != null

    init {
        breakpointTypeClasses.forEach { typeClass ->
            handlers += DapBreakpointHandler(session.project, breakpointSync, typeClass)
        }
        breakpointHandlers = handlers.toTypedArray<XBreakpointHandler<*>>()
        client.setRunInTerminalHandler(runInTerminalHandler)
        // Pause is opt-in at the session level: the platform's PauseAction
        // checks `XDebugSessionProxy#isPauseActionSupported` BEFORE consulting
        // `XDebugProcess#checkCanPerformCommands()`, and the flag defaults to
        // false. Without this, the Pause toolbar button is hidden and "Pause
        // Program" stays greyed even while the inferior is running. DAP always
        // supports the `pause` request (it's mandatory, not capability-gated),
        // so we unconditionally advertise it.
        session.setPauseActionSupported(true)
    }

    private fun dispatchAdapterResponse(breakpoint: XLineBreakpoint<*>, response: Breakpoint) {
        ApplicationManager.getApplication().invokeLater {
            handlers.forEach { it.applyAdapterResponse(breakpoint, response) }
        }
    }

    @Volatile private var capabilities: DapCapabilities? = null
    @Volatile private var initializedEventReceived = false

    /**
     * Convenience accessor: the current thread id the gate knows about.
     * Used by [startPausing] / [runToPosition] when the platform didn't
     * hand us a [DapSuspendContext] to pull a thread id from.
     */
    private val lastKnownThreadId: Int? get() = syntheticPauseGate.lastKnownThreadId

    /**
     * Defers `setBreakpoints` and `configurationDone` until the adapter has
     * sent the `initialized` event (per DAP spec). Until then,
     * [checkCanInitBreakpoints] returns false and we ignore the platform's
     * implicit "init breakpoints now" attempt.
     */
    override fun checkCanInitBreakpoints(): Boolean = initializedEventReceived

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider
    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = breakpointHandlers
    override fun doGetProcessHandler(): ProcessHandler = processHandler
    override fun createConsole(): ExecutionConsole = console

    private val smartStepInto: XSmartStepIntoHandler<*> by lazy {
        DapSmartStepIntoHandler(session, client, sessionScope)
    }

    override fun getSmartStepIntoHandler(): XSmartStepIntoHandler<*>? =
        if (capabilities?.supportsStepInTargetsRequest == true) smartStepInto else null

    private val dropFrame: XDropFrameHandler by lazy {
        DapDropFrameHandler(client, sessionScope)
    }

    override fun getDropFrameHandler(): XDropFrameHandler? =
        if (capabilities?.supportsRestartFrame == true) dropFrame else null

    private val disassemblyProvider = DapDisassemblyProvider(client)

    @Volatile private var disassemblyTab: DapDisassemblyTab? = null

    override fun createTabLayouter(): XDebugTabLayouter = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            // We CANNOT gate this on `capabilities?.supportsDisassembleRequest`
            // because the platform builds the Debug tool window UI (and calls
            // this method) eagerly during session start — synchronously,
            // before our async `initialize()` has returned. At this moment
            // `capabilities` is still null, so any direct capability gate
            // here always evaluates to "no, skip" and the tab never appears
            // even for adapters (lldb-dap, codelldb, dlv-dap) that DO
            // advertise the capability.
            //
            // Register the tab unconditionally and let DapDisassemblyTab.refresh()
            // re-check the live capability when the user (or a `stopped`
            // event) triggers a fetch. If the adapter doesn't support the
            // request, the tab shows a friendly "not supported" placeholder
            // instead of an empty pane.
            val tab = DapDisassemblyTab(
                project = session.project,
                session = session,
                sessionScope = sessionScope,
                provider = disassemblyProvider,
                capabilitiesProvider = { capabilities },
            )
            tab.register(ui)
            disassemblyTab = tab
        }
    }

    /**
     * Called by the scaffold runner after the `XDebugProcess` has been wired
     * into the session. Encapsulates the full DAP startup choreography.
     */
    fun start() {
        // Pump events forever (until the scope is cancelled).
        sessionScope.scope.launch {
            client.events.onEach { handleEvent(it) }.collect { /* terminal collector */ }
        }
        sessionScope.scope.launch {
            try {
                log.info("start(): sending initialize for profile=${profile.id} launchSpec=${launchSpec::class.simpleName}")
                val capabilities = client.initialize(buildInitializeArgs())
                this@DapDebugProcess.capabilities = capabilities
                log.info("start(): initialize OK — confDone=${capabilities.supportsConfigurationDoneRequest} cond=${capabilities.supportsConditionalBreakpoints} logPts=${capabilities.supportsLogPoints}")
                // Don't block on the launch/attach response yet: lldb-dap (and most
                // spec-conformant adapters) defer it until AFTER configurationDone,
                // so awaiting here would deadlock the whole handshake.
                val launchAck = when (launchSpec) {
                    is DapLaunchSpec.Launch -> client.launch(mergedLaunchArgs(launchSpec.args))
                    is DapLaunchSpec.Attach -> client.attach(mergedLaunchArgs(launchSpec.args))
                }
                log.info("start(): ${launchSpec::class.simpleName} dispatched, awaiting initialized event")
                // Wait for the adapter's "Initialized" event before sending the
                // breakpoint set, per the DAP lifecycle requirements.
                // (Uses a one-shot signal so we don't race the event-flow subscription.)
                client.awaitInitialized()
                initializedEventReceived = true
                log.info("start(): initialized event received, prompting platform to push breakpoints")
                // Wait for the platform to push every existing IDE breakpoint
                // down through registerBreakpoint() BEFORE we ack with
                // configurationDone — per the DAP spec the adapter may resume
                // the debuggee immediately on configurationDone, and a fast
                // program (Hello World) can exit before any breakpoint reaches
                // the adapter if we send configurationDone too early.
                ApplicationManager.getApplication().invokeAndWait {
                    session.initBreakpoints()
                }
                // Now state is populated; bypass the debounce and ship every
                // file's breakpoint set to the adapter, awaiting the response
                // so they're armed before the debuggee resumes.
                breakpointSync.flushAllAndAwait()
                DapExceptionBreakpoints.applyDefaults(profile, capabilities, client)
                if (capabilities.supportsConfigurationDoneRequest) {
                    log.info("start(): sending configurationDone")
                    client.configurationDone()
                }
                // Now the adapter is ready to settle the launch response.
                launchAck.await()
                // After configurationDone the adapter resumes the inferior unless
                // stopOnEntry is set (in which case a `stopped` event will flip
                // this back to false momentarily). Marking it running here is the
                // baseline for the synthetic-pause wrapper around setBreakpoints.
                syntheticPauseGate.markRunning()
                log.info("start(): launch response received — session is live")
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("DAP session startup failed", throwable)
                session.reportError("DAP session startup failed: ${DapErrors.toUserMessage(throwable)}")
                ApplicationManager.getApplication().invokeLater { session.stop() }
            }
        }
    }

    private suspend fun handleEvent(event: DapEvent) {
        when (event) {
            is DapEvent.Output -> outputAdapter.accept(event.payload)
            is DapEvent.Stopped -> onStopped(event)
            is DapEvent.Continued -> onContinued(event)
            is DapEvent.Thread -> syntheticPauseGate.recordThreadStarted(event.payload.threadId)
            is DapEvent.Breakpoint -> onBreakpointEvent(event)
            is DapEvent.Capabilities -> onCapabilitiesUpdate(event)
            is DapEvent.Terminated -> {
                log.info("DAP `terminated` event received; tearing down session")
                processHandler.destroyProcess()
                ApplicationManager.getApplication().invokeLater { session.stop() }
            }
            is DapEvent.Exited -> {
                log.info("DAP `exited` event received (exitCode=${event.payload.exitCode}); tearing down session")
                processHandler.destroyProcess()
                ApplicationManager.getApplication().invokeLater { session.stop() }
            }
            else -> { /* additional events are wired in later phases */ }
        }
    }

    /**
     * Merges adapter-pushed capability updates into our cached
     * [capabilities]. Adapters use this event to add features they only
     * discover after launch/attach — e.g. lldb-dap emits
     * `supportsStepInTargetsRequest` here once it knows the target triple
     * is x86, and `supportsRestartRequest` only for launch (not attach)
     * sessions. Without this merge those features remain invisible to
     * IntelliJ, which means Smart Step Into and Restart never appear in
     * the UI even when the adapter would honour them.
     *
     * The DAP spec models `capabilities` as a *partial* update — we
     * overlay onto the existing snapshot rather than replacing it. LSP4J
     * exposes the raw [Capabilities] type whose Boolean fields are
     * nullable; `null` means "no change" so we only copy non-null fields.
     */
    private fun onCapabilitiesUpdate(event: DapEvent.Capabilities) {
        val incoming = event.payload.capabilities ?: return
        val current = capabilities?.raw
        if (current == null) {
            capabilities = DapCapabilities(incoming)
            log.info("capabilities event: established initial capabilities snapshot")
            return
        }
        // Field-by-field overlay. There's no public copy/merge helper on
        // org.eclipse.lsp4j.debug.Capabilities, so we mutate the existing
        // payload directly. Only the flags the plugin reads need merging
        // today; new feature gates should add a line here.
        var changed = false
        incoming.supportsConfigurationDoneRequest?.let {
            if (current.supportsConfigurationDoneRequest != it) { current.supportsConfigurationDoneRequest = it; changed = true }
        }
        incoming.supportsFunctionBreakpoints?.let {
            if (current.supportsFunctionBreakpoints != it) { current.supportsFunctionBreakpoints = it; changed = true }
        }
        incoming.supportsConditionalBreakpoints?.let {
            if (current.supportsConditionalBreakpoints != it) { current.supportsConditionalBreakpoints = it; changed = true }
        }
        incoming.supportsHitConditionalBreakpoints?.let {
            if (current.supportsHitConditionalBreakpoints != it) { current.supportsHitConditionalBreakpoints = it; changed = true }
        }
        incoming.supportsLogPoints?.let {
            if (current.supportsLogPoints != it) { current.supportsLogPoints = it; changed = true }
        }
        incoming.supportsSetVariable?.let {
            if (current.supportsSetVariable != it) { current.supportsSetVariable = it; changed = true }
        }
        incoming.supportsEvaluateForHovers?.let {
            if (current.supportsEvaluateForHovers != it) { current.supportsEvaluateForHovers = it; changed = true }
        }
        incoming.supportsTerminateRequest?.let {
            if (current.supportsTerminateRequest != it) { current.supportsTerminateRequest = it; changed = true }
        }
        incoming.supportsExceptionFilterOptions?.let {
            if (current.supportsExceptionFilterOptions != it) { current.supportsExceptionFilterOptions = it; changed = true }
        }
        incoming.supportsStepInTargetsRequest?.let {
            if (current.supportsStepInTargetsRequest != it) { current.supportsStepInTargetsRequest = it; changed = true }
        }
        incoming.supportsDisassembleRequest?.let {
            if (current.supportsDisassembleRequest != it) { current.supportsDisassembleRequest = it; changed = true }
        }
        incoming.supportsRestartFrame?.let {
            if (current.supportsRestartFrame != it) { current.supportsRestartFrame = it; changed = true }
        }
        if (changed) {
            log.info(
                "capabilities event: merged updates — stepInTargets=${current.supportsStepInTargetsRequest} " +
                    "restartFrame=${current.supportsRestartFrame} disassemble=${current.supportsDisassembleRequest}",
            )
        }
    }

    private fun onBreakpointEvent(event: DapEvent.Breakpoint) {
        val payload = event.payload
        val breakpoint = payload.breakpoint ?: return
        val xbp = breakpointSync.findByDapId(breakpoint.id) ?: return
        dispatchAdapterResponse(xbp, breakpoint)
    }

    private suspend fun onStopped(event: DapEvent.Stopped) {
        // Hand the event to the gate FIRST. It flips inferiorRunning to
        // false, caches the thread id, and tells us whether this stop
        // was the synthetic one we issued to install a mid-session
        // breakpoint (in which case the IDE UI must NOT see it).
        //
        // Verified against lldb-dap source (EventHelper.cpp:234,
        // ProtocolEvents.cpp:80): a real pause request always reports
        // reason="pause"; real breakpoint hits never do. So gating
        // consumption on the reason closes the race where a real
        // breakpoint fires between our pause() and the stop event.
        val isSynthetic = syntheticPauseGate.consumeIfSynthetic(
            event.payload.reason,
            event.payload.threadId,
        )

        // Run-to-cursor (and any other transient) gets unarmed here so the
        // next continue doesn't rehit it. Done BEFORE the (potentially slow)
        // threads/stackTrace fan-out so the cleanup setBreakpoints is in
        // flight while the UI is being prepped.
        breakpointSync.consumeHitTransients(event.payload.hitBreakpointIds?.toList())

        if (isSynthetic) {
            log.debug("onStopped: consumed synthetic-pause stop (threadId=${event.payload.threadId})")
            return
        }

        val threadsResponse: ThreadsResponse = try {
            client.threads()
        } catch (throwable: Throwable) {
            log.warn("threads() request failed after stop", throwable)
            return
        }
        val stopThreadId = event.payload.threadId
        // The gate already cached stopThreadId; just resolve which one
        // to use for stack assembly below.
        val stacks: List<DapExecutionStack> = threadsResponse.threads.orEmpty()
            .map { thread ->
                val isActive = thread.id == stopThreadId
                val topFrame: DapStackFrame? = if (isActive) fetchTopFrame(thread.id) else null
                DapExecutionStack(
                    client = client,
                    sessionScope = sessionScope,
                    sourceMapper = sourceMapper,
                    sourceReferenceCache = sourceReferenceCache,
                    threadId = thread.id,
                    threadName = thread.name ?: "Thread ${thread.id}",
                    topFrame = topFrame,
                )
            }
        val active = stacks.firstOrNull { it.threadId == stopThreadId } ?: stacks.firstOrNull()
        val suspendContext: XSuspendContext = DapSuspendContext(stacks, active)
        ApplicationManager.getApplication().invokeLater {
            session.positionReached(suspendContext)
            disassemblyTab?.refresh()
        }
    }

    private suspend fun fetchTopFrame(threadId: Int): DapStackFrame? {
        return try {
            val response = client.stackTrace(StackTraceArguments().apply {
                this.threadId = threadId
                startFrame = 0
                levels = 1
            })
            response.stackFrames?.firstOrNull()?.let { frame ->
                frame.source?.sourceReference?.takeIf { it > 0 }?.let { ref ->
                    sourceReferenceCache.await(ref, frame.source.path, frame.source.name)
                }
                DapStackFrame(client, sessionScope, sourceMapper, frame)
            }
        } catch (throwable: Throwable) {
            log.warn("stackTrace failed for thread $threadId", throwable)
            null
        }
    }

    private fun onContinued(event: DapEvent.Continued) {
        // Out-of-band continuation (e.g. user typed `continue` in lldb's
        // own debug console, or the adapter resumed after configurationDone).
        // The IDE-side state needs to follow.
        // `XDebugSessionImpl.sessionResumed()` is idempotent: it short-
        // circuits if the platform already considers the session running,
        // so we can safely fire this for both adapter-initiated continues
        // and the no-op case after a user-initiated Resume.
        //
        // Also: capture the threadId so the synthetic-pause gate has a
        // valid target the very first time the user toggles a mid-session
        // breakpoint. Before this, the gate had to wait for a Stopped
        // event to learn a thread id — but Stopped never fires for a
        // program launched without stopOnEntry, so the gate silently
        // bypassed the pause-install-resume dance and mid-session
        // breakpoints never armed.
        syntheticPauseGate.recordContinued(event.payload.threadId)
        ApplicationManager.getApplication().invokeLater {
            session.sessionResumed()
        }
    }

    // ---- user-driven execution control -------------------------------------

    override fun resume(context: XSuspendContext?) {
        val threadId = activeThreadId(context) ?: return
        // The actual continue is async; mark the inferior running now so a
        // breakpoint flush racing with the continue takes the synthetic-pause
        // path. Worst case: the request fails and we self-correct on the next
        // stop event.
        syntheticPauseGate.markRunning()
        sessionScope.scope.launch {
            try {
                client.continueExecution(ContinueArguments().apply { this.threadId = threadId })
            } catch (throwable: Throwable) {
                log.warn("continue failed", throwable)
            }
        }
    }

    override fun startStepOver(context: XSuspendContext?) {
        val threadId = activeThreadId(context) ?: return
        syntheticPauseGate.markRunning()
        sessionScope.scope.launch {
            try {
                client.next(NextArguments().apply { this.threadId = threadId })
            } catch (throwable: Throwable) {
                log.warn("next failed", throwable)
            }
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        val threadId = activeThreadId(context) ?: return
        syntheticPauseGate.markRunning()
        sessionScope.scope.launch {
            try {
                client.stepIn(StepInArguments().apply { this.threadId = threadId })
            } catch (throwable: Throwable) {
                log.warn("stepIn failed", throwable)
            }
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        val threadId = activeThreadId(context) ?: return
        syntheticPauseGate.markRunning()
        sessionScope.scope.launch {
            try {
                client.stepOut(StepOutArguments().apply { this.threadId = threadId })
            } catch (throwable: Throwable) {
                log.warn("stepOut failed", throwable)
            }
        }
    }

    /**
     * Run to Cursor. The DAP-canonical implementation is "install a transient
     * one-shot breakpoint at the requested line, then continue execution."
     *
     * NB: the platform's default in [XDebugProcess] throws `AbstractMethodError`,
     * so we MUST override the (position, context) overload — overriding only
     * the single-arg variant won't help because the platform invokes the
     * two-arg one through a chain that bottoms out in the throwing default.
     *
     * We await the breakpoint install round-trip before issuing `continue`
     * so the adapter is guaranteed to have the breakpoint armed before
     * execution resumes; otherwise a fast inferior could blow past the
     * target line before the `setBreakpoints` request landed.
     */
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val threadId = activeThreadId(context) ?: lastKnownThreadId ?: run {
            log.warn("runToPosition: no thread id available, ignoring")
            return
        }
        val file = position.file
        val line = position.line
        sessionScope.scope.launch {
            try {
                breakpointSync.installTransientLineBreakpoint(file, line)
                syntheticPauseGate.markRunning()
                client.continueExecution(ContinueArguments().apply { this.threadId = threadId })
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("runToPosition failed", throwable)
            }
        }
    }

    override fun startPausing() {
        // DAP requires a threadId on `pause`, but we MUST NOT call
        // `threads()` to discover one while the program is running:
        // lldb-dap rejects metadata requests with `notStopped` once
        // execution has resumed. That rejection used to throw silently,
        // the pause request was never issued, and the user saw the second
        // Pause click do nothing — the bug we wrote
        // LldbDapPauseResumeIntegrationTest to reproduce.
        //
        // Instead, use the thread id captured from the most recent
        // `stopped` event. The first-ever pause has nothing cached yet, so
        // fall back to `threads()` — at session start the inferior is
        // still in its post-launch / configurationDone transition and
        // most adapters accept the request. We catch and log any failure
        // so a future regression here is visible.
        sessionScope.scope.launch {
            try {
                val threadId = lastKnownThreadId ?: discoverInitialThreadId() ?: run {
                    log.warn("startPausing(): no thread id available, cannot pause")
                    return@launch
                }
                client.pause(PauseArguments().apply { this.threadId = threadId })
            } catch (throwable: Throwable) {
                log.warn("pause request failed", throwable)
            }
        }
    }

    /**
     * Fallback for the first pause of a session, before we've observed any
     * stopped event. We accept failure here gracefully — if the adapter
     * refuses, the next IDE pause attempt will use whatever id arrived in
     * the interim.
     */
    private suspend fun discoverInitialThreadId(): Int? = try {
        // Cache the discovered thread id through the gate so a follow-up
        // mid-session setBreakpoints can use it without another roundtrip.
        client.threads().threads?.firstOrNull()?.id?.also {
            syntheticPauseGate.recordThreadStarted(it)
        }
    } catch (throwable: Throwable) {
        log.debug("threads() failed during initial pause discovery", throwable)
        null
    }

    override fun checkCanPerformCommands(): Boolean =
        processHandler.isStartNotified && !processHandler.isProcessTerminated

    override fun stop() {
        breakpointSync.shutdown()
        sessionScope.scope.launch {
            try {
                val caps = capabilities
                if (caps?.supportsTerminateRequest == true) {
                    client.terminate(TerminateArguments())
                } else {
                    client.disconnect(DisconnectArguments().apply { terminateDebuggee = true })
                }
            } catch (throwable: Throwable) {
                log.debug("disconnect/terminate failed (already gone?)", throwable)
            } finally {
                client.dispose()
                sessionScope.dispose()
            }
        }
    }

    private fun activeThreadId(context: XSuspendContext?): Int? {
        val ctx = context as? DapSuspendContext ?: return null
        val active = ctx.activeExecutionStack as? DapExecutionStack ?: return null
        return active.threadId
    }

    private fun mergedLaunchArgs(callerOverrides: Map<String, Any?>): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>(profile.defaultLaunchArgs())
        merged.putAll(callerOverrides)
        return merged
    }

    private fun buildInitializeArgs(): InitializeRequestArguments = InitializeRequestArguments().apply {
        clientID = clientId
        clientName = "IntelliJ DAP"
        adapterID = profile.id
        pathFormat = "path"
        linesStartAt1 = true
        columnsStartAt1 = true
        // Advertise reverse-request support only when the host actually installed a handler.
        supportsRunInTerminalRequest = runInTerminalAdvertised
        supportsStartDebuggingRequest = false
        supportsVariableType = true
        supportsVariablePaging = false
        supportsMemoryReferences = false
        supportsProgressReporting = false
        supportsInvalidatedEvent = false
        supportsMemoryEvent = false
    }

    companion object {
        const val DEFAULT_CLIENT_ID: String = "dap.intellij"
    }
}
