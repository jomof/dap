package com.github.jomof.dap.stepping

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.frames.DapExecutionStack
import com.github.jomof.dap.frames.DapStackFrame
import com.github.jomof.dap.session.DapSessionScope
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler
import com.intellij.xdebugger.stepping.XSmartStepIntoVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepInTarget
import org.eclipse.lsp4j.debug.StepInTargetsArguments
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/**
 * Smart-step-into for DAP. Pops a chooser of every callable on the current
 * source line (resolved via `stepInTargets`) and forwards the user's pick to
 * `stepIn` with the corresponding `targetId`.
 *
 * If the adapter doesn't support `stepInTargets`, the async fetch errors out
 * and IntelliJ silently falls back to plain step-into.
 */
class DapSmartStepIntoHandler(
    private val session: XDebugSession,
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
) : XSmartStepIntoHandler<DapSmartStepIntoVariant>() {

    private val log = logger<DapSmartStepIntoHandler>()

    override fun computeSmartStepVariants(position: XSourcePosition): List<DapSmartStepIntoVariant> {
        // Sync path is reserved for legacy callers; the async one below is what IntelliJ uses today.
        return emptyList()
    }

    override fun computeSmartStepVariantsAsync(position: XSourcePosition): Promise<List<DapSmartStepIntoVariant>> {
        val promise = AsyncPromise<List<DapSmartStepIntoVariant>>()
        val frameId = currentFrameId()
        if (frameId == null) {
            promise.setError("No current DAP frame")
            return promise
        }
        sessionScope.scope.launch {
            try {
                val response = client.stepInTargets(StepInTargetsArguments().apply { this.frameId = frameId })
                val variants: List<DapSmartStepIntoVariant> = response.targets
                    ?.map { DapSmartStepIntoVariant(it) }
                    .orEmpty()
                promise.setResult(variants)
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.debug("stepInTargets failed; falling back to plain step-into", throwable)
                promise.setError(throwable)
            }
        }
        return promise
    }

    override fun startStepInto(variant: DapSmartStepIntoVariant) {
        dispatchStepInto(variant, currentThreadId())
    }

    override fun startStepInto(variant: DapSmartStepIntoVariant, context: XSuspendContext?) {
        val threadId = (context as? com.github.jomof.dap.frames.DapSuspendContext)
            ?.activeExecutionStack
            ?.let { it as? DapExecutionStack }
            ?.threadId
            ?: currentThreadId()
        dispatchStepInto(variant, threadId)
    }

    private fun dispatchStepInto(variant: DapSmartStepIntoVariant, threadId: Int?) {
        if (threadId == null) return
        sessionScope.scope.launch {
            try {
                client.stepIn(StepInArguments().apply {
                    this.threadId = threadId
                    this.targetId = variant.targetId
                })
            } catch (throwable: Throwable) {
                log.warn("stepIn(targetId=${variant.targetId}) failed", throwable)
            }
        }
    }

    private fun currentFrameId(): Int? = (session.currentStackFrame as? DapStackFrame)?.dapFrameId

    private fun currentThreadId(): Int? {
        val context = session.suspendContext ?: return null
        val stack = context.activeExecutionStack as? DapExecutionStack ?: return null
        return stack.threadId
    }
}

class DapSmartStepIntoVariant(private val target: StepInTarget) : XSmartStepIntoVariant() {
    val targetId: Int = target.id
    override fun getText(): String = target.label ?: "<target ${target.id}>"
}
