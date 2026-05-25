package com.github.jomof.dap.scaffold.run

/**
 * Identifies which DAP server binary the scaffold run-config should launch.
 *
 * Distinct from a [com.github.jomof.dap.language.DapLanguageProfile] because
 * one language can be debugged by several adapters (e.g. Rust by both
 * lldb-dap and CodeLLDB) and one adapter can serve several languages (e.g.
 * lldb-dap covers C, C++, Objective-C, Rust, Swift).
 */
enum class DapAdapterKind {
    LLDB_DAP,
    CODE_LLDB,
    DELVE_DAP,
}
