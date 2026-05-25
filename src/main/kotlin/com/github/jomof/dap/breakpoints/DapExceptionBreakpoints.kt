package com.github.jomof.dap.breakpoints

import com.github.jomof.dap.client.DapCapabilities
import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.language.DapLanguageProfile
import com.intellij.openapi.diagnostic.logger
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments

/**
 * Applies the language profile's default exception-breakpoint filters to the
 * adapter once at session start. The filter identifiers themselves are
 * adapter-defined (advertised via [DapCapabilities.raw.exceptionBreakpointFilters]);
 * the profile contributes only the *defaults* that match the developer's
 * usual expectations (e.g. "break on uncaught Rust panic", "break on C++
 * throw").
 *
 * A future Phase will add a user-facing settings panel that overrides the
 * defaults; until then this is one-shot.
 */
object DapExceptionBreakpoints {

    private val log = logger<DapExceptionBreakpoints>()

    suspend fun applyDefaults(
        profile: DapLanguageProfile,
        capabilities: DapCapabilities?,
        client: DapClient,
    ) {
        val available = capabilities?.raw?.exceptionBreakpointFilters?.mapNotNull { it.filter }?.toSet().orEmpty()
        if (available.isEmpty()) return
        val desired = profile.defaultExceptionFilters().filter { it in available }
        if (desired.isEmpty()) return
        try {
            client.setExceptionBreakpoints(
                SetExceptionBreakpointsArguments().apply {
                    filters = desired.toTypedArray()
                }
            )
        } catch (throwable: Throwable) {
            log.warn("Failed to apply default exception filters $desired", throwable)
        }
    }
}
