package com.github.jomof.dap.breakpoints

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.eclipse.lsp4j.debug.Breakpoint

/**
 * Bridges IntelliJ's [XBreakpointHandler] callbacks for one concrete
 * [XLineBreakpointType] to the per-file batched [DapBreakpointSync].
 *
 * The handler **type-parameter is fixed at construction** (this is what the
 * platform requires — each breakpoint type the user has registered needs its
 * own handler instance). Multiple language profiles therefore create one
 * handler each, all delegating to the same [DapBreakpointSync].
 *
 * The unchecked cast on the constructor argument is unavoidable: in Java the
 * upper bound is `Class<? extends XBreakpointType<B, ?>>` and `XLineBreakpointType<P>`
 * satisfies it, but Kotlin's variance rules over the captured wildcards
 * cannot prove that automatically.
 */
@Suppress("UNCHECKED_CAST")
class DapBreakpointHandler(
    private val project: Project,
    private val sync: DapBreakpointSync,
    breakpointTypeClass: Class<out XLineBreakpointType<*>>,
) : XBreakpointHandler<XLineBreakpoint<*>>(
    breakpointTypeClass as Class<out XBreakpointType<XLineBreakpoint<*>, *>>,
) {

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<*>) {
        sync.set(breakpoint)
    }

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<*>, temporary: Boolean) {
        sync.remove(breakpoint)
    }

    /**
     * Applies adapter-reported state (verified flag, error message, …) to the
     * IDE-side breakpoint. Called by [DapBreakpointSync] after every
     * `setBreakpoints` round-trip and also when an async DAP `breakpoint`
     * event mutates an existing breakpoint.
     */
    fun applyAdapterResponse(breakpoint: XLineBreakpoint<*>, response: Breakpoint) {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        if (response.isVerified) {
            manager.updateBreakpointPresentation(breakpoint, null, null)
        } else {
            val message = response.message?.takeIf { it.isNotBlank() } ?: "Unverified by adapter"
            manager.updateBreakpointPresentation(
                breakpoint,
                com.intellij.icons.AllIcons.Debugger.Db_invalid_breakpoint,
                message,
            )
        }
    }
}
