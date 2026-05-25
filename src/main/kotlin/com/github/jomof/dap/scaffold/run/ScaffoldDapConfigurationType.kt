package com.github.jomof.dap.scaffold.run

import com.github.jomof.dap.language.DapLanguageProfile
import com.github.jomof.dap.scaffold.language.CppLanguageProfile
import com.github.jomof.dap.scaffold.language.GoLanguageProfile
import com.github.jomof.dap.scaffold.language.RustLanguageProfile
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

/**
 * Single configuration type with one factory per (profile, adapter) pair.
 *
 * Phase 1 ships C/C++ via lldb-dap; Phase 2 adds Rust via lldb-dap as the
 * default plus CodeLLDB as the user-switchable alternative; Phase 5 adds a
 * Go factory. Each factory bakes in its profile and default adapter so the
 * user only sees a single, language-named entry in the New Configuration
 * dialog.
 */
class ScaffoldDapConfigurationType : ConfigurationType {

    private val factories: Array<ConfigurationFactory> = arrayOf(
        Factory(this, CppLanguageProfile, DapAdapterKind.LLDB_DAP, "DAP (C/C++) — lldb-dap"),
        Factory(this, RustLanguageProfile, DapAdapterKind.LLDB_DAP, "DAP (Rust) — lldb-dap"),
        Factory(this, GoLanguageProfile, DapAdapterKind.DELVE_DAP, "DAP (Go) — delve-dap"),
    )

    override fun getDisplayName(): String = "DAP (Scaffold)"
    override fun getConfigurationTypeDescription(): String =
        "Scaffold run configurations that drive the DAP-to-XDebugger bridge against real debug adapters."
    override fun getIcon() = AllIcons.RunConfigurations.Application
    override fun getId(): String = "DAP_SCAFFOLD"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = factories

    private class Factory(
        type: ScaffoldDapConfigurationType,
        private val profile: DapLanguageProfile,
        private val adapterKind: DapAdapterKind,
        private val factoryName: String,
    ) : ConfigurationFactory(type) {
        override fun getId(): String = "DAP_${profile.id.uppercase()}_${adapterKind.name}"
        override fun getName(): String = factoryName
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ScaffoldDapRunConfiguration(project, this, factoryName).also {
                it.profileId = profile.id
                it.adapterKind = adapterKind
            }
    }
}
