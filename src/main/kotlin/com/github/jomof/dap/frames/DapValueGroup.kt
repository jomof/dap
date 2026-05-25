package com.github.jomof.dap.frames

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapErrors
import com.github.jomof.dap.session.DapSessionScope
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.Scope
import org.eclipse.lsp4j.debug.VariablesArguments

/**
 * A DAP "scope" (locals, arguments, registers, …) rendered as a top-level
 * group node under a stack frame in the Variables tree.
 *
 * Children are loaded lazily on EDT-friendly background coroutines, the same
 * way [DapValue] handles its children.
 */
class DapValueGroup(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
    private val scope: Scope,
) : XValueGroup(scope.name ?: "<scope>") {

    private val log = logger<DapValueGroup>()

    /** "Expensive" scopes (e.g. registers) collapse by default per DAP guidance. */
    override fun isAutoExpand(): Boolean = !scope.isExpensive

    override fun computeChildren(node: XCompositeNode) {
        val ref = scope.variablesReference
        if (ref <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        sessionScope.scope.launch {
            try {
                val args = VariablesArguments().apply { variablesReference = ref }
                val response = client.variables(args)
                val children = XValueChildrenList(response.variables?.size ?: 0)
                response.variables?.forEach { variable ->
                    children.add(
                        DapValue(
                            client = client,
                            sessionScope = sessionScope,
                            name = variable.name ?: "<unnamed>",
                            value = variable.value.orEmpty(),
                            type = variable.type,
                            variablesReference = variable.variablesReference ?: 0,
                            parentVariablesReference = ref,
                            isMutable = true,
                        )
                    )
                }
                ApplicationManager.getApplication().invokeLater {
                    node.addChildren(children, true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("Failed to fetch DAP variables for scope '${name}'", throwable)
                val userMessage = DapErrors.toUserMessage(throwable)
                ApplicationManager.getApplication().invokeLater {
                    node.setErrorMessage(userMessage)
                }
            }
        }
    }
}
