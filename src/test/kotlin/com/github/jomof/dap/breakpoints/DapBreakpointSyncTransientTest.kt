package com.github.jomof.dap.breakpoints

import com.github.jomof.dap.mock.MockDapFixture
import com.github.jomof.dap.session.DapSessionScope
import com.github.jomof.dap.session.DapSourceMapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for the transient (one-shot) breakpoint mechanism that
 * backs Run to Cursor. We bypass the IntelliJ platform and talk to a
 * [MockDapFixture] directly, focusing on two things only:
 *
 *  1. After [DapBreakpointSync.installTransientLineBreakpoint] the adapter
 *     sees a `setBreakpoints` payload that includes the transient line.
 *  2. After [DapBreakpointSync.consumeHitTransients] the adapter sees a
 *     follow-up `setBreakpoints` payload that DROPS the transient (so a
 *     subsequent continue doesn't re-hit it).
 *
 * Motivating bug: Run to Cursor was throwing `AbstractMethodError` because
 * [DapDebugProcess] inherited the platform's stub `runToPosition`. The fix
 * was to install/continue/consume; these tests pin the install/consume half
 * (the continue is a one-line client call that we don't need to retest).
 *
 * A `LightVirtualFile` stands in for a real source file. It is purely
 * in-memory and doesn't require any IntelliJ application initialisation,
 * which keeps these tests in the "fast unit" tier.
 */
class DapBreakpointSyncTransientTest {

    private lateinit var fixture: MockDapFixture
    private lateinit var scope: DapSessionScope
    private lateinit var sync: DapBreakpointSync

    @Before fun setUp() {
        fixture = MockDapFixture()
        scope = DapSessionScope("transient-test")
        sync = DapBreakpointSync(
            client = fixture.client,
            sessionScope = scope,
            sourceMapper = DapSourceMapper(),
            capabilitiesProvider = { null },
            onAdapterResponse = { _, _ -> /* no IDE-side wiring in this test */ },
        )
    }

    @After fun tearDown() {
        sync.shutdown()
        scope.dispose()
        fixture.close()
    }

    @Test
    fun `installTransientLineBreakpoint ships exactly one breakpoint at the requested line`() = runBlocking {
        val file = sourceFile("/tmp/dap-test/foo.cpp")

        withTimeout(TIMEOUT) {
            sync.installTransientLineBreakpoint(file, EDITOR_LINE_TARGET)
        }

        val args = fixture.server.lastSetBreakpoints.get()
        assertNotNull("adapter received setBreakpoints", args)
        assertEquals("/tmp/dap-test/foo.cpp", args!!.source.path)
        val lines = (args.breakpoints ?: emptyArray()).map { it.line }
        // Editor line 9 (0-based) → DAP line 10 (1-based).
        assertArrayEquals(intArrayOf(DAP_LINE_TARGET), lines.toIntArray())
    }

    @Test
    fun `consumeHitTransients drops the transient and re-flushes the empty set`() = runBlocking {
        val file = sourceFile("/tmp/dap-test/foo.cpp")
        withTimeout(TIMEOUT) {
            sync.installTransientLineBreakpoint(file, EDITOR_LINE_TARGET)
        }
        val hitId = fixture.server.lastSetBreakpoints.get()!!
            .breakpoints!!.first()
            .let { /* mock assigns id=1 to the first breakpoint */ 1 }

        // The adapter has now stopped on the transient breakpoint. The session
        // layer would call consumeHitTransients() with the hit ids; verify
        // that produces a re-flush with the transient removed.
        sync.consumeHitTransients(listOf(hitId))

        // The re-flush is debounced; wait for the second setBreakpoints call.
        val args = pollForSetBreakpointsCount(expected = 2)
        assertEquals(
            "follow-up flush should drop the transient",
            0,
            (args.breakpoints ?: emptyArray()).size,
        )
    }

    @Test
    fun `transient lives alongside a separately-tracked user breakpoint and only the transient is dropped on hit`() = runBlocking {
        // We can't easily mint a real XLineBreakpoint here without the
        // platform, so we drive the user-bp path through a second transient.
        // Functionally identical from the adapter's perspective: the file has
        // two breakpoints, we then "hit" only one of them, and the next
        // setBreakpoints payload should keep the survivor.
        val file = sourceFile("/tmp/dap-test/foo.cpp")
        withTimeout(TIMEOUT) {
            sync.installTransientLineBreakpoint(file, EDITOR_LINE_TARGET)
            sync.installTransientLineBreakpoint(file, EDITOR_LINE_OTHER)
        }
        // Each flush replaces the previous breakpoint set so the latest call
        // has both lines (in install order).
        val both = (fixture.server.lastSetBreakpoints.get()!!.breakpoints ?: emptyArray()).map { it.line }
        assertEquals(listOf(DAP_LINE_TARGET, DAP_LINE_OTHER), both)

        // Adapter assigns ids 1, 2 to the two transient lines (in payload
        // order). Hit only the first — the second must remain installed.
        sync.consumeHitTransients(listOf(1))

        val survivor = pollForSetBreakpointsContaining(expectedLines = listOf(DAP_LINE_OTHER))
        assertArrayEquals(
            intArrayOf(DAP_LINE_OTHER),
            (survivor.breakpoints ?: emptyArray()).map { it.line }.toIntArray(),
        )
    }

    /**
     * `consumeHitTransients` schedules a debounced flush, so we poll briefly
     * for the adapter to see the next request. The mock fixture counts
     * setBreakpoints calls atomically, so we just wait for the count to tick.
     */
    private suspend fun pollForSetBreakpointsCount(expected: Int): SetBreakpointsArguments {
        withTimeout(TIMEOUT) {
            while (fixture.server.setBreakpointsCalls.get() < expected) {
                kotlinx.coroutines.delay(10)
            }
        }
        val args = fixture.server.lastSetBreakpoints.get()
        assertNotNull(args)
        return args!!
    }

    private suspend fun pollForSetBreakpointsContaining(expectedLines: List<Int>): SetBreakpointsArguments {
        withTimeout(TIMEOUT) {
            while (true) {
                val args = fixture.server.lastSetBreakpoints.get()
                val lines = (args?.breakpoints ?: emptyArray()).map { it.line }
                if (lines == expectedLines) return@withTimeout
                kotlinx.coroutines.delay(10)
            }
        }
        return fixture.server.lastSetBreakpoints.get()!!
    }

    private fun sourceFile(path: String): VirtualFile {
        // LightVirtualFile is the IntelliJ test-framework's pure-in-memory
        // VFS object — perfect for tests that only need `path` and `name`.
        // The first constructor arg ("name") is what `getName()` returns;
        // we override `getPath()` so the adapter sees a realistic absolute
        // path (the mock asserts on path, the production code uses it as
        // the per-file mirror key).
        return object : LightVirtualFile(path.substringAfterLast('/'), "") {
            override fun getPath(): String = path
        }
    }

    private companion object {
        val TIMEOUT = 5.seconds

        /** Editor line we run to (0-based, matching IDE breakpoint API). */
        const val EDITOR_LINE_TARGET = 9

        /** Same line in DAP coordinates (1-based). */
        const val DAP_LINE_TARGET = 10

        const val EDITOR_LINE_OTHER = 19
        const val DAP_LINE_OTHER = 20
    }
}
