package com.github.jomof.dap.scaffold.breakpoints

import com.intellij.testFramework.LightPlatformTestCase

/**
 * Regression guard: every DAP gutter breakpoint type must return a non-null
 * `XDebuggerEditorsProvider` from `getEditorsProvider()`. If it doesn't,
 * IntelliJ silently hides the Condition / Log Expression / "Evaluate and
 * log" editors in the Breakpoints dialog — exactly the symptom that bit us
 * when we first wired the C/C++ breakpoint type up.
 *
 * Uses [LightPlatformTestCase] so [com.github.jomof.dap.evaluation.DapEditorsProvider]
 * can resolve `Language.findLanguageByID("TEXT")` for its fallback.
 */
class DapLineBreakpointTypesTest : LightPlatformTestCase() {

    fun `test cpp breakpoint type advertises an editors provider`() {
        assertNotNull(
            "CppLineBreakpointType must expose dapEditorsProvider so the Breakpoints dialog shows Condition/Log fields",
            CppLineBreakpointType().dapEditorsProvider,
        )
    }

    fun `test rust breakpoint type advertises an editors provider`() {
        assertNotNull(
            "RustLineBreakpointType must expose dapEditorsProvider so the Breakpoints dialog shows Condition/Log fields",
            RustLineBreakpointType().dapEditorsProvider,
        )
    }

    fun `test go breakpoint type advertises an editors provider`() {
        assertNotNull(
            "GoLineBreakpointType must expose dapEditorsProvider so the Breakpoints dialog shows Condition/Log fields",
            GoLineBreakpointType().dapEditorsProvider,
        )
    }
}
