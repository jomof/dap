import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // Eclipse LSP4J Debug bindings (DAP types + JSON-RPC framing).
    // gson is excluded because the IntelliJ Platform already bundles it.
    implementation(libs.lsp4jDebug) {
        exclude(group = "org.eclipse.lsp4j", module = "org.eclipse.lsp4j.jsonrpc.debug")
    }
    implementation(libs.lsp4jJsonrpcDebug) {
        exclude(group = "com.google.code.gson", module = "gson")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)
    }
}

// We don't ship IntelliJ GUI Designer .form files and the bytecode instrumentation
// task in IntelliJ Platform Gradle Plugin 2.x has compatibility issues with the
// Ant tasks shipped in some IDE releases ("instrumentIdeaExtensions doesn't support
// the nested 'skip' element"). Disable it since we have nothing to instrument.
intellijPlatform {
    instrumentCode = false
}

intellijPlatformTesting {
    val runClion by runIde.registering {
        type = IntelliJPlatformType.CLion
        version = "2025.3.5"
    }
    val runRustRover by runIde.registering {
        type = IntelliJPlatformType.RustRover
        version = "2025.3.5"
    }
}
