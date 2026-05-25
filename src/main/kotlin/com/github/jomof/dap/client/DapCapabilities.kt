package com.github.jomof.dap.client

import org.eclipse.lsp4j.debug.Capabilities

/**
 * Read-only wrapper around the DAP server [Capabilities] block returned by the
 * `initialize` request. Translates the protocol's many nullable Booleans into
 * non-null Kotlin properties, defaulting absent flags to `false` per the spec.
 *
 * Only the capabilities the plugin actually inspects are surfaced here; access
 * the original payload via [raw] for anything else.
 */
class DapCapabilities(val raw: Capabilities) {
    val supportsConfigurationDoneRequest: Boolean get() = raw.supportsConfigurationDoneRequest == true
    val supportsFunctionBreakpoints: Boolean get() = raw.supportsFunctionBreakpoints == true
    val supportsConditionalBreakpoints: Boolean get() = raw.supportsConditionalBreakpoints == true
    val supportsHitConditionalBreakpoints: Boolean get() = raw.supportsHitConditionalBreakpoints == true
    val supportsLogPoints: Boolean get() = raw.supportsLogPoints == true
    val supportsSetVariable: Boolean get() = raw.supportsSetVariable == true
    val supportsEvaluateForHovers: Boolean get() = raw.supportsEvaluateForHovers == true
    val supportsTerminateRequest: Boolean get() = raw.supportsTerminateRequest == true
    val supportsExceptionFilterOptions: Boolean get() = raw.supportsExceptionFilterOptions == true
    val supportsStepInTargetsRequest: Boolean get() = raw.supportsStepInTargetsRequest == true
    val supportsDisassembleRequest: Boolean get() = raw.supportsDisassembleRequest == true
    val supportsRestartFrame: Boolean get() = raw.supportsRestartFrame == true
}
