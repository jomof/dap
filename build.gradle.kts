import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(21)
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // ktlint rules wrapped as detekt rules — extends detekt's coverage
    // to formatting/style categories that IntelliJ also surfaces.
    detektPlugins(libs.detektFormatting)

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

// ─── Static analysis ────────────────────────────────────────────────
//
// detekt runs as part of `./gradlew check`. We start with the bundled
// default ruleset so the agent (and CI) sees the same warnings the
// IDE shows; project-specific rule tuning lives in `detekt.yml` and
// is layered on top via `buildUponDefaultConfig = true`.
//
// `ignoreFailures = false` makes new warnings break the build — once
// the baseline is clean this prevents regression. If you ever need a
// soft-fail mode while triaging, flip it to `true` for the
// problematic run.
detekt {
    parallel = true
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    ignoreFailures = false
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
    autoCorrect = false
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    // We don't ship type-resolution-dependent rules (slow + brittle
    // across IntelliJ Platform classpaths); keep things lexical-only.
    jvmTarget.set("21")
    // Default is `Error` — but most style/complexity findings are
    // `Warning`, which would silently pass. `Info` fails on anything
    // the configured ruleset reports.
    failOnSeverity.set(dev.detekt.gradle.extensions.FailOnSeverity.Info)
    reports {
        // HTML for humans, Checkstyle XML for machines (CI tooling,
        // and any future agent integration that wants line-precise
        // findings). Detekt 2.0 dropped the `xml` alias in favour of
        // `checkstyle`. SARIF and Markdown stay off — nothing reads
        // them today.
        html.required.set(true)
        checkstyle.required.set(true)
        sarif.required.set(false)
        markdown.required.set(false)
    }
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
