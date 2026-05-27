package com.github.jomof.dap.session

import com.github.jomof.dap.mock.MockDapFixture
import com.github.jomof.dap.scaffold.language.CppLanguageProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DapStartupSequenceTest {

    private lateinit var fixture: MockDapFixture

    @Before fun setUp() {
        fixture = MockDapFixture()
    }

    @After fun tearDown() {
        fixture.close()
    }

    @Test
    fun `exception breakpoints are armed before configurationDone can resume debuggee`() = runBlocking {
        val capabilities = withTimeout(TIMEOUT) {
            fixture.client.initialize(InitializeRequestArguments().apply {
                clientID = "dap-startup-test"
                adapterID = "mock"
                linesStartAt1 = true
                columnsStartAt1 = true
            })
        }

        withTimeout(TIMEOUT) {
            DapStartupSequence.configureBeforeResume(
                profile = CppLanguageProfile,
                capabilities = capabilities,
                client = fixture.client,
                flushLineBreakpoints = { fixture.server.callLog += "flushLineBreakpoints" },
            )
        }

        assertEquals(
            listOf("flushLineBreakpoints", "setExceptionBreakpoints", "configurationDone"),
            fixture.server.callLog.toList(),
        )
        assertEquals(1, fixture.server.setExceptionBreakpointsCalls.get())
        val filters = fixture.server.lastSetExceptionBreakpoints.get()?.filters?.toList()
        assertEquals(listOf("cpp_throw"), filters)
        assertEquals(1, fixture.server.configurationDoneCalls.get())
        assertTrue(
            "setExceptionBreakpoints must precede configurationDone",
            fixture.server.callLog.indexOf("setExceptionBreakpoints") <
                fixture.server.callLog.indexOf("configurationDone"),
        )
    }

    private companion object {
        val TIMEOUT = 5.seconds
    }
}
