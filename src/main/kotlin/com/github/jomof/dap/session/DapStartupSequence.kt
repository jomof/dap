package com.github.jomof.dap.session

import com.github.jomof.dap.breakpoints.DapExceptionBreakpoints
import com.github.jomof.dap.client.DapCapabilities
import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.language.DapLanguageProfile

/**
 * Protocol-critical tail of the DAP startup handshake. Everything that arms
 * breakpoints must happen before `configurationDone`, because adapters are free
 * to resume the debuggee as soon as they receive that request.
 */
internal object DapStartupSequence {
    suspend fun configureBeforeResume(
        profile: DapLanguageProfile,
        capabilities: DapCapabilities,
        client: DapClient,
        flushLineBreakpoints: suspend () -> Unit,
    ) {
        flushLineBreakpoints()
        DapExceptionBreakpoints.applyDefaults(profile, capabilities, client)
        if (capabilities.supportsConfigurationDoneRequest) {
            client.configurationDone()
        }
    }
}
