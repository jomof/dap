package com.github.jomof.dap.language

import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile

/**
 * Encapsulates the small surface of language-specific behaviour that the
 * otherwise language-agnostic production DAP layer needs from its host.
 *
 * Concrete profiles live in the scaffold package for the languages we currently
 * support (C/C++, Rust, Go); downstream codebases that transplant this layer
 * supply their own profiles by simply implementing this interface.
 *
 * The DAP protocol itself is source-language-agnostic — `Source`, `StackFrame`,
 * `Variable`, etc. don't depend on whether the program was written in C++,
 * Rust, or Go — so the surface here is intentionally narrow.
 */
interface DapLanguageProfile {
    /** Stable identifier, e.g. `"cpp"`, `"rust"`, `"go"`. */
    val id: String

    /** Human-readable name, shown in run-config UI and similar. */
    val displayName: String

    /**
     * Whether [file] can host a DAP line breakpoint and is otherwise
     * "debuggable" by this profile. Used by per-language XLineBreakpointType
     * `canPutAt` implementations. Should be cheap to evaluate.
     */
    fun isDebuggable(file: VirtualFile): Boolean

    /**
     * The IntelliJ [Language] used for editor instances inside the Evaluate
     * dialog, watches, and hover popups. May return `null` to fall back to a
     * plain-text editor; profiles for languages whose IntelliJ plugin may or
     * may not be installed should look the language up reflectively and return
     * `null` if it is absent.
     */
    fun evaluatorLanguage(): Language? = null

    /**
     * Adapter-neutral defaults merged into every `launch` request before any
     * caller-supplied overrides. For example, the C/C++ profile contributes
     * nothing here, while a Go profile would add `mode: "debug"` and
     * `substitutePath` defaults.
     */
    fun defaultLaunchArgs(): Map<String, Any?> = emptyMap()

    /**
     * Default exception-breakpoint filter IDs the profile suggests enabling
     * out of the box. Populated by Phase 4 when exception breakpoints land.
     */
    fun defaultExceptionFilters(): List<String> = emptyList()
}
