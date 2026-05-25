package com.github.jomof.dap.scaffold.language

import com.github.jomof.dap.language.DapLanguageProfile
import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile

/**
 * Phase 2 [DapLanguageProfile] for Rust sessions debugged via lldb-dap or
 * CodeLLDB. Uses reflective IntelliJ-`Language` lookup so the plugin works
 * whether or not the Rust IDE plugin is installed.
 */
object RustLanguageProfile : DapLanguageProfile {

    override val id: String = "rust"
    override val displayName: String = "Rust"

    private val extensions: Set<String> = setOf("rs")

    override fun isDebuggable(file: VirtualFile): Boolean =
        !file.isDirectory && extensions.contains(file.extension?.lowercase())

    override fun evaluatorLanguage(): Language? {
        for (id in CANDIDATE_LANGUAGE_IDS) {
            Language.findLanguageByID(id)?.let { return it }
        }
        return null
    }

    /** lldb-dap on Rust binaries surfaces `rust_panic`; CodeLLDB matches the same name. */
    override fun defaultExceptionFilters(): List<String> = listOf("rust_panic")

    private val CANDIDATE_LANGUAGE_IDS = listOf("Rust", "RUST")
}
