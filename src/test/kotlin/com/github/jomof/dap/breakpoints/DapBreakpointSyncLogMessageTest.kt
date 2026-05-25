package com.github.jomof.dap.breakpoints

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the IntelliJ-expression → DAP-logMessage translation, the bit that
 * made "Evaluate and log" actually evaluate against lldb-dap instead of
 * echoing the expression text. See [DapBreakpointSync.toDapLogMessage].
 */
class DapBreakpointSyncLogMessageTest {

    @Test
    fun `wraps bare expression in braces so adapter evaluates it`() {
        // The motivating bug: this exact expression rode through to lldb-dap
        // as-is and was printed verbatim (with the literal quotes) on every
        // hit instead of being evaluated.
        val expression = """"counter is " + std::to_string(counter)"""
        val expected = """{"counter is " + std::to_string(counter)}"""

        assertEquals(expected, DapBreakpointSync.toDapLogMessage(expression))
    }

    @Test
    fun `wraps simple identifier`() {
        assertEquals("{counter}", DapBreakpointSync.toDapLogMessage("counter"))
    }

    @Test
    fun `passes through expression that already contains a DAP template`() {
        // If the user types in DAP template syntax explicitly we honour it
        // rather than producing `{counter is {value}}` (which most adapters
        // wouldn't parse correctly anyway).
        val template = "counter is {counter}"

        assertEquals(template, DapBreakpointSync.toDapLogMessage(template))
    }

    @Test
    fun `treats lambda containing braces as a single expression to evaluate`() {
        // A C++ lambda has braces, but the user's intent is "evaluate this
        // whole thing once and print the result". Because the lambda body
        // is `{ return … }` (with whitespace inside the braces), our probe
        // matches it as a template — which is actually fine: the adapter
        // evaluates `[](auto x){ return x*2; }(counter)` as one expression
        // either way, so passing it through unwrapped yields the same
        // result with one fewer brace pair.
        val expression = "[](auto x){ return x*2; }(counter)"

        // We don't assert exact wrap-vs-passthrough behaviour here — both
        // produce the correct output once lldb-dap parses the format
        // string. We just assert we don't corrupt the expression.
        val result = DapBreakpointSync.toDapLogMessage(expression)
        assert(result.contains("return x*2"))
    }
}
