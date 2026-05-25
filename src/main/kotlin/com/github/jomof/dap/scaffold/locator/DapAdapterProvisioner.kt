package com.github.jomof.dap.scaffold.locator

import java.nio.file.Path

/**
 * Locates an installed DAP adapter executable on disk and, if the adapter
 * has a network-provisionable distribution, downloads it on demand.
 *
 * The contract is intentionally a two-step affair so callers can decide
 * how aggressive to be:
 *  - [resolve] is **cheap and offline**: it only consults already-installed
 *    paths (env override, well-known IDE/extension dirs, `PATH`) and
 *    never reaches the network. Useful from the IDE settings UI where we
 *    want to surface "found / not found" without blocking on HTTP.
 *  - [provision] returns the same answer when an install is present but
 *    falls back to a download when nothing local matches. Callers that
 *    accept the side effect (run-config startup, integration tests)
 *    use this entry point.
 *
 * The split keeps `lldb-dap` (resolve-only — there is no upstream
 * tarball we trust to download) and `CodeLLDB` (resolve-or-download from
 * the same GitHub releases that lsp4ij uses) under one umbrella without
 * sneaking network calls into [resolve].
 */
interface DapAdapterProvisioner {

    /** Human-readable adapter name, used in error messages. */
    val displayName: String

    /**
     * Locate an already-installed adapter binary. Returns `null` when
     * nothing matches — never throws, never blocks on the network.
     */
    fun resolve(): Path?

    /**
     * Locate or fetch an adapter binary. Implementations that don't
     * support fetching simply delegate to [resolve] and throw
     * [AdapterUnavailableException] when it returns `null`.
     */
    @Throws(AdapterUnavailableException::class)
    fun provision(): Path
}

/**
 * Thrown by [DapAdapterProvisioner.provision] when no install can be
 * found and no download path is available. Carries an actionable
 * message that callers (e.g. the run-config) propagate verbatim.
 */
class AdapterUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
