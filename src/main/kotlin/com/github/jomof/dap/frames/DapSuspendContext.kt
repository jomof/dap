package com.github.jomof.dap.frames

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext

/**
 * The platform's suspend context: a snapshot of all threads at the moment
 * execution stopped. The "active" stack is the one whose thread fired the
 * `stopped` event (if any) — IntelliJ highlights its top frame in the editor
 * and selects it in the Threads panel by default.
 */
class DapSuspendContext(
    private val stacks: List<DapExecutionStack>,
    private val activeStack: DapExecutionStack?,
) : XSuspendContext() {

    override fun getActiveExecutionStack(): XExecutionStack? = activeStack

    override fun getExecutionStacks(): Array<XExecutionStack> = stacks.toTypedArray<XExecutionStack>()
}
