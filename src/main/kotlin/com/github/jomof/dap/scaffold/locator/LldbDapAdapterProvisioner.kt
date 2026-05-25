package com.github.jomof.dap.scaffold.locator

import java.nio.file.Path

/**
 * Resolve-only provisioner for `lldb-dap`.
 *
 * Unlike CodeLLDB, lldb-dap has no canonical "drop-in" download — it ships
 * with the LLVM project and we never want to mirror it ourselves. The
 * provisioner therefore delegates straight to [LldbDapLocator] and turns
 * a missing install into an actionable error rather than reaching for
 * the network.
 */
object LldbDapAdapterProvisioner : DapAdapterProvisioner {

    override val displayName: String = "lldb-dap"

    override fun resolve(): Path? = LldbDapLocator.resolve()

    override fun provision(): Path = resolve() ?: throw AdapterUnavailableException(
        "lldb-dap not found. Set DAP_LLDB_DAP, drop the binary in " +
            "~/projects/lldb/bin/lldb-dap, or put it on PATH.",
    )
}
