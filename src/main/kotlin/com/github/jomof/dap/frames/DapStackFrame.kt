package com.github.jomof.dap.frames

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapErrors
import com.github.jomof.dap.evaluation.DapEvaluator
import com.github.jomof.dap.session.DapSessionScope
import com.github.jomof.dap.session.DapSourceMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.StackFrame

/**
 * One stack frame in a suspended DAP thread, mapped onto IntelliJ's
 * [XStackFrame]. The frame's `id` (`dapFrameId`) is the DAP-issued opaque
 * handle reused for [scopes][org.eclipse.lsp4j.debug.services.IDebugProtocolServer.scopes],
 * `evaluate`, `setVariable`, `restartFrame`, …
 *
 * Source position is resolved eagerly so the gutter highlight appears the
 * moment IntelliJ paints the frame; scopes (locals/arguments/...) are
 * fetched lazily when the user expands the frame in the Variables view.
 */
class DapStackFrame(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
    sourceMapper: DapSourceMapper,
    val raw: StackFrame,
) : XStackFrame() {

    private val log = logger<DapStackFrame>()

    val dapFrameId: Int = raw.id

    private val sourcePosition: XSourcePosition? = sourceMapper.toSourcePosition(raw.source, raw.line)
    private val evaluator: DapEvaluator = DapEvaluator(client, sessionScope, dapFrameId)

    override fun getSourcePosition(): XSourcePosition? = sourcePosition

    override fun getEqualityObject(): Any = dapFrameId

    override fun getEvaluator(): XDebuggerEvaluator = evaluator

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(raw.name ?: "<frame ${'$'}dapFrameId>", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val source = raw.source?.name
        if (source != null) {
            component.append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            component.append("$source:${raw.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    override fun computeChildren(node: XCompositeNode) {
        sessionScope.scope.launch {
            try {
                val args = ScopesArguments().apply { frameId = dapFrameId }
                val response = client.scopes(args)
                val children = XValueChildrenList(response.scopes?.size ?: 0)
                response.scopes?.forEach { scope ->
                    children.addTopGroup(DapValueGroup(client, sessionScope, scope))
                }
                ApplicationManager.getApplication().invokeLater {
                    node.addChildren(children, true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("Failed to fetch scopes for frame $dapFrameId", throwable)
                val userMessage = DapErrors.toUserMessage(throwable)
                ApplicationManager.getApplication().invokeLater {
                    node.setErrorMessage(userMessage)
                }
            }
        }
    }
}
