package com.github.jomof.dap.session

/**
 * Describes the DAP request (and its JSON arguments) that should be sent after
 * the `initialize` handshake completes. DAP defines two such start-up requests
 * — `launch` (the adapter starts the target) and `attach` (the adapter
 * connects to an already-running target) — and their argument shape is
 * adapter-specific opaque JSON, so we model the payload as a plain map.
 */
sealed interface DapLaunchSpec {
    val args: Map<String, Any?>

    class Launch(override val args: Map<String, Any?>) : DapLaunchSpec
    class Attach(override val args: Map<String, Any?>) : DapLaunchSpec
}
