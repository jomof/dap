package com.github.jomof.dap.scaffold.locator

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Scaffold-grade resolver for the `lldb-dap` binary.
 *
 * Resolution order:
 *  1. The `DAP_LLDB_DAP` environment variable, if set and pointing at an
 *     executable file.
 *  2. The known development build at `~/projects/lldb/bin/lldb-dap`
 *     (configured per the project brief).
 *  3. The first `lldb-dap` found on `PATH`.
 *
 * Quick-and-dirty as agreed in the plan; the production layer never invokes
 * this — only the scaffold run-config does.
 */
object LldbDapLocator {

    private const val ENV_OVERRIDE = "DAP_LLDB_DAP"
    private const val BINARY_NAME = "lldb-dap"

    fun resolve(): Path? {
        System.getenv(ENV_OVERRIDE)?.let { override ->
            val path = Paths.get(override)
            if (Files.isExecutable(path)) return path
        }
        val devBuild = Paths.get(System.getProperty("user.home"), "projects", "lldb", "bin", BINARY_NAME)
        if (Files.isExecutable(devBuild)) return devBuild
        return searchPath(BINARY_NAME)
    }

    private fun searchPath(name: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(java.io.File.pathSeparatorChar)
            .map { Paths.get(it, name) }
            .firstOrNull { Files.isExecutable(it) }
    }
}
