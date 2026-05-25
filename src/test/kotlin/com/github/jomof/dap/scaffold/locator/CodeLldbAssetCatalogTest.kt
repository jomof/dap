package com.github.jomof.dap.scaffold.locator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the os/arch → asset matrix used to pick a CodeLLDB release
 * artifact. The expected names mirror lsp4ij's `installer.json` for the
 * `codelldb` template, so a regression here would diverge from the
 * RedHat plugin's behaviour.
 */
class CodeLldbAssetCatalogTest {

    @Test fun `picks darwin arm64 for macOS on M-series`() {
        assertEquals("codelldb-darwin-arm64.vsix", CodeLldbAssetCatalog.assetName("Mac OS X", "aarch64"))
        assertEquals("codelldb-darwin-arm64.vsix", CodeLldbAssetCatalog.assetName("Darwin", "arm64"))
    }

    @Test fun `picks darwin x64 for macOS on Intel`() {
        assertEquals("codelldb-darwin-x64.vsix", CodeLldbAssetCatalog.assetName("Mac OS X", "x86_64"))
        assertEquals("codelldb-darwin-x64.vsix", CodeLldbAssetCatalog.assetName("Mac OS X", "amd64"))
    }

    @Test fun `picks linux variants by arch`() {
        assertEquals("codelldb-linux-x64.vsix", CodeLldbAssetCatalog.assetName("Linux", "x86_64"))
        assertEquals("codelldb-linux-arm64.vsix", CodeLldbAssetCatalog.assetName("Linux", "aarch64"))
    }

    @Test fun `picks the only windows variant for x64`() {
        assertEquals("codelldb-win32-x64.vsix", CodeLldbAssetCatalog.assetName("Windows 11", "amd64"))
    }

    @Test fun `returns null when upstream doesn't ship the combination`() {
        // CodeLLDB doesn't currently publish a win-arm64 build.
        assertNull(CodeLldbAssetCatalog.assetName("Windows 11", "aarch64"))
    }

    @Test fun `returns null for unknown os or arch`() {
        assertNull(CodeLldbAssetCatalog.assetName("Plan9", "x86_64"))
        assertNull(CodeLldbAssetCatalog.assetName("Linux", "sparc64"))
    }

    @Test fun `adapter path is exe on windows and plain elsewhere`() {
        assertEquals("extension/adapter/codelldb.exe", CodeLldbAssetCatalog.adapterPath("Windows 11"))
        assertEquals("extension/adapter/codelldb", CodeLldbAssetCatalog.adapterPath("Mac OS X"))
        assertEquals("extension/adapter/codelldb", CodeLldbAssetCatalog.adapterPath("Linux"))
    }

    @Test fun `liblldb path varies by platform`() {
        assertEquals("extension/lldb/lib/liblldb.dylib", CodeLldbAssetCatalog.liblldbPath("Mac OS X"))
        assertEquals("extension/lldb/lib/liblldb.so", CodeLldbAssetCatalog.liblldbPath("Linux"))
        assertEquals("extension/lldb/bin/liblldb.dll", CodeLldbAssetCatalog.liblldbPath("Windows 11"))
    }
}
