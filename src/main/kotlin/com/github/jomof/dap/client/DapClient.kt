package com.github.jomof.dap.client

import com.github.jomof.dap.reverse.DapRunInTerminalHandler
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.eclipse.lsp4j.debug.BreakpointEventArguments
import org.eclipse.lsp4j.debug.CapabilitiesEventArguments
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.ContinuedEventArguments
import org.eclipse.lsp4j.debug.DisassembleArguments
import org.eclipse.lsp4j.debug.DisassembleResponse
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.InvalidatedEventArguments
import org.eclipse.lsp4j.debug.LoadedSourceEventArguments
import org.eclipse.lsp4j.debug.MemoryEventArguments
import org.eclipse.lsp4j.debug.ModuleEventArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.ProcessEventArguments
import org.eclipse.lsp4j.debug.RestartFrameArguments
import org.eclipse.lsp4j.debug.RunInTerminalRequestArguments
import org.eclipse.lsp4j.debug.RunInTerminalResponse
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.SetVariableResponse
import org.eclipse.lsp4j.debug.SourceArguments
import org.eclipse.lsp4j.debug.SourceResponse
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepInTargetsArguments
import org.eclipse.lsp4j.debug.StepInTargetsResponse
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminateArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.ThreadEventArguments
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Future as JFuture

/**
 * Thin coroutine-friendly facade over the Eclipse LSP4J DAP client bindings.
 * Outgoing requests are exposed as `suspend` functions (each awaits the server
 * proxy's [java.util.concurrent.CompletableFuture]); adapter-to-client events
 * arrive through the [events] shared flow.
 *
 * One [DapClient] per debug session. The instance is not reusable after [dispose]
 * — create a new one to start another session.
 *
 * Threading:
 *  - LSP4J's listener thread invokes the [IDebugProtocolClient] callbacks; we
 *    only push the event into a [MutableSharedFlow] there, so the listener
 *    never blocks on user code.
 *  - All `suspend` request methods inherit the caller's dispatcher; the wire
 *    work happens on the LSP4J executor pool created in the primary constructor.
 */
class DapClient(
    private val transport: DapTransport,
) : IDebugProtocolClient {

    private val log = logger<DapClient>()

    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dap-client").apply { isDaemon = true }
    }

    /**
     * Buffer events so that bursty traffic (e.g. a stream of `output` events while
     * the program is running) never blocks the LSP4J listener thread. 256 is
     * generous; if the consumer falls further behind than that, the dropped
     * event is logged and the application continues.
     */
    private val eventFlow = MutableSharedFlow<DapEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<DapEvent> = eventFlow.asSharedFlow()

    /**
     * One-shot signal that completes the first time the adapter fires the
     * DAP `initialized` event. Callers awaiting this don't have to race the
     * event flow's `replay = 0` semantics — late awaiters still observe the
     * signal as soon as they suspend, because [CompletableDeferred] retains
     * its completion state.
     */
    private val initializedSignal = CompletableDeferred<Unit>()

    /** Suspends until the adapter has sent the DAP `initialized` event. */
    suspend fun awaitInitialized() {
        initializedSignal.await()
    }

    @Volatile private var server: IDebugProtocolServer? = null

    @Volatile private var listenerFuture: JFuture<Void>? = null
    private val disposed = AtomicBoolean(false)

    /** Background scope for reverse requests so they don't block the JSON-RPC listener thread. */
    private val reverseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Pluggable handler for the `runInTerminal` reverse request; null means "tell the adapter we can't". */
    private val runInTerminalHandler = AtomicReference<DapRunInTerminalHandler?>(null)

    /** Installs the handler used to service `runInTerminal` reverse requests. */
    fun setRunInTerminalHandler(handler: DapRunInTerminalHandler?) {
        runInTerminalHandler.set(handler)
    }

    /**
     * Wires the JSON-RPC machinery over [transport] and starts the listener.
     * Returns the server proxy for callers that want to issue protocol calls
     * directly; most callers should use the typed `suspend` helpers below.
     *
     * Throws [IllegalStateException] if called more than once or after [dispose].
     */
    fun connect(): IDebugProtocolServer {
        check(server == null) { "DapClient has already been connected" }
        check(!disposed.get()) { "DapClient has been disposed" }
        val launcher: Launcher<IDebugProtocolServer> = DSPLauncher.createClientLauncher(
            this,
            transport.input,
            transport.output,
            executor,
            ::sanitiseIncoming,
        )
        listenerFuture = launcher.startListening()
        return launcher.remoteProxy.also { server = it }
    }

    /**
     * Strips non-spec payloads off DAP notifications before LSP4J's
     * reflection-based dispatch sees them, so adapter quirks don't cause
     * standard events to be silently discarded.
     *
     * Concretely: lldb-dap emits the `initialized` event with a `body`
     * containing an LLDB statistics blob. The DAP spec defines `initialized`
     * as a parameterless event, so LSP4J's `GenericEndpoint` matches our
     * `initialized()` handler, observes that the incoming params are
     * non-null, logs a warning, and drops the event. That breaks the
     * launch handshake because we never observe the event we're waiting on.
     */
    private fun sanitiseIncoming(downstream: MessageConsumer): MessageConsumer = MessageConsumer { message ->
        if (message is NotificationMessage && message.method in PARAMLESS_EVENTS && message.params != null) {
            message.params = null
        }
        downstream.consume(message)
    }

    private fun server(): IDebugProtocolServer =
        server ?: error("DapClient is not connected — call connect() first")

    // ---- typed outgoing requests --------------------------------------------

    suspend fun initialize(args: InitializeRequestArguments): DapCapabilities =
        DapCapabilities(server().initialize(args).await())

    /**
     * Issues the `launch` request and returns the pending response future.
     *
     * Per the DAP spec the adapter is permitted (and many adapters, including
     * lldb-dap, do) defer the `launch` response until **after** the client has
     * sent `configurationDone`. Callers therefore MUST NOT block on this
     * future before completing the handshake, otherwise the session deadlocks.
     *
     * Typical sequence:
     * ```
     * val launchAck = client.launch(args)
     * client.awaitInitialized()
     * // ... setBreakpoints, setExceptionBreakpoints ...
     * client.configurationDone()
     * launchAck.await()
     * ```
     */
    fun launch(args: Map<String, Any?>): CompletableFuture<Void> =
        server().launch(args)

    /** Twin of [launch] for attach-style sessions; same ordering rules apply. */
    fun attach(args: Map<String, Any?>): CompletableFuture<Void> =
        server().attach(args)

    suspend fun configurationDone() {
        server().configurationDone(ConfigurationDoneArguments()).await()
    }

    suspend fun setBreakpoints(args: SetBreakpointsArguments): SetBreakpointsResponse =
        server().setBreakpoints(args).await()

    suspend fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): SetExceptionBreakpointsResponse =
        server().setExceptionBreakpoints(args).await()

    suspend fun threads(): ThreadsResponse = server().threads().await()

    suspend fun stackTrace(args: StackTraceArguments): StackTraceResponse =
        server().stackTrace(args).await()

    suspend fun scopes(args: ScopesArguments): ScopesResponse =
        server().scopes(args).await()

    suspend fun variables(args: VariablesArguments): VariablesResponse =
        server().variables(args).await()

    suspend fun evaluate(args: EvaluateArguments): EvaluateResponse =
        server().evaluate(args).await()

    suspend fun continueExecution(args: ContinueArguments): ContinueResponse =
        server().continue_(args).await()

    suspend fun pause(args: PauseArguments) {
        server().pause(args).await()
    }

    suspend fun next(args: NextArguments) {
        server().next(args).await()
    }

    suspend fun stepIn(args: StepInArguments) {
        server().stepIn(args).await()
    }

    suspend fun stepOut(args: StepOutArguments) {
        server().stepOut(args).await()
    }

    suspend fun stepInTargets(args: StepInTargetsArguments): StepInTargetsResponse =
        server().stepInTargets(args).await()

    suspend fun setVariable(args: SetVariableArguments): SetVariableResponse =
        server().setVariable(args).await()

    suspend fun source(args: SourceArguments): SourceResponse =
        server().source(args).await()

    suspend fun disassemble(args: DisassembleArguments): DisassembleResponse =
        server().disassemble(args).await()

    suspend fun restartFrame(args: RestartFrameArguments) {
        server().restartFrame(args).await()
    }

    suspend fun disconnect(args: DisconnectArguments) {
        server().disconnect(args).await()
    }

    suspend fun terminate(args: TerminateArguments) {
        server().terminate(args).await()
    }

    /**
     * Cancels the listener, closes the transport, and tears down the executor.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        // Unblock any latent awaitInitialized() callers so they don't hang forever.
        initializedSignal.completeExceptionally(IllegalStateException("DapClient disposed"))
        runCatching { reverseScope.cancel() }
        runCatching { listenerFuture?.cancel(true) }
        runCatching { transport.close() }
        runCatching { executor.shutdownNow() }
    }

    // ---- reverse requests from the adapter ----------------------------------

    override fun runInTerminal(args: RunInTerminalRequestArguments): CompletableFuture<RunInTerminalResponse> {
        val handler = runInTerminalHandler.get()
            ?: return CompletableFuture<RunInTerminalResponse>().apply {
                completeExceptionally(UnsupportedOperationException("No runInTerminal handler installed"))
            }
        return reverseScope.future { handler.handle(args) }
    }

    // ---- IDebugProtocolClient (events from the adapter) ---------------------

    override fun initialized() {
        initializedSignal.complete(Unit)
        fire(DapEvent.Initialized)
    }
    override fun stopped(args: StoppedEventArguments) = fire(DapEvent.Stopped(args))
    override fun continued(args: ContinuedEventArguments) = fire(DapEvent.Continued(args))
    override fun thread(args: ThreadEventArguments) = fire(DapEvent.Thread(args))
    override fun output(args: OutputEventArguments) = fire(DapEvent.Output(args))
    override fun breakpoint(args: BreakpointEventArguments) = fire(DapEvent.Breakpoint(args))
    override fun terminated(args: TerminatedEventArguments?) = fire(DapEvent.Terminated(args))
    override fun exited(args: ExitedEventArguments) = fire(DapEvent.Exited(args))
    override fun module(args: ModuleEventArguments) = fire(DapEvent.Module(args))
    override fun loadedSource(args: LoadedSourceEventArguments) = fire(DapEvent.LoadedSource(args))
    override fun capabilities(args: CapabilitiesEventArguments) = fire(DapEvent.Capabilities(args))
    override fun process(args: ProcessEventArguments) = fire(DapEvent.Process(args))
    override fun memory(args: MemoryEventArguments) = fire(DapEvent.Memory(args))
    override fun invalidated(args: InvalidatedEventArguments) = fire(DapEvent.Invalidated(args))

    private fun fire(event: DapEvent) {
        if (!eventFlow.tryEmit(event)) {
            // Buffer overflow with DROP_OLDEST should never return false, but log defensively.
            log.warn("DAP event dropped: ${event::class.simpleName}")
        }
    }

    private companion object {
        /**
         * DAP events whose spec definition takes no params. If an adapter
         * (looking at you, lldb-dap with `$__lldb_statistics`) attaches a
         * payload anyway, LSP4J would drop the event; instead we discard the
         * payload and let the standard handler fire.
         */
        val PARAMLESS_EVENTS: Set<String> = setOf("initialized")
    }
}
