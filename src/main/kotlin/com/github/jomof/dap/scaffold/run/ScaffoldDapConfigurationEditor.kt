package com.github.jomof.dap.scaffold.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Minimal FormBuilder editor: target program, args, cwd, env (one per line:
 * KEY=VALUE), launch-JSON overrides (free-form JSON snippet). Adapter and
 * language profile are baked in by the factory and not user-editable in
 * Phase 1 — they become editable when Phase 2 introduces a Rust adapter
 * selector.
 */
class ScaffoldDapConfigurationEditor : SettingsEditor<ScaffoldDapRunConfiguration>() {

    private val programField = TextFieldWithBrowseButton().also { field ->
        field.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select Program")
                .withDescription("Choose the executable to debug"),
        )
    }
    private val argsField = JBTextField()
    private val cwdField = TextFieldWithBrowseButton().also { field ->
        field.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Working Directory")
                .withDescription("Choose the working directory for the launched program"),
        )
    }
    private val envArea = JBTextArea(4, 40)
    private val launchJsonArea = JBTextArea(6, 40)
    private val adapterBox: JComboBox<DapAdapterKind> = JComboBox(DapAdapterKind.values())

    override fun createEditor(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Adapter:"), adapterBox)
            .addLabeledComponent(JBLabel("Program:"), programField)
            .addLabeledComponent(JBLabel("Arguments:"), argsField)
            .addLabeledComponent(JBLabel("Working directory:"), cwdField)
            .addLabeledComponent(JBLabel("Environment (KEY=VALUE per line):"), envArea)
            .addLabeledComponent(JBLabel("Launch JSON overrides:"), launchJsonArea)
            .panel

    override fun resetEditorFrom(config: ScaffoldDapRunConfiguration) {
        adapterBox.selectedItem = config.adapterKind
        programField.text = config.program
        argsField.text = config.programArgs
        cwdField.text = config.workingDir
        envArea.text = config.environment.entries.joinToString(separator = "\n") { (k, v) -> "$k=$v" }
        launchJsonArea.text = config.launchJsonOverrides
    }

    override fun applyEditorTo(config: ScaffoldDapRunConfiguration) {
        (adapterBox.selectedItem as? DapAdapterKind)?.let { config.adapterKind = it }
        config.program = programField.text.trim()
        config.programArgs = argsField.text.trim()
        config.workingDir = cwdField.text.trim()
        config.environment = envArea.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }
        config.launchJsonOverrides = launchJsonArea.text.trim()
    }
}
