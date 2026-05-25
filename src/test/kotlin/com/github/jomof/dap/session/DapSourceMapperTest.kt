package com.github.jomof.dap.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for the DAP↔IntelliJ line/column conversion logic.
 * The VFS-dependent helpers (`toSourcePosition`, `toDapSource`) are exercised
 * by the platform-fixture-based tests in Phase 2; here we only verify the
 * arithmetic that matters most often in practice (the off-by-one DAP base).
 */
class DapSourceMapperTest {

    private val mapper = DapSourceMapper()

    @Test fun `DAP line 1 maps to editor line 0`() {
        assertEquals(0, mapper.toEditorLine(1))
    }

    @Test fun `DAP line 42 maps to editor line 41`() {
        assertEquals(41, mapper.toEditorLine(42))
    }

    @Test fun `Editor line round-trips`() {
        (0..1000 step 17).forEach { line ->
            assertEquals(line, mapper.toEditorLine(mapper.toDapLine(line)))
        }
    }

    @Test fun `Null or zero DAP column maps to editor column 0`() {
        assertEquals(0, mapper.toEditorColumn(null))
        assertEquals(0, mapper.toEditorColumn(0))
    }

    @Test fun `Positive DAP column subtracts one`() {
        assertEquals(7, mapper.toEditorColumn(8))
    }

    @Test fun `Editor column round-trips`() {
        (0..200 step 13).forEach { col ->
            assertEquals(col, mapper.toEditorColumn(mapper.toDapColumn(col)))
        }
    }

    @Test fun `Negative DAP line clamps to editor line 0`() {
        assertEquals(0, mapper.toEditorLine(-5))
        assertEquals(0, mapper.toEditorLine(0))
    }
}
