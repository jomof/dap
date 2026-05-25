package com.github.jomof.dap.scaffold.breakpoints

import com.github.jomof.dap.evaluation.DapEditorsProvider
import com.github.jomof.dap.scaffold.language.GoLanguageProfile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * Gutter line-breakpoint type for Go files. Mirrors the C/C++ and Rust
 * breakpoint types but is scoped to `.go` via [GoLanguageProfile].
 */
class GoLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    ID,
    DISPLAY_NAME,
) {
    internal val dapEditorsProvider = DapEditorsProvider(GoLanguageProfile)

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        GoLanguageProfile.isDebuggable(file)

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun getEditorsProvider(
        breakpoint: XLineBreakpoint<XBreakpointProperties<*>>,
        project: Project,
    ): XDebuggerEditorsProvider = dapEditorsProvider

    companion object {
        const val ID: String = "dap-go"
        const val DISPLAY_NAME: String = "DAP Go Line Breakpoint"
    }
}
