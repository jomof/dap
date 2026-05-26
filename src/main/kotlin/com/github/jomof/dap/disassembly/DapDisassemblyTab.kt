package com.github.jomof.dap.disassembly

import com.github.jomof.dap.client.DapCapabilities
import com.github.jomof.dap.frames.DapStackFrame
import com.github.jomof.dap.session.DapSessionScope
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.xdebugger.XDebugSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Minimal disassembly tab added to the IntelliJ debugger layout.
 *
 * Shows the disassembled instructions for the *current frame*'s
 * `instructionPointerReference`; a Refresh action re-issues the
 * `disassemble` request, e.g. after a step. The renderer is a read-only
 * editor showing one instruction per line.
 *
 * Text-only on purpose — a richer renderer with column alignment,
 * address labels and PC tracking can be slotted in later without touching
 * the data layer ([DapDisassemblyProvider]).
 */
class DapDisassemblyTab(
    private val project: Project,
    private val session: XDebugSession,
    private val sessionScope: DapSessionScope,
    private val provider: DapDisassemblyProvider,
    private val capabilitiesProvider: () -> DapCapabilities?,
) {
    private val log = logger<DapDisassemblyTab>()

    private val editor: EditorEx = run {
        val factory = EditorFactory.getInstance()
        val doc = factory.createDocument("")
        (factory.createEditor(doc, project, PlainTextFileType.INSTANCE, true) as EditorEx).apply {
            settings.isLineNumbersShown = false
            settings.isFoldingOutlineShown = false
            settings.isLineMarkerAreaShown = false
            settings.isIndentGuidesShown = false
            setHorizontalScrollbarVisible(true)
            setVerticalScrollbarVisible(true)
        }
    }

    private val panel: JComponent = JPanel(BorderLayout()).apply {
        add(buildToolbar().component, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)
    }

    fun register(ui: RunnerLayoutUi): Content {
        val content = ui.createContent(
            CONTENT_ID,
            panel,
            "Disassembly",
            AllIcons.Toolwindows.ToolWindowDebugger,
            panel,
        ).apply {
            isCloseable = false
        }
        Disposer.register(content) { EditorFactory.getInstance().releaseEditor(editor) }
        ui.addContent(content)
        return content
    }

    /** Refresh the panel from the session's current frame. */
    fun refresh() {
        // Live capability check: the tab is registered eagerly during
        // session-tab construction (before `initialize` returns), so the
        // only safe time to ask whether the adapter supports `disassemble`
        // is at the point the user actually wants data.
        val caps = capabilitiesProvider()
        if (caps != null && !caps.supportsDisassembleRequest) {
            replaceText("// Adapter does not support the DAP `disassemble` request.")
            return
        }
        val frame = session.currentStackFrame as? DapStackFrame ?: run {
            replaceText("// No DAP frame currently selected.")
            return
        }
        val ref = frame.raw.instructionPointerReference
        if (ref.isNullOrBlank()) {
            replaceText("// Adapter did not provide an instruction pointer for this frame.")
            return
        }
        replaceText("// Disassembling at $ref ...")
        sessionScope.scope.launch {
            try {
                val instructions = provider.disassemble(ref)
                val rendered = instructions.joinToString("\n") { inst ->
                    val addr = inst.address ?: ""
                    val bytes = inst.instructionBytes.orEmpty()
                    val mnemonic = inst.instruction.orEmpty()
                    val symbol = inst.symbol?.let { " ; $it" }.orEmpty()
                    "%-18s  %-24s  %s%s".format(addr, bytes, mnemonic, symbol)
                }
                ApplicationManager.getApplication().invokeLater { replaceText(rendered) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.debug("disassemble at $ref failed", throwable)
                ApplicationManager.getApplication().invokeLater {
                    replaceText("// Disassemble failed: ${throwable.message}")
                }
            }
        }
    }

    private fun replaceText(text: String) {
        ApplicationManager.getApplication().runWriteAction {
            editor.document.setText(text)
        }
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Re-fetch disassembly for the current frame", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
        }
        return ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.DEBUGGER_TOOLBAR, group, true)
            .also { it.targetComponent = panel }
    }

    companion object {
        const val CONTENT_ID: String = "dap-disassembly"
    }
}
