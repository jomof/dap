package com.github.jomof.dap.reverse

import org.eclipse.lsp4j.debug.RunInTerminalRequestArguments
import org.eclipse.lsp4j.debug.RunInTerminalResponse

/**
 * Resolves the DAP `runInTerminal` reverse request. The adapter asks the IDE
 * (rather than starting the debuggee itself) so the IDE can spawn the process
 * in a user-visible terminal that captures stdout/stderr/input.
 *
 * Hosts typically supply an implementation that delegates to a real terminal
 * tool window. [ProcessBuilderRunInTerminalHandler] provides a usable
 * default that simply forks the process — adequate for non-interactive
 * debuggees and for environments where no terminal plugin is available.
 */
fun interface DapRunInTerminalHandler {
    suspend fun handle(args: RunInTerminalRequestArguments): RunInTerminalResponse
}

/**
 * Default headless handler: starts the process detached from the IDE so the
 * adapter can attach to it. The process's stdio is inherited from the JVM,
 * which surfaces it in the IDE log; for richer UX, hosts should swap in a
 * terminal-tool-window implementation.
 */
class ProcessBuilderRunInTerminalHandler : DapRunInTerminalHandler {
    override suspend fun handle(args: RunInTerminalRequestArguments): RunInTerminalResponse {
        val builder = ProcessBuilder(args.args?.toList().orEmpty())
        args.cwd?.takeIf { it.isNotBlank() }?.let { builder.directory(java.io.File(it)) }
        args.env?.forEach { (key, value) ->
            if (value == null) builder.environment().remove(key) else builder.environment()[key] = value
        }
        builder.inheritIO()
        val process = builder.start()
        return RunInTerminalResponse().apply { processId = process.pid().toInt() }
    }
}
