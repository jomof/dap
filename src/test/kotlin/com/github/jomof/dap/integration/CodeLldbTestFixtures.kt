package com.github.jomof.dap.integration

import com.github.jomof.dap.client.DapTransport
import com.github.jomof.dap.scaffold.locator.CodeLldbAdapterProvisioner
import com.github.jomof.dap.scaffold.run.DapTransportBlueprint
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path

/**
 * Helper for the CodeLLDB integration suite: encapsulates the
 * provisioner call + TCP spawn so each test body can stay focused on
 * the DAP behaviour it's verifying.
 *
 * The adapter is *always* invoked over TCP — CodeLLDB has no stdio
 * mode (unlike lldb-dap), so we pre-pick a free port and pass
 * `--port=<port>` plus `--liblldb=<path>` to point at the bundled
 * library. Connection retries are handled by
 * [DapTransportBlueprint.TcpAfterStartup] so a slow adapter startup
 * doesn't cause flakes.
 *
 * Usage:
 * ```
 * val fixture = CodeLldbTestFixtures.tryProvisionAndSpawn()
 *     ?: return@runBlocking // skipped above
 * val client = DapClient(fixture.transport).also { it.connect() }
 * ...
 * fixture.dispose()
 * ```
 */
internal data class CodeLldbProcessFixture(
    val binary: Path,
    val process: Process,
    val transport: DapTransport,
    val port: Int,
) {
    fun dispose() {
        runCatching { transport.close() }
        runCatching { process.destroy() }
    }
}

internal object CodeLldbTestFixtures {

    /**
     * Provisions a CodeLLDB binary (resolving from cache or downloading
     * the latest release), spawns it on a free TCP port, and connects.
     * Returns `null` when the provisioner can't get a binary on this
     * host — the caller should `assumeTrue` on the result and skip.
     */
    fun tryProvisionAndSpawn(): CodeLldbProcessFixture? {
        val binary = try {
            CodeLldbAdapterProvisioner.provision()
        } catch (failure: Throwable) {
            // Network failures, unsupported os/arch, sandboxing — all
            // legitimate reasons to skip these tests, not fail them.
            System.err.println("[CodeLldb integration] provision failed: ${failure.message}")
            return null
        }
        val liblldb = CodeLldbAdapterProvisioner.liblldbFor(binary)
        val port = ServerSocket(0).use { it.localPort }
        val args = mutableListOf(binary.toString(), "--port=$port")
        liblldb?.let { args += "--liblldb=$it" }
        val process = try {
            ProcessBuilder(args).redirectErrorStream(false).start()
        } catch (failure: IOException) {
            System.err.println("[CodeLldb integration] spawn failed: ${failure.message}")
            return null
        }
        val transport = try {
            DapTransportBlueprint.TcpAfterStartup(
                host = "127.0.0.1",
                port = port,
                readyTimeoutMs = 10_000,
            ).connect(process)
        } catch (failure: IOException) {
            process.destroy()
            System.err.println("[CodeLldb integration] connect failed: ${failure.message}")
            return null
        }
        return CodeLldbProcessFixture(binary, process, transport, port)
    }
}
