package com.github.jomof.dap.scaffold.locator

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Provisioner for the CodeLLDB adapter. Combines the two halves of the
 * problem:
 *  1. [resolve] looks for an already-installed copy in any of the
 *     well-known places — env override, our own download cache,
 *     legacy read-only caches, and the VS Code extension dir —
 *     without ever touching the network.
 *  2. [provision] does the same and, on miss, hands control to
 *     [CodeLldbDownloader] to fetch the release matching the host's
 *     OS/arch.
 *
 * Symmetric with [LldbDapAdapterProvisioner] but with the download
 * path enabled.
 */
object CodeLldbAdapterProvisioner : DapAdapterProvisioner {

    private const val ENV_OVERRIDE = "DAP_CODELLDB"
    private const val BINARY_NAME = "codelldb"

    override val displayName: String = "CodeLLDB"

    override fun resolve(): Path? {
        envOverride()?.let { return it }
        downloaderCache()?.let { return it }
        legacyDownloaderCache()?.let { return it }
        lsp4ijCache()?.let { return it }
        vscodeExtensionCache()?.let { return it }
        return searchPath(BINARY_NAME)
    }

    override fun provision(): Path {
        resolve()?.let { return it }
        return try {
            CodeLldbDownloader.ensureInstalled()
        } catch (failure: IOException) {
            throw AdapterUnavailableException(
                "Failed to download CodeLLDB: ${failure.message}. " +
                    "Set DAP_CODELLDB to a hand-installed binary as a workaround.",
                failure,
            )
        }
    }

    /**
     * Path of `liblldb` to pass to the adapter via `--liblldb`. We need
     * this because the CodeLLDB adapter binary doesn't auto-discover
     * libraries from its install root in every case — passing it
     * explicitly is the lsp4ij/VSCode-compatible recipe.
     *
     * Returns `null` when the adapter at [adapter] doesn't live inside
     * an extension-style layout (e.g. a user-supplied `DAP_CODELLDB`
     * pointing at a hand-built binary somewhere else). The caller may
     * still attempt to spawn it, and the adapter will surface its own
     * error if liblldb can't be found.
     */
    fun liblldbFor(adapter: Path): Path? {
        // Walk up from `<root>/extension/adapter/codelldb` (lsp4ij layout)
        // or `<root>/adapter/codelldb` (VS Code-extracted layout) to
        // find the lldb library that ships beside it.
        val parents = generateSequence(adapter.parent) { it.parent }.take(5).toList()
        for (parent in parents) {
            for (relative in LIBLLDB_RELATIVES) {
                val candidate = parent.resolve(relative)
                if (Files.exists(candidate)) return candidate
            }
        }
        return null
    }

    private fun envOverride(): Path? {
        val override = System.getenv(ENV_OVERRIDE) ?: return null
        val path = Paths.get(override)
        return if (Files.isExecutable(path)) path else null
    }

    private fun downloaderCache(): Path? = CodeLldbDownloader.existingInstall()

    private fun legacyDownloaderCache(): Path? {
        val root = Paths.get(System.getProperty("user.home"), ".cache", "dap-intellij", "codelldb")
        return CodeLldbDownloaderImpl(cacheRoot = root).existingInstall()
    }

    /**
     * `~/.lsp4ij/dap/codelldb/extension/adapter/codelldb`. We don't write
     * here — but if the user already has lsp4ij installed, reusing its
     * download avoids a second copy and keeps offline upgrades working.
     */
    private fun lsp4ijCache(): Path? {
        val root = Paths.get(System.getProperty("user.home"), ".lsp4ij", "dap", "codelldb")
        if (!Files.isDirectory(root)) return null
        val candidate = root.resolve(CodeLldbAssetCatalog.adapterPath(System.getProperty("os.name") ?: ""))
        return if (isCompleteExtensionAdapter(candidate)) candidate else null
    }

    /**
     * `~/.vscode/extensions/vadimcn.vscode-lldb-<version>/adapter/codelldb`
     * for users who installed CodeLLDB via the VS Code extension. The
     * extension dir is *flat* (the `extension/` prefix is stripped when
     * VS Code unpacks the VSIX), which is why we resolve `adapter/...`
     * directly instead of `extension/adapter/...`.
     */
    private fun vscodeExtensionCache(): Path? {
        val home = Paths.get(System.getProperty("user.home"), ".vscode", "extensions").toFile()
        if (!home.isDirectory) return null
        return home.listFiles { file -> file.isDirectory && file.name.startsWith("vadimcn.vscode-lldb-") }
            ?.sortedByDescending(java.io.File::getName)
            ?.firstNotNullOfOrNull { ext ->
                val candidate = ext.toPath().resolve("adapter").resolve(BINARY_NAME)
                if (isCompleteExtensionAdapter(candidate)) candidate else null
            }
    }

    private fun isCompleteExtensionAdapter(candidate: Path): Boolean =
        Files.isExecutable(candidate) && liblldbFor(candidate)?.let { Files.isRegularFile(it) } == true

    private fun searchPath(name: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(java.io.File.pathSeparatorChar)
            .map { Paths.get(it, name) }
            .firstOrNull { Files.isExecutable(it) }
    }

    private val LIBLLDB_RELATIVES = listOf(
        "lldb/lib/liblldb.dylib",
        "lldb/lib/liblldb.so",
        "lldb/bin/liblldb.dll",
    )
}
