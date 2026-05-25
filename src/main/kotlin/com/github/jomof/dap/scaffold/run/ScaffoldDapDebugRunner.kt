package com.github.jomof.dap.scaffold.run

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapEvent
import com.github.jomof.dap.language.DapLanguageProfile
import com.github.jomof.dap.output.DapOutputAdapter
import com.github.jomof.dap.scaffold.breakpoints.CppLineBreakpointType
import com.github.jomof.dap.scaffold.breakpoints.GoLineBreakpointType
import com.github.jomof.dap.scaffold.breakpoints.RustLineBreakpointType
import com.github.jomof.dap.scaffold.language.CppLanguageProfile
import com.github.jomof.dap.scaffold.language.GoLanguageProfile
import com.github.jomof.dap.scaffold.language.RustLanguageProfile
import com.github.jomof.dap.session.DapDebugProcess
import com.github.jomof.dap.session.DapLaunchSpec
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/**
 * Wires a [ScaffoldDapRunConfiguration] into the IntelliJ run/debug pipeline.
 *
 * Two executors are supported:
 *
 *  - **Debug** — full DAP debugging via [DapDebugProcess]: breakpoints,
 *    threads, frames, stepping, evaluation, the works.
 *  - **Run** — `launch` is dispatched with `noDebug: true`, which tells the
 *    adapter to spawn the target without breakpoint or stepping
 *    instrumentation. The IDE only sees a console for the debuggee's
 *    stdout/stderr (routed via DAP `output` events); no XDebugger session
 *    is created.
 *
 * The shared first half of both paths is "spawn the adapter, get its
 * transport, hand it to a [DapClient]". From there the Debug path hands
 * off to [DapDebugProcess] and the Run path drives a minimal in-runner
 * lifecycle.
 */
class ScaffoldDapDebugRunner : AsyncProgramRunner<com.intellij.execution.configurations.RunnerSettings>() {

    private val log = logger<ScaffoldDapDebugRunner>()

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (profile !is ScaffoldDapRunConfiguration) return false
        return executorId == DefaultDebugExecutor.EXECUTOR_ID ||
            executorId == DefaultRunExecutor.EXECUTOR_ID
    }

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val promise = AsyncPromise<RunContentDescriptor?>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val descriptor = when (environment.executor.id) {
                    DefaultDebugExecutor.EXECUTOR_ID -> startDebugSession(environment, state)
                    DefaultRunExecutor.EXECUTOR_ID -> startRunSession(environment, state)
                    else -> error("Unsupported executor ${environment.executor.id}")
                }
                promise.setResult(descriptor)
            } catch (throwable: Throwable) {
                log.warn("Failed to start scaffold DAP session", throwable)
                promise.setError(throwable)
            }
        }
        return promise
    }

    private fun startDebugSession(environment: ExecutionEnvironment, state: RunProfileState): RunContentDescriptor {
        val commandLineState = state as ScaffoldDapCommandLineState
        val executionResult = commandLineState.execute(environment.executor, this)
        val processHandler = executionResult.processHandler as DapServerProcessHandler
        val console = executionResult.executionConsole as ConsoleView

        val client = connectAdapter(processHandler)

        val config = commandLineState.config()
        val launchSpec = buildLaunchSpec(config, noDebug = false)
        val profile = profileFor(config.profileId)
        val breakpointTypes = breakpointTypesFor(config.profileId)

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val process = DapDebugProcess(
                    session = session,
                    client = client,
                    profile = profile,
                    launchSpec = launchSpec,
                    processHandler = processHandler,
                    console = console,
                    breakpointTypeClasses = breakpointTypes,
                )
                process.start()
                return process
            }
        }

        val sessionResult = XDebuggerManager.getInstance(environment.project)
            .startSession(environment, starter)
        return sessionResult.runContentDescriptor
    }

    /**
     * Run-mode: no XDebugger, no breakpoints, just `launch(noDebug=true)`.
     *
     * The DAP lifecycle is driven by a small coroutine scope owned by this
     * runner; it terminates the process handler when the adapter reports
     * `terminated` / `exited`, and is cancelled when the IDE asks the
     * handler to stop (so user-initiated Stop also tears the client down).
     */
    private fun startRunSession(environment: ExecutionEnvironment, state: RunProfileState): RunContentDescriptor {
        val commandLineState = state as ScaffoldDapCommandLineState
        val executionResult = commandLineState.execute(environment.executor, this)
        val processHandler = executionResult.processHandler as DapServerProcessHandler
        val console = executionResult.executionConsole as ConsoleView

        val client = connectAdapter(processHandler)
        val config = commandLineState.config()
        val profile = profileFor(config.profileId)
        val launchSpec = buildLaunchSpec(config, noDebug = true)
        // Run mode has no debug-console tab, so suppress lldb-dap's banner
        // ("To get started with the debug console …", "Attached to process N",
        // "Process N exited with status = 0.") and keep only the program's
        // real stdout/stderr (plus any `important` adapter notices).
        val outputAdapter = DapOutputAdapter(console, skipCategories = DapOutputAdapter.RUN_MODE_SKIP)

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("dap-run-${environment.runProfile.name}"),
        )

        scope.launch {
            try {
                client.events.collect { event ->
                    when (event) {
                        is DapEvent.Output -> outputAdapter.accept(event.payload)
                        is DapEvent.Terminated, is DapEvent.Exited -> {
                            ApplicationManager.getApplication().invokeLater {
                                if (!processHandler.isProcessTerminated) processHandler.destroyProcess()
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("DAP run-mode event pump failed", throwable)
            }
        }

        scope.launch {
            try {
                val capabilities = client.initialize(buildInitializeArgs(profile))
                // Issue `launch` but do NOT await its response yet — lldb-dap (and
                // most spec-conformant adapters) only settle the launch response
                // AFTER they receive configurationDone, so a naive `.await()` here
                // deadlocks the whole handshake.
                val launchAck = client.launch(launchSpec.args)
                // The adapter fires `initialized` so we send `configurationDone` in lockstep.
                // Even with noDebug=true lldb-dap waits for configurationDone before resuming
                // the debuggee. Use the one-shot signal rather than the event flow because
                // `initialized` often arrives before we'd otherwise subscribe.
                client.awaitInitialized()
                if (capabilities.supportsConfigurationDoneRequest) {
                    client.configurationDone()
                }
                launchAck.await()
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("DAP run-mode startup failed", throwable)
                ApplicationManager.getApplication().invokeLater {
                    console.print(
                        "DAP run-mode startup failed: ${throwable.message}\n",
                        ConsoleViewContentType.ERROR_OUTPUT,
                    )
                    if (!processHandler.isProcessTerminated) processHandler.destroyProcess()
                }
            }
        }

        processHandler.addProcessListener(object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                scope.cancel()
                client.dispose()
            }
        })

        return RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)
    }

    private fun connectAdapter(processHandler: DapServerProcessHandler): DapClient {
        val transport = try {
            processHandler.transportBlueprint.connect(processHandler.underlyingProcess())
        } catch (throwable: Throwable) {
            runCatching { processHandler.destroyProcess() }
            throw throwable
        }
        return DapClient(transport).also { it.connect() }
    }

    private fun buildLaunchSpec(config: ScaffoldDapRunConfiguration, noDebug: Boolean): DapLaunchSpec {
        val args = LinkedHashMap<String, Any?>()
        args["program"] = config.program
        if (config.programArgs.isNotBlank()) {
            args["args"] = splitArgs(config.programArgs)
        }
        if (config.workingDir.isNotBlank()) {
            args["cwd"] = config.workingDir
        }
        if (config.environment.isNotEmpty()) {
            args["env"] = config.environment
        }
        if (noDebug) {
            // Per DAP spec: when `noDebug` is true the adapter spawns the target
            // without breakpoint or stepping instrumentation; lldb-dap honours this
            // by directly exec()-ing the program and only forwarding output events.
            args["noDebug"] = true
        }
        if (config.launchJsonOverrides.isNotBlank()) {
            parseLaunchOverrides(config.launchJsonOverrides).forEach { (k, v) -> args[k] = v }
        }
        return DapLaunchSpec.Launch(args)
    }

    private fun buildInitializeArgs(profile: DapLanguageProfile): InitializeRequestArguments =
        InitializeRequestArguments().apply {
            clientID = "dap.intellij.run"
            clientName = "IntelliJ DAP (Run)"
            adapterID = profile.id
            pathFormat = "path"
            linesStartAt1 = true
            columnsStartAt1 = true
            supportsRunInTerminalRequest = false
            supportsStartDebuggingRequest = false
            supportsVariableType = false
            supportsVariablePaging = false
            supportsMemoryReferences = false
            supportsProgressReporting = false
            supportsInvalidatedEvent = false
            supportsMemoryEvent = false
        }

    /**
     * Naive whitespace split for Phase 1. Quote-aware tokenisation arrives
     * with the conditional-breakpoint work in Phase 2 when we anyway need a
     * JSON parser for launch overrides.
     */
    private fun splitArgs(line: String): List<String> =
        line.split(Regex("\\s+")).filter { it.isNotBlank() }

    /**
     * Phase 1 only accepts simple `KEY=VALUE` lines in the launch-JSON box.
     * Phase 2 swaps this for a proper Gson-backed JSON parser.
     */
    private fun parseLaunchOverrides(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }

    private fun profileFor(profileId: String): DapLanguageProfile = when (profileId) {
        RustLanguageProfile.id -> RustLanguageProfile
        GoLanguageProfile.id -> GoLanguageProfile
        else -> CppLanguageProfile
    }

    private fun breakpointTypesFor(profileId: String): List<Class<out XLineBreakpointType<*>>> = when (profileId) {
        RustLanguageProfile.id -> listOf(RustLineBreakpointType::class.java)
        GoLanguageProfile.id -> listOf(GoLineBreakpointType::class.java)
        else -> listOf(CppLineBreakpointType::class.java)
    }

    companion object {
        const val RUNNER_ID: String = "ScaffoldDapDebugRunner"
    }
}
