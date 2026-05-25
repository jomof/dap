package com.github.jomof.dap.scaffold.language

import com.github.jomof.dap.language.DapLanguageProfile
import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile

/**
 * Phase 5 [DapLanguageProfile] for Go sessions debugged via delve-dap.
 *
 * The evaluator language defers to the GoLand / Go-plugin `Language` when
 * present (probed reflectively via [Language.findLanguageByID]) and falls
 * back to plain text otherwise — delve-dap accepts Go expressions in any
 * case, the language tag only controls the editor's syntax colouring.
 */
object GoLanguageProfile : DapLanguageProfile {

    override val id: String = "go"
    override val displayName: String = "Go"

    private val extensions: Set<String> = setOf("go")

    override fun isDebuggable(file: VirtualFile): Boolean =
        !file.isDirectory && extensions.contains(file.extension?.lowercase())

    override fun evaluatorLanguage(): Language? {
        for (id in CANDIDATE_LANGUAGE_IDS) {
            Language.findLanguageByID(id)?.let { return it }
        }
        return null
    }

    /**
     * Delve uses the standard DAP exception filter id `panic` for runtime
     * panics. `fatalError` is exposed by newer dlv releases for unrecoverable
     * runtime errors (stack overflow, deadlock detection, …).
     */
    override fun defaultExceptionFilters(): List<String> = listOf("panic", "fatalError")

    /**
     * Delve expects a `mode` field on its `launch` request. "debug" tells
     * delve to compile and run the workspace package; the runner overrides
     * to "exec" when the user points at a pre-built binary.
     */
    override fun defaultLaunchArgs(): Map<String, Any?> = mapOf(
        "mode" to "debug",
        "stopOnEntry" to false,
    )

    private val CANDIDATE_LANGUAGE_IDS = listOf("go", "Go", "GO")
}
