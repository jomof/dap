package com.github.jomof.dap.frames

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapErrors
import com.github.jomof.dap.session.DapSessionScope
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.SetVariableArguments
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments

/**
 * Represents one variable (or sub-variable) in the IntelliJ Variables tree.
 *
 * DAP exposes children via the integer `variablesReference` handle. When that
 * value is positive the variable has a child scope; the actual children are
 * fetched lazily through a `variables` request when IntelliJ asks for them.
 *
 * All UI callbacks ([computePresentation], [computeChildren]) are invoked on
 * the EDT. Both return immediately — the actual DAP fetch is launched on a
 * background coroutine and the result is pushed back into the [XCompositeNode]
 * once available. This keeps the Variables view responsive even against slow
 * debuggers.
 *
 * `parentVariablesReference` is the variablesReference of the *container*
 * (scope or parent struct/object) that produced this variable; DAP's
 * `setVariable` request needs it to identify the slot to mutate.
 */
class DapValue(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
    name: String,
    private var value: String,
    private val type: String?,
    private val variablesReference: Int,
    private val parentVariablesReference: Int,
    private val isMutable: Boolean,
) : XNamedValue(name) {

    private val log = logger<DapValue>()
    private var hasMutableValue: String = value

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(iconFor(type), type, hasMutableValue, variablesReference > 0)
    }

    override fun computeChildren(node: XCompositeNode) {
        if (variablesReference <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        sessionScope.scope.launch {
            try {
                val args = VariablesArguments().apply { variablesReference = this@DapValue.variablesReference }
                val response = client.variables(args)
                val children = XValueChildrenList(response.variables?.size ?: 0)
                response.variables?.forEach { child ->
                    children.add(toChild(child, parentRef = variablesReference))
                }
                ApplicationManager.getApplication().invokeLater {
                    node.addChildren(children, true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("Failed to fetch DAP variables for '$name'", throwable)
                val userMessage = DapErrors.toUserMessage(throwable)
                ApplicationManager.getApplication().invokeLater {
                    node.setErrorMessage(userMessage)
                }
            }
        }
    }

    override fun getModifier(): XValueModifier? {
        if (!isMutable || parentVariablesReference <= 0) return null
        return DapValueModifier()
    }

    private inner class DapValueModifier : XValueModifier() {
        override fun setValue(expression: XExpression, callback: XModificationCallback) {
            applyChange(expression.expression, callback)
        }

        override fun calculateInitialValueEditorText(callback: XInitialValueCallback) {
            callback.setValue(hasMutableValue)
        }

        private fun applyChange(expression: String, callback: XModificationCallback) {
            sessionScope.scope.launch {
                try {
                    val args = SetVariableArguments().apply {
                        variablesReference = parentVariablesReference
                        name = this@DapValue.name
                        this.value = expression
                    }
                    val response = client.setVariable(args)
                    val newDisplay = response.value.orEmpty()
                    hasMutableValue = newDisplay
                    value = newDisplay
                    ApplicationManager.getApplication().invokeLater { callback.valueModified() }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (throwable: Throwable) {
                    log.warn("setVariable failed for '$name'", throwable)
                    val userMessage = DapErrors.toUserMessage(throwable)
                    ApplicationManager.getApplication().invokeLater {
                        callback.errorOccurred(userMessage)
                    }
                }
            }
        }
    }

    private fun toChild(variable: Variable, parentRef: Int): DapValue =
        DapValue(
            client = client,
            sessionScope = sessionScope,
            name = variable.name ?: "<unnamed>",
            value = variable.value.orEmpty(),
            type = variable.type,
            variablesReference = variable.variablesReference ?: 0,
            parentVariablesReference = parentRef,
            isMutable = isMutable,
        )

    /**
     * Pick an icon hinting at the kind of value. The DAP `Variable.type` field
     * is free-form, so this is best-effort. Defaulting to the generic value
     * icon is always acceptable.
     */
    private fun iconFor(type: String?): javax.swing.Icon {
        val t = type?.lowercase().orEmpty()
        return when {
            t.contains("string") || t.contains("int") || t.contains("long") || t.contains("short") ||
                t.contains("byte") || t.contains("char") || t.contains("bool") ||
                t.contains("float") || t.contains("double") -> AllIcons.Debugger.Db_primitive
            else -> AllIcons.Debugger.Value
        }
    }
}
