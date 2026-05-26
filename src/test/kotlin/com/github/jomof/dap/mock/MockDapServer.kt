package com.github.jomof.dap.mock

import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateResponse
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.Scope
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason
import org.eclipse.lsp4j.debug.TerminateArguments
import org.eclipse.lsp4j.debug.Thread
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Hand-rolled in-process DAP server used by the unit tests. Doesn't model any
 * particular real adapter — implements the minimum slice the production code
 * exercises, with hooks for tests to script specific scenarios:
 *
 *  - `initialize`/`launch`/`configurationDone` succeed and record the
 *    arguments they were called with.
 *  - `setBreakpoints` echoes back each requested breakpoint as `verified`.
 *  - `threads`/`stackTrace`/`scopes`/`variables` return canned data
 *    settable via [scriptStop].
 *  - `continue`, `next`, `stepIn`, `stepOut`, `pause`, `disconnect`,
 *    `terminate` all complete instantly.
 */
class MockDapServer : IDebugProtocolServer {

    private val remote = AtomicReference<IDebugProtocolClient?>()

    val initializeArgs = AtomicReference<InitializeRequestArguments?>()
    val launchArgs = AtomicReference<Map<String, Any?>?>()
    val attachArgs = AtomicReference<Map<String, Any?>?>()
    val configurationDoneCalls = AtomicInteger(0)
    val disconnectCalls = AtomicInteger(0)
    val terminateCalls = AtomicInteger(0)
    val continueCalls = AtomicInteger(0)
    val pauseCalls = AtomicInteger(0)
    val nextCalls = AtomicInteger(0)
    val stepInCalls = AtomicInteger(0)
    val stepOutCalls = AtomicInteger(0)
    val setBreakpointsCalls = AtomicInteger(0)
    val setExceptionBreakpointsCalls = AtomicInteger(0)
    val lastSetBreakpoints = AtomicReference<SetBreakpointsArguments?>()
    val lastSetExceptionBreakpoints = AtomicReference<SetExceptionBreakpointsArguments?>()
    val callLog = CopyOnWriteArrayList<String>()

    /** Scripted threads, frames, scopes, variables. Mutated by [scriptStop]. */
    private var scriptedThreads: List<Thread> = listOf(Thread().apply { id = 1; name = "main" })
    private var scriptedFrames: List<StackFrame> = emptyList()
    private var scriptedScopes: List<Scope> = emptyList()
    private val scriptedVariables: MutableMap<Int, List<Variable>> = mutableMapOf()

    fun bindRemoteProxy(client: IDebugProtocolClient) {
        remote.set(client)
    }

    /** Script the response to the next `threads`/`stackTrace`/`scopes`/`variables` round-trip. */
    fun scriptStop(
        threads: List<Thread>,
        frames: List<StackFrame>,
        scopes: List<Scope>,
        variablesByReference: Map<Int, List<Variable>>,
    ) {
        scriptedThreads = threads
        scriptedFrames = frames
        scriptedScopes = scopes
        scriptedVariables.clear()
        scriptedVariables.putAll(variablesByReference)
    }

    /** Simulate an adapter-side `stopped` event being raised. */
    fun fireStopped(threadId: Int = 1, reason: String = StoppedEventArgumentsReason.BREAKPOINT) {
        val args = StoppedEventArguments().apply {
            this.threadId = threadId
            this.reason = reason
            allThreadsStopped = true
        }
        remote.get()?.stopped(args)
    }

    /** Simulate the `initialized` notification the adapter emits after `launch`. */
    fun fireInitialized() {
        remote.get()?.initialized()
    }

    // ---- IDebugProtocolServer ----------------------------------------------

    override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> {
        initializeArgs.set(args)
        return CompletableFuture.completedFuture(defaultCapabilities())
    }

    override fun launch(args: Map<String, Any>): CompletableFuture<Void> {
        @Suppress("UNCHECKED_CAST")
        launchArgs.set(args as Map<String, Any?>)
        return CompletableFuture.completedFuture(null)
    }

    override fun attach(args: Map<String, Any>): CompletableFuture<Void> {
        @Suppress("UNCHECKED_CAST")
        attachArgs.set(args as Map<String, Any?>)
        return CompletableFuture.completedFuture(null)
    }

    override fun configurationDone(args: ConfigurationDoneArguments): CompletableFuture<Void> {
        configurationDoneCalls.incrementAndGet()
        callLog += "configurationDone"
        return CompletableFuture.completedFuture(null)
    }

    override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> {
        setBreakpointsCalls.incrementAndGet()
        lastSetBreakpoints.set(args)
        callLog += "setBreakpoints"
        val response = SetBreakpointsResponse().apply {
            breakpoints = (args.breakpoints ?: emptyArray()).mapIndexed { index, src ->
                org.eclipse.lsp4j.debug.Breakpoint().apply {
                    id = index + 1
                    isVerified = true
                    line = src.line
                }
            }.toTypedArray()
        }
        return CompletableFuture.completedFuture(response)
    }

    override fun setExceptionBreakpoints(
        args: SetExceptionBreakpointsArguments,
    ): CompletableFuture<SetExceptionBreakpointsResponse> {
        setExceptionBreakpointsCalls.incrementAndGet()
        lastSetExceptionBreakpoints.set(args)
        callLog += "setExceptionBreakpoints"
        return CompletableFuture.completedFuture(SetExceptionBreakpointsResponse())
    }

    override fun threads(): CompletableFuture<ThreadsResponse> {
        val response = ThreadsResponse().apply { threads = scriptedThreads.toTypedArray() }
        return CompletableFuture.completedFuture(response)
    }

    override fun stackTrace(args: StackTraceArguments): CompletableFuture<StackTraceResponse> {
        val limit = (args.levels ?: 0).takeIf { it > 0 } ?: scriptedFrames.size
        val response = StackTraceResponse().apply {
            stackFrames = scriptedFrames.take(limit).toTypedArray()
            totalFrames = scriptedFrames.size
        }
        return CompletableFuture.completedFuture(response)
    }

    override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> {
        val response = ScopesResponse().apply { scopes = scriptedScopes.toTypedArray() }
        return CompletableFuture.completedFuture(response)
    }

    override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> {
        val list = scriptedVariables[args.variablesReference] ?: emptyList()
        val response = VariablesResponse().apply { variables = list.toTypedArray() }
        return CompletableFuture.completedFuture(response)
    }

    override fun evaluate(args: EvaluateArguments): CompletableFuture<EvaluateResponse> {
        val response = EvaluateResponse().apply {
            result = "mock<${args.expression}>"
            variablesReference = 0
        }
        return CompletableFuture.completedFuture(response)
    }

    override fun continue_(args: ContinueArguments): CompletableFuture<ContinueResponse> {
        continueCalls.incrementAndGet()
        val response = ContinueResponse().apply { allThreadsContinued = true }
        return CompletableFuture.completedFuture(response)
    }

    override fun next(args: NextArguments): CompletableFuture<Void> {
        nextCalls.incrementAndGet()
        return CompletableFuture.completedFuture(null)
    }

    override fun stepIn(args: StepInArguments): CompletableFuture<Void> {
        stepInCalls.incrementAndGet()
        return CompletableFuture.completedFuture(null)
    }

    override fun stepOut(args: StepOutArguments): CompletableFuture<Void> {
        stepOutCalls.incrementAndGet()
        return CompletableFuture.completedFuture(null)
    }

    override fun pause(args: PauseArguments): CompletableFuture<Void> {
        pauseCalls.incrementAndGet()
        return CompletableFuture.completedFuture(null)
    }

    override fun disconnect(args: DisconnectArguments): CompletableFuture<Void> {
        disconnectCalls.incrementAndGet()
        return CompletableFuture.completedFuture(null)
    }

    override fun terminate(args: TerminateArguments): CompletableFuture<Void> {
        terminateCalls.incrementAndGet()
        return CompletableFuture.completedFuture(null)
    }

    private fun defaultCapabilities(): Capabilities = Capabilities().apply {
        supportsConfigurationDoneRequest = true
        supportsSetVariable = true
        supportsEvaluateForHovers = true
        supportsConditionalBreakpoints = true
        supportsLogPoints = true
        supportsHitConditionalBreakpoints = true
        supportsTerminateRequest = true
        exceptionBreakpointFilters = arrayOf(
            exceptionFilter("cpp_throw"),
            exceptionFilter("rust_panic"),
            exceptionFilter("panic"),
            exceptionFilter("fatalError"),
        )
    }

    private fun exceptionFilter(filterId: String): ExceptionBreakpointsFilter =
        ExceptionBreakpointsFilter().apply {
            filter = filterId
            label = filterId
        }

    companion object {
        fun frame(id: Int, name: String, source: Source?, line: Int): StackFrame =
            StackFrame().apply {
                this.id = id
                this.name = name
                this.source = source
                this.line = line
                this.column = 1
            }

        fun source(path: String, name: String = path.substringAfterLast('/')): Source =
            Source().apply {
                this.path = path
                this.name = name
            }

        fun scope(name: String, variablesReference: Int, expensive: Boolean = false): Scope =
            Scope().apply {
                this.name = name
                this.variablesReference = variablesReference
                this.isExpensive = expensive
            }

        fun variable(name: String, value: String, type: String? = null, variablesReference: Int = 0): Variable =
            Variable().apply {
                this.name = name
                this.value = value
                this.type = type
                this.variablesReference = variablesReference
            }
    }
}
