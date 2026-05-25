package com.github.jomof.dap.output

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import org.eclipse.lsp4j.debug.OutputEventArguments

/**
 * Translates DAP `output` events into IntelliJ console output, mapping the
 * DAP category to the closest [ConsoleViewContentType] so IntelliJ paints
 * stdout/stderr/console-meta lines in their conventional colours.
 *
 * Callers can pass [skipCategories] to discard events whose category is
 * uninteresting in the current context. Run mode, for example, uses
 * [RUN_MODE_SKIP] to suppress lldb-dap's debug-console banner ("To get
 * started with the debug console …", "Attached to process N.",
 * "Process N exited with status = 0.") and telemetry, leaving only the
 * debuggee's actual stdout/stderr (and `important` adapter notices) in
 * the Run tool window.
 *
 * All console writes are dispatched on the EDT — IntelliJ's console
 * implementations require it.
 */
class DapOutputAdapter(
    private val console: ConsoleView,
    private val skipCategories: Set<String> = emptySet(),
) {

    fun accept(event: OutputEventArguments) {
        val text = event.output ?: return
        val category = event.category
        if (category != null && category in skipCategories) return
        val type = contentTypeFor(category)
        ApplicationManager.getApplication().invokeLater {
            console.print(text, type)
        }
    }

    private fun contentTypeFor(category: String?): ConsoleViewContentType = when (category) {
        "stderr" -> ConsoleViewContentType.ERROR_OUTPUT
        "console", "important", null -> ConsoleViewContentType.SYSTEM_OUTPUT
        "telemetry" -> ConsoleViewContentType.LOG_INFO_OUTPUT
        else -> ConsoleViewContentType.NORMAL_OUTPUT
    }

    companion object {
        /**
         * Adapter-side categories that aren't program output and would otherwise
         * pollute a `noDebug=true` Run console with debug-console chatter.
         */
        val RUN_MODE_SKIP: Set<String> = setOf("console", "telemetry")
    }
}
