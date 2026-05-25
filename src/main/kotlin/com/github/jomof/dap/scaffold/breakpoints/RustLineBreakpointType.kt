package com.github.jomof.dap.scaffold.breakpoints

import com.github.jomof.dap.evaluation.DapEditorsProvider
import com.github.jomof.dap.scaffold.language.RustLanguageProfile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * Gutter line-breakpoint type for Rust files. Mirrors
 * [com.github.jomof.dap.scaffold.breakpoints.CppLineBreakpointType] but
 * scoped to `.rs` via [RustLanguageProfile].
 */
class RustLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    ID,
    DISPLAY_NAME,
) {
    internal val dapEditorsProvider = DapEditorsProvider(RustLanguageProfile)

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        RustLanguageProfile.isDebuggable(file)

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun getEditorsProvider(
        breakpoint: XLineBreakpoint<XBreakpointProperties<*>>,
        project: Project,
    ): XDebuggerEditorsProvider = dapEditorsProvider

    companion object {
        const val ID: String = "dap-rust"
        const val DISPLAY_NAME: String = "DAP Rust Line Breakpoint"
    }
}
