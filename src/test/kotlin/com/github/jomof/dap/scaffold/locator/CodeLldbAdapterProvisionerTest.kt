package com.github.jomof.dap.scaffold.locator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-Kotlin tests for the bits of [CodeLldbAdapterProvisioner] that
 * don't need a real CodeLLDB install — currently just the
 * `liblldbFor()` lookup. The full resolve/provision pipeline is
 * exercised end-to-end by the opt-in `CodeLldbInitializeIntegrationTest`
 * and friends.
 */
class CodeLldbAdapterProvisionerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `lsp4ijCache reuses complete existing adapter from lsp4ij download`() {
        val home = tmp.newFolder("home").toPath()
        val root = home.resolve(".lsp4ij/dap/codelldb")
        val adapter = createFile(
            root.resolve(CodeLldbAssetCatalog.adapterPath(System.getProperty("os.name") ?: "")),
            executable = true,
        )
        createFile(root.resolve(CodeLldbAssetCatalog.liblldbPath(System.getProperty("os.name") ?: "")))

        assertEquals(
            adapter.toAbsolutePath(),
            CodeLldbAdapterProvisioner.lsp4ijCache(home)?.toAbsolutePath(),
        )
    }

    @Test fun `lsp4ijCache ignores incomplete adapter without bundled liblldb`() {
        val home = tmp.newFolder("home").toPath()
        createFile(
            home
                .resolve(".lsp4ij/dap/codelldb")
                .resolve(CodeLldbAssetCatalog.adapterPath(System.getProperty("os.name") ?: "")),
            executable = true,
        )

        assertNull(CodeLldbAdapterProvisioner.lsp4ijCache(home))
    }

    @Test fun `liblldbFor finds dylib next to adapter in lsp4ij-style layout`() {
        val root = tmp.newFolder("install").toPath()
        val adapter = createFile(root.resolve("extension/adapter/codelldb"))
        val dylib = createFile(root.resolve("extension/lldb/lib/liblldb.dylib"))
        assertEquals(
            dylib.toAbsolutePath(),
            CodeLldbAdapterProvisioner.liblldbFor(adapter)?.toAbsolutePath(),
        )
    }

    @Test fun `liblldbFor finds dylib in flat VS Code-extracted layout`() {
        // VS Code unpacks the VSIX dropping the `extension/` prefix,
        // so the bundled lldb lives at <root>/lldb/lib/liblldb.dylib
        // instead of <root>/extension/lldb/lib/liblldb.dylib.
        val root = tmp.newFolder("vscode-install").toPath()
        val adapter = createFile(root.resolve("adapter/codelldb"))
        val dylib = createFile(root.resolve("lldb/lib/liblldb.dylib"))
        assertEquals(
            dylib.toAbsolutePath(),
            CodeLldbAdapterProvisioner.liblldbFor(adapter)?.toAbsolutePath(),
        )
    }

    @Test fun `liblldbFor finds so on Linux-style install`() {
        val root = tmp.newFolder("linux-install").toPath()
        val adapter = createFile(root.resolve("extension/adapter/codelldb"))
        val so = createFile(root.resolve("extension/lldb/lib/liblldb.so"))
        assertEquals(
            so.toAbsolutePath(),
            CodeLldbAdapterProvisioner.liblldbFor(adapter)?.toAbsolutePath(),
        )
    }

    @Test fun `liblldbFor returns null when adapter is a standalone binary`() {
        // A user-supplied DAP_CODELLDB pointing at a hand-built copy
        // somewhere unrelated — we can't guess where the matching
        // liblldb lives, so we return null and let the adapter speak
        // for itself.
        val standalone = createFile(tmp.newFolder("loose").toPath().resolve("codelldb"))
        assertNull(CodeLldbAdapterProvisioner.liblldbFor(standalone))
    }

    private fun createFile(path: Path, executable: Boolean = false): Path {
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf(0x7F))
        if (executable) path.toFile().setExecutable(true)
        return path
    }
}
