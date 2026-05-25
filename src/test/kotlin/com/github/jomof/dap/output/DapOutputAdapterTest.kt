package com.github.jomof.dap.output

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import org.eclipse.lsp4j.debug.OutputEventArguments
import javax.swing.JComponent

/**
 * Verifies that [DapOutputAdapter] correctly routes DAP `output` events to
 * the IntelliJ console — including respecting the [DapOutputAdapter.RUN_MODE_SKIP]
 * filter that suppresses lldb-dap's debug-console banner in Run mode.
 *
 * Uses a hand-rolled `RecordingConsole` test double rather than IntelliJ's
 * real `ConsoleViewImpl` so we don't depend on platform initialisation just
 * to assert against a flat list of (text, type) pairs.
 */
class DapOutputAdapterTest : LightPlatformTestCase() {

    fun `test routes stdout as NORMAL_OUTPUT`() {
        val console = RecordingConsole()
        val adapter = DapOutputAdapter(console)

        adapter.accept(output("hello\n", category = "stdout"))
        flushEdt()

        assertEquals(listOf("hello\n" to ConsoleViewContentType.NORMAL_OUTPUT), console.writes)
    }

    fun `test routes stderr as ERROR_OUTPUT`() {
        val console = RecordingConsole()
        val adapter = DapOutputAdapter(console)

        adapter.accept(output("boom\n", category = "stderr"))
        flushEdt()

        assertEquals(listOf("boom\n" to ConsoleViewContentType.ERROR_OUTPUT), console.writes)
    }

    fun `test routes console category as SYSTEM_OUTPUT by default`() {
        val console = RecordingConsole()
        val adapter = DapOutputAdapter(console)

        adapter.accept(output("To get started …\n", category = "console"))
        flushEdt()

        assertEquals(listOf("To get started …\n" to ConsoleViewContentType.SYSTEM_OUTPUT), console.writes)
    }

    fun `test RUN_MODE_SKIP suppresses console and telemetry but keeps program output`() {
        val console = RecordingConsole()
        val adapter = DapOutputAdapter(console, skipCategories = DapOutputAdapter.RUN_MODE_SKIP)

        adapter.accept(output("To get started with the debug console …\n", category = "console"))
        adapter.accept(output("Attached to process 12345.\n", category = "console"))
        adapter.accept(output("Hello and welcome to C++!\n", category = "stdout"))
        adapter.accept(output("i = 1\n", category = "stdout"))
        adapter.accept(output("boom\n", category = "stderr"))
        adapter.accept(output("usage-metrics\n", category = "telemetry"))
        adapter.accept(output("Process 12345 exited with status = 0.\n", category = "console"))
        flushEdt()

        assertEquals(
            listOf(
                "Hello and welcome to C++!\n" to ConsoleViewContentType.NORMAL_OUTPUT,
                "i = 1\n" to ConsoleViewContentType.NORMAL_OUTPUT,
                "boom\n" to ConsoleViewContentType.ERROR_OUTPUT,
            ),
            console.writes,
        )
    }

    fun `test important category is always passed through even with RUN_MODE_SKIP`() {
        val console = RecordingConsole()
        val adapter = DapOutputAdapter(console, skipCategories = DapOutputAdapter.RUN_MODE_SKIP)

        adapter.accept(output("adapter must surface this\n", category = "important"))
        flushEdt()

        assertEquals(
            listOf("adapter must surface this\n" to ConsoleViewContentType.SYSTEM_OUTPUT),
            console.writes,
        )
    }

    fun `test null category falls through to SYSTEM_OUTPUT and is not skipped`() {
        val console = RecordingConsole()
        val adapter = DapOutputAdapter(console, skipCategories = DapOutputAdapter.RUN_MODE_SKIP)

        adapter.accept(output("uncategorised\n", category = null))
        flushEdt()

        assertEquals(listOf("uncategorised\n" to ConsoleViewContentType.SYSTEM_OUTPUT), console.writes)
    }

    private fun output(text: String, category: String?): OutputEventArguments =
        OutputEventArguments().apply {
            this.output = text
            this.category = category
        }

    /**
     * Drain the EDT so `invokeLater`-dispatched console writes land before
     * we assert. `Application.invokeAndWait` would be a no-op here because
     * LightPlatformTestCase runs the test body ON the EDT.
     */
    private fun flushEdt() {
        UIUtil.dispatchAllInvocationEvents()
    }
}

/**
 * Minimal [ConsoleView] test double that records every [print] in order
 * with the requested [ConsoleViewContentType]. Everything else is a no-op;
 * the production adapter only ever calls [print].
 */
private class RecordingConsole : ConsoleView, UserDataHolder by UserDataHolderBase() {
    val writes: MutableList<Pair<String, ConsoleViewContentType>> = mutableListOf()

    override fun print(text: String, contentType: ConsoleViewContentType) {
        writes.add(text to contentType)
    }

    override fun clear() = writes.clear()
    override fun scrollTo(offset: Int) {}
    override fun attachToProcess(processHandler: com.intellij.execution.process.ProcessHandler) {}
    override fun setOutputPaused(value: Boolean) {}
    override fun isOutputPaused(): Boolean = false
    override fun hasDeferredOutput(): Boolean = false
    override fun performWhenNoDeferredOutput(runnable: Runnable) = runnable.run()
    override fun setHelpId(helpId: String) {}
    override fun addMessageFilter(filter: com.intellij.execution.filters.Filter) {}
    override fun printHyperlink(hyperlinkText: String, info: com.intellij.execution.filters.HyperlinkInfo?) {}
    override fun getContentSize(): Int = writes.sumOf { it.first.length }
    override fun canPause(): Boolean = false
    override fun getComponent(): JComponent = throw UnsupportedOperationException()
    override fun getPreferredFocusableComponent(): JComponent = throw UnsupportedOperationException()
    override fun dispose() {}
    override fun createConsoleActions(): Array<com.intellij.openapi.actionSystem.AnAction> = emptyArray()
    override fun allowHeavyFilters() {}
}
