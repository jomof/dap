package com.github.jomof.dap.client

import org.eclipse.lsp4j.debug.Capabilities

/**
 * Read-only wrapper around the DAP server [Capabilities] block returned by the
 * `initialize` request. Translates the protocol's nullable Booleans into
 * non-null Kotlin properties, defaulting absent flags to `false` per the spec.
 *
 * Only capabilities the plugin currently gates behaviour on are surfaced here.
 * If a new feature needs another flag, add the wrapper property at the same
 * time as the call site — don't pre-mirror the full spec. The original payload
 * is accessible via [raw] for any one-off probes.
 */
class DapCapabilities(val raw: Capabilities) {
    val supportsConfigurationDoneRequest: Boolean get() = raw.supportsConfigurationDoneRequest == true
    val supportsConditionalBreakpoints: Boolean get() = raw.supportsConditionalBreakpoints == true
    val supportsLogPoints: Boolean get() = raw.supportsLogPoints == true
    val supportsTerminateRequest: Boolean get() = raw.supportsTerminateRequest == true
    val supportsStepInTargetsRequest: Boolean get() = raw.supportsStepInTargetsRequest == true
    val supportsDisassembleRequest: Boolean get() = raw.supportsDisassembleRequest == true
    val supportsRestartFrame: Boolean get() = raw.supportsRestartFrame == true
}
