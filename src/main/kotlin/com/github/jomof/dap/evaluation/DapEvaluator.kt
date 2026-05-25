package com.github.jomof.dap.evaluation

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapErrors
import com.github.jomof.dap.frames.DapValue
import com.github.jomof.dap.session.DapSessionScope
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateArgumentsContext

/**
 * Per-frame expression evaluator. Invoked by IntelliJ for watches, hover
 * popups and the Evaluate dialog. Each [com.github.jomof.dap.frames.DapStackFrame]
 * has its own instance because DAP's `evaluate` request is frame-scoped (the
 * adapter resolves names against the frame's lexical context).
 *
 * The result is wrapped in a [DapValue] so the Variables tree can recurse
 * into structures the adapter returns.
 */
class DapEvaluator(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
    private val frameId: Int,
) : XDebuggerEvaluator() {

    private val log = logger<DapEvaluator>()

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        evaluate(expression, callback, EvaluateArgumentsContext.WATCH)
    }

    private fun evaluate(expression: String, callback: XEvaluationCallback, context: String) {
        sessionScope.scope.launch {
            try {
                val args = EvaluateArguments().apply {
                    this.expression = expression
                    this.frameId = this@DapEvaluator.frameId
                    this.context = context
                }
                val response = client.evaluate(args)
                val value = DapValue(
                    client = client,
                    sessionScope = sessionScope,
                    name = expression,
                    value = response.result.orEmpty(),
                    type = response.type,
                    variablesReference = response.variablesReference,
                    // Evaluator results aren't slot-addressable so we can't modify them.
                    parentVariablesReference = 0,
                    isMutable = false,
                )
                ApplicationManager.getApplication().invokeLater { callback.evaluated(value) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.debug("evaluate('$expression') failed", throwable)
                val userMessage = DapErrors.toUserMessage(throwable)
                ApplicationManager.getApplication().invokeLater {
                    callback.errorOccurred(userMessage)
                }
            }
        }
    }
}
