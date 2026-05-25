package com.github.jomof.dap.scaffold.language

import com.github.jomof.dap.language.DapLanguageProfile
import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile

/**
 * Phase 1 [DapLanguageProfile] for C/C++ sessions debugged via lldb-dap.
 *
 * The evaluator language is looked up reflectively so the plugin builds
 * cleanly against the bare IntelliJ Platform yet picks up CLion's
 * `OCLanguage` when running inside CLion. If neither is available we fall
 * back to plain text — evaluator works, the editor just lacks syntax
 * highlighting.
 */
object CppLanguageProfile : DapLanguageProfile {

    override val id: String = "cpp"
    override val displayName: String = "C/C++"

    private val extensions: Set<String> = setOf(
        "c", "cpp", "cc", "cxx", "c++",
        "h", "hpp", "hxx", "hh", "h++",
        "m", "mm",
    )

    override fun isDebuggable(file: VirtualFile): Boolean =
        !file.isDirectory && extensions.contains(file.extension?.lowercase())

    override fun evaluatorLanguage(): Language? {
        for (id in CANDIDATE_LANGUAGE_IDS) {
            Language.findLanguageByID(id)?.let { return it }
        }
        return null
    }

    /**
     * lldb-dap (and most C/C++ adapters) expose these two filter IDs.
     * `cpp_throw` breaks on every thrown exception; `cpp_catch` breaks on
     * caught exceptions. The user can override via setExceptionBreakpoints
     * once we ship a UI for it.
     */
    override fun defaultExceptionFilters(): List<String> = listOf("cpp_throw")

    private val CANDIDATE_LANGUAGE_IDS = listOf("ObjectiveC", "C++", "C", "CPP")
}
