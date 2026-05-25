package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * CodeLLDB twin of [LldbDapInitializeIntegrationTest]: drives a real
 * CodeLLDB process (downloaded into the local cache by
 * `CodeLldbAdapterProvisioner` if not already present) through the
 * `initialize` handshake over a TCP transport.
 *
 *  - Skipped unless `DAP_INTEGRATION_TESTS=1` is set.
 *  - Also skipped when the provisioner can't get a binary (no network,
 *    unsupported os/arch, etc.) — surfaced via stderr so the run log
 *    still tells you why.
 *
 * What this test proves end-to-end: our `DapAdapterProvisioner` SPI
 * actually produces a runnable adapter binary AND the
 * `--port=N --liblldb=…` recipe makes CodeLLDB accept a TCP DAP
 * connection without further coaxing.
 */
class CodeLldbInitializeIntegrationTest {

    private var fixture: CodeLldbProcessFixture? = null
    private var client: DapClient? = null

    @Before fun setUp() {
        assumeTrue("Set DAP_INTEGRATION_TESTS=1 to enable", System.getenv("DAP_INTEGRATION_TESTS") == "1")
        fixture = CodeLldbTestFixtures.tryProvisionAndSpawn()
        assumeTrue("CodeLLDB could not be provisioned on this host", fixture != null)
    }

    @After fun tearDown() {
        client?.dispose()
        fixture?.dispose()
    }

    @Test fun `codelldb responds to initialize over TCP`() = runBlocking {
        val fx = fixture!!
        val client = DapClient(fx.transport).also { it.connect() }
        this@CodeLldbInitializeIntegrationTest.client = client
        val capabilities = withTimeout(15.seconds) {
            client.initialize(InitializeRequestArguments().apply {
                clientID = "dap.intellij.integration-test"
                // adapterID `lldb` is what CodeLLDB expects (matching
                // the `type: "lldb"` launch config used by VS Code and
                // lsp4ij's codelldb template).
                adapterID = "lldb"
                linesStartAt1 = true
                columnsStartAt1 = true
                pathFormat = "path"
            })
        }
        assertNotNull(capabilities)
        // CodeLLDB advertises configurationDone support by spec, same
        // as lldb-dap. If this regresses we've handshaked with the
        // wrong server.
        assert(capabilities.supportsConfigurationDoneRequest) {
            "CodeLLDB should support configurationDone"
        }
    }
}
