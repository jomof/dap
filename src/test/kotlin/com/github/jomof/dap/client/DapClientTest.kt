package com.github.jomof.dap.client

import com.github.jomof.dap.mock.MockDapFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end smoke tests for [DapClient] against an in-process [MockDapFixture].
 * No IntelliJ Platform machinery is involved — these tests only care that the
 * JSON-RPC plumbing and capability translation work.
 */
class DapClientTest {

    private lateinit var fixture: MockDapFixture

    @Before fun setUp() { fixture = MockDapFixture() }

    @After fun tearDown() { fixture.close() }

    @Test
    fun `initialize round-trips and exposes capabilities`() = runBlocking {
        val capabilities = withTimeout(5.seconds) {
            fixture.client.initialize(InitializeRequestArguments().apply {
                clientID = "dap-test"
                adapterID = "mock"
                linesStartAt1 = true
                columnsStartAt1 = true
            })
        }
        assertNotNull("server received initialize args", fixture.server.initializeArgs.get())
        assertEquals("dap-test", fixture.server.initializeArgs.get()?.clientID)
        assertTrue(capabilities.supportsConfigurationDoneRequest)
        assertTrue(capabilities.supportsConditionalBreakpoints)
        assertTrue(capabilities.supportsLogPoints)
    }

    @Test
    fun `setBreakpoints returns verified breakpoints`() = runBlocking {
        // Mock returns all breakpoints as verified regardless of capabilities.
        val response = withTimeout(5.seconds) {
            fixture.client.setBreakpoints(SetBreakpointsArguments().apply {
                source = Source().apply { path = "/tmp/test.cpp" }
                breakpoints = arrayOf(SourceBreakpoint().apply { line = 42 })
            })
        }
        assertEquals(1, response.breakpoints.size)
        assertTrue(response.breakpoints[0].isVerified)
        assertEquals(42, response.breakpoints[0].line)
        assertEquals(1, fixture.server.setBreakpointsCalls.get())
    }

    @Test
    fun `pause request reaches the server`() = runBlocking {
        withTimeout(5.seconds) {
            fixture.client.pause(org.eclipse.lsp4j.debug.PauseArguments().apply { threadId = 1 })
        }
        assertEquals(1, fixture.server.pauseCalls.get())
    }

    @Test
    fun `continue request reaches the server`() = runBlocking {
        withTimeout(5.seconds) {
            fixture.client.continueExecution(org.eclipse.lsp4j.debug.ContinueArguments().apply { threadId = 1 })
        }
        assertEquals(1, fixture.server.continueCalls.get())
    }

    @Test
    fun `client receives initialized event from server`() = runBlocking {
        // Subscribe before firing so we don't race past the emit.
        val received = withTimeout(5.seconds) {
            coroutineScope {
                val deferred = async {
                    fixture.client.events.first { it === DapEvent.Initialized }
                }
                // Tiny delay so subscription is set up before the emit.
                delay(50.milliseconds)
                fixture.server.fireInitialized()
                deferred.await()
            }
        }
        assertNotNull(received)
    }

    @Test
    fun `awaitInitialized resolves even when called after the event fired`() = runBlocking {
        // Fire the event first, then suspend on awaitInitialized — the one-shot
        // signal must be observable even though the event-flow subscription
        // would have missed it (replay = 0).
        fixture.server.fireInitialized()
        delay(50.milliseconds)
        withTimeout(5.seconds) { fixture.client.awaitInitialized() }
    }
}
