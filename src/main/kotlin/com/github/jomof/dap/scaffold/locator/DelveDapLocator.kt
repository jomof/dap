package com.github.jomof.dap.scaffold.locator

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Scaffold-grade resolver for the `dlv` Go debugger binary used in
 * `dlv dap --listen=...` mode.
 *
 * Resolution order:
 *  1. The `DAP_DELVE` environment variable, if set and pointing at an
 *     executable file.
 *  2. The first `dlv` found on `PATH`.
 */
object DelveDapLocator {

    private const val ENV_OVERRIDE = "DAP_DELVE"
    private const val BINARY_NAME = "dlv"

    fun resolve(): Path? {
        System.getenv(ENV_OVERRIDE)?.let { override ->
            val path = Paths.get(override)
            if (Files.isExecutable(path)) return path
        }
        return searchPath(BINARY_NAME)
    }

    private fun searchPath(name: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(File.pathSeparatorChar)
            .map { Paths.get(it, name) }
            .firstOrNull { Files.isExecutable(it) }
    }
}
