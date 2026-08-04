package com.example.shelfplayer.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Compose dependencies and compiler configuration shared by `:app` and Compose library modules.
 *
 * PRODUCT_SPEC 10.1 (Compose Material 3), 16.3 (Compose compiler reports/metrics in CI artifacts).
 */
internal fun Project.configureCompose() {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

    val bom = libs.findLibrary("androidx-compose-bom").get()
    dependencies {
        addComposePlatform(bom)
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

private fun DependencyHandler.addComposePlatform(bom: Any) {
    add("implementation", platform(bom))
    add("androidTestImplementation", platform(bom))
    add("testImplementation", platform(bom))
}
