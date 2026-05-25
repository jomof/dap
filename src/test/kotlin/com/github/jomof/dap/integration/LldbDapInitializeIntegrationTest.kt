package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapTransport
import com.github.jomof.dap.scaffold.locator.LldbDapLocator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Opt-in integration test that drives a real lldb-dap process through the
 * `initialize` handshake.
 *
 *  - Skipped automatically when neither the env-override `DAP_LLDB_DAP` nor
 *    `~/projects/lldb/bin/lldb-dap` nor a PATH entry resolve.
 *  - Skipped in CI by default unless `DAP_INTEGRATION_TESTS=1` is set
 *    (avoids spawning external processes on shared runners).
 *
 * Phase 1 only verifies the handshake; later phases will add tests that
 * actually launch a debuggee.
 */
class LldbDapInitializeIntegrationTest {

    private var process: Process? = null
    private var client: DapClient? = null

    @Before fun setUp() {
        assumeTrue("Set DAP_INTEGRATION_TESTS=1 to enable", System.getenv("DAP_INTEGRATION_TESTS") == "1")
        val binary = LldbDapLocator.resolve()
        assumeTrue("lldb-dap not found on this host", binary != null && Files.isExecutable(binary))
        process = ProcessBuilder(binary!!.toString()).redirectErrorStream(false).start()
    }

    @After fun tearDown() {
        client?.dispose()
        process?.destroy()
    }

    @Test
    fun `lldb-dap responds to initialize`() = runBlocking {
        val transport = DapTransport.Stdio(process!!.inputStream, process!!.outputStream)
        val client = DapClient(transport).also { it.connect() }
        this@LldbDapInitializeIntegrationTest.client = client
        val capabilities = withTimeout(10.seconds) {
            client.initialize(InitializeRequestArguments().apply {
                clientID = "dap.intellij.integration-test"
                adapterID = "lldb-dap"
                linesStartAt1 = true
                columnsStartAt1 = true
                pathFormat = "path"
            })
        }
        assertNotNull(capabilities)
        // lldb-dap is required by spec to surface configurationDone support.
        assert(capabilities.supportsConfigurationDoneRequest) {
            "lldb-dap should support configurationDone"
        }
    }
}
