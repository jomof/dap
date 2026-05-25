package com.github.jomof.dap.scaffold.breakpoints

import com.github.jomof.dap.evaluation.DapEditorsProvider
import com.github.jomof.dap.scaffold.language.CppLanguageProfile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * Gutter line-breakpoint type for C/C++ files. Phase 1 owns it; if the user
 * has other line-breakpoint plugins installed for C/C++ (e.g. CLion's native
 * one), the platform will offer both choices in the breakpoint dialog.
 *
 * Carries no per-breakpoint properties — DAP `SourceBreakpoint` already
 * captures everything we need.
 *
 * [getEditorsProvider] returns a non-null provider so the breakpoint
 * properties dialog surfaces the Condition / Log Expression / "Evaluate
 * and log" editors. Without it the platform hides those fields entirely,
 * because it has no language to give the embedded editor.
 */
class CppLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    ID,
    DISPLAY_NAME,
) {
    internal val dapEditorsProvider = DapEditorsProvider(CppLanguageProfile)

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        CppLanguageProfile.isDebuggable(file)

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun getEditorsProvider(
        breakpoint: XLineBreakpoint<XBreakpointProperties<*>>,
        project: Project,
    ): XDebuggerEditorsProvider = dapEditorsProvider

    companion object {
        const val ID: String = "dap-cpp"
        const val DISPLAY_NAME: String = "DAP C/C++ Line Breakpoint"
    }
}
