package com.github.jomof.dap.scaffold.run

import com.github.jomof.dap.scaffold.language.CppLanguageProfile
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element

/**
 * Persisted run-config for one scaffold DAP session.
 *
 * Fields are intentionally minimal — this is scaffolding the plan calls out
 * as "expeditious" — but cover everything lldb-dap needs for a simple
 * launch: target executable, arguments, working directory, environment.
 *
 * `profileId` and `adapterKind` are baked in by the factory but persisted
 * (so older configs stay valid if the factory list shifts later).
 */
class ScaffoldDapRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<Any?>(project, factory, name) {

    var profileId: String = CppLanguageProfile.id
    var adapterKind: DapAdapterKind = DapAdapterKind.LLDB_DAP

    var program: String = ""
    var programArgs: String = ""
    var workingDir: String = project.basePath.orEmpty()
    var environment: Map<String, String> = emptyMap()
    /** Free-form JSON merged into the DAP `launch` request; overrides defaults. */
    var launchJsonOverrides: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        ScaffoldDapConfigurationEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): ScaffoldDapCommandLineState =
        ScaffoldDapCommandLineState(environment, this)

    override fun checkConfiguration() {
        if (program.isBlank()) {
            throw RuntimeConfigurationException("A target program must be specified.")
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, FIELD_PROFILE_ID, profileId)
        JDOMExternalizerUtil.writeField(element, FIELD_ADAPTER, adapterKind.name)
        JDOMExternalizerUtil.writeField(element, FIELD_PROGRAM, program)
        JDOMExternalizerUtil.writeField(element, FIELD_PROGRAM_ARGS, programArgs)
        JDOMExternalizerUtil.writeField(element, FIELD_CWD, workingDir)
        JDOMExternalizerUtil.writeField(element, FIELD_LAUNCH_JSON, launchJsonOverrides)
        if (environment.isNotEmpty()) {
            val envElement = Element(ELEMENT_ENV)
            environment.forEach { (k, v) ->
                envElement.addContent(Element("var").apply {
                    setAttribute("key", k)
                    setAttribute("value", v)
                })
            }
            element.addContent(envElement)
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        profileId = JDOMExternalizerUtil.readField(element, FIELD_PROFILE_ID, profileId)
        adapterKind = runCatching {
            DapAdapterKind.valueOf(JDOMExternalizerUtil.readField(element, FIELD_ADAPTER, adapterKind.name))
        }.getOrDefault(adapterKind)
        program = JDOMExternalizerUtil.readField(element, FIELD_PROGRAM, program)
        programArgs = JDOMExternalizerUtil.readField(element, FIELD_PROGRAM_ARGS, programArgs)
        workingDir = JDOMExternalizerUtil.readField(element, FIELD_CWD, workingDir)
        launchJsonOverrides = JDOMExternalizerUtil.readField(element, FIELD_LAUNCH_JSON, launchJsonOverrides)
        environment = element.getChild(ELEMENT_ENV)?.children?.associate {
            it.getAttributeValue("key").orEmpty() to it.getAttributeValue("value").orEmpty()
        } ?: emptyMap()
    }

    companion object {
        private const val FIELD_PROFILE_ID = "profileId"
        private const val FIELD_ADAPTER = "adapter"
        private const val FIELD_PROGRAM = "program"
        private const val FIELD_PROGRAM_ARGS = "programArgs"
        private const val FIELD_CWD = "workingDir"
        private const val FIELD_LAUNCH_JSON = "launchJson"
        private const val ELEMENT_ENV = "env"
    }
}
