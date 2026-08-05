package com.example.shelfplayer.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Compose dependencies and compiler configuration shared by `:app` and Compose library modules.
 *
 * PRODUCT_SPEC 10.1 (Compose Material 3), 16.3 (Compose compiler reports/metrics in CI artifacts).
 */
internal fun Project.configureCompose() {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

    // `platform()` takes a dependency *notation*, not a Provider, so the catalog entry is flattened
    // to its `group:name:version` coordinate here. Passing the Provider straight through fails at
    // plugin-application time with "Cannot convert the provided notation to an object of type
    // Dependency".
    val composeBom = libs.findLibrary("androidx-compose-bom").get().get().let { dependency ->
        "${dependency.module.group}:${dependency.module.name}:${dependency.versionConstraint.requiredVersion}"
    }

    dependencies {
        add("implementation", platform(composeBom))
        add("testImplementation", platform(composeBom))
        add("androidTestImplementation", platform(composeBom))
    }

    extensions.configure(ComposeCompilerGradlePluginExtension::class.java) {
        val emitReports = providers.gradleProperty("shelfplayer.composeCompilerReports")
            .orNull?.toBoolean() ?: false
        if (emitReports) {
            val root = layout.buildDirectory.dir("compose-compiler")
            reportsDestination.set(root.map { it.dir("reports") })
            metricsDestination.set(root.map { it.dir("metrics") })
        }
    }
}
