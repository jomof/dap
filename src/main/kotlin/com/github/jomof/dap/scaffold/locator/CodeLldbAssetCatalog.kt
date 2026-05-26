package com.github.jomof.dap.scaffold.locator

/**
 * Mapping from `(os, arch)` to the GitHub release asset name for the
 * CodeLLDB VSIX. The matrix mirrors lsp4ij's `installer.json` for the
 * `codelldb` template (which is the canonical reference used by the
 * RedHat LSP4IJ plugin) so that users get the same binary either tool
 * would install.
 *
 * Kept as a top-level object so callers and tests can hit the lookup
 * without instantiating the downloader.
 */
object CodeLldbAssetCatalog {

    /**
     * Returns the asset file name (e.g. `codelldb-darwin-arm64.vsix`) for
     * the given JVM-reported os/arch values, or `null` if no canonical
     * upstream artifact exists for that combination.
     */
    fun assetName(osName: String, osArch: String): String? {
        val os = normaliseOs(osName) ?: return null
        val arch = normaliseArch(osArch) ?: return null
        return when (os) {
            Os.MAC -> when (arch) {
                Arch.X86_64 -> "codelldb-darwin-x64.vsix"
                Arch.ARM64 -> "codelldb-darwin-arm64.vsix"
            }
            Os.LINUX -> when (arch) {
                Arch.X86_64 -> "codelldb-linux-x64.vsix"
                Arch.ARM64 -> "codelldb-linux-arm64.vsix"
            }
            Os.WINDOWS -> when (arch) {
                Arch.X86_64 -> "codelldb-win32-x64.vsix"
                Arch.ARM64 -> null // upstream doesn't ship a win-arm64 build (yet)
            }
        }
    }

    /**
     * Relative path inside the extracted VSIX that points to the
     * adapter executable. Windows packs it as `.exe`; everywhere else
     * the same `extension/adapter/codelldb` layout used by lsp4ij.
     */
    fun adapterPath(osName: String): String =
        if (normaliseOs(osName) == Os.WINDOWS) "extension/adapter/codelldb.exe"
        else "extension/adapter/codelldb"

    /**
     * Relative path inside the extracted VSIX that points to the bundled
     * `liblldb`. Required as the `--liblldb=<path>` argument to the
     * adapter (without it, the adapter aborts at startup looking for the
     * system lldb).
     */
    fun liblldbPath(osName: String): String = when (normaliseOs(osName)) {
        Os.MAC -> "extension/lldb/lib/liblldb.dylib"
        Os.LINUX -> "extension/lldb/lib/liblldb.so"
        Os.WINDOWS -> "extension/lldb/bin/liblldb.dll"
        null -> "extension/lldb/lib/liblldb"
    }

    private enum class Os { MAC, LINUX, WINDOWS }
    private enum class Arch { X86_64, ARM64 }

    private fun normaliseOs(osName: String): Os? {
        val lower = osName.lowercase()
        return when {
            "mac" in lower || "darwin" in lower -> Os.MAC
            "linux" in lower -> Os.LINUX
            "win" in lower -> Os.WINDOWS
            else -> null
        }
    }

    private fun normaliseArch(osArch: String): Arch? = when (osArch.lowercase()) {
        "aarch64", "arm64" -> Arch.ARM64
        "x86_64", "amd64", "x64" -> Arch.X86_64
        else -> null
    }
}
