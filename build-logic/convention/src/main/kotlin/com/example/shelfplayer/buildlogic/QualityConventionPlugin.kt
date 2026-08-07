package com.example.shelfplayer.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.util.concurrent.Callable

/**
 * The quality gate applied to every ShelfPlayer project, including the root project.
 *
 * PRODUCT_SPEC 16.2 (ktlint), 16.3 (detekt with type resolution, Android Lint, warnings as errors),
 * 16.1 (dependency locking) and 16.5 (`verifyDebug`).
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configureKtlint()
            configureDetekt()
            configureCoverage()
            configureDependencyLocking()
            registerVerifyDebug()
        }
    }
}

private fun Project.configureKtlint() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")

    extensions.configure<KtlintExtension> {
        version.set(libs.version("ktlint"))
        // PRODUCT_SPEC 16.2: ktlint is the single formatter; style rules live in `.editorconfig`.
        ignoreFailures.set(false)
        enableExperimentalRules.set(false)
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
        }
        filter {
            // Generated Kotlin (KSP, Room, Hilt, protobuf) is not ours to format.
            exclude { element -> element.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
            exclude { element ->
                element.file.path.contains("${java.io.File.separator}generated${java.io.File.separator}")
            }
        }
    }
}

/**
 * PRODUCT_SPEC 17.3 — coverage, measured.
 *
 * Applied by id and configured nowhere here: the thresholds are a project-wide policy, not a per-module
 * one, so they live in the root build file where the aggregate report is assembled. This only makes each
 * module *measurable*.
 */
private fun Project.configureCoverage() {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}

private fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        // PRODUCT_SPEC 16.3: no detekt baseline for new code.
        baseline = null
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = JVM_TARGET
        exclude("**/build/**", "**/generated/**")
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }
    tasks.withType<DetektCreateBaselineTask>().configureEach {
        jvmTarget = JVM_TARGET
    }
}

private fun Project.configureDependencyLocking() {
    // PRODUCT_SPEC 16.1: dependency locking. Lock state is written by
    // `./gradlew resolveAndLockAll --write-locks` (see scripts/update-dependency-locks.sh).
    dependencyLocking {
        lockAllConfigurations()
    }
}

/**
 * PRODUCT_SPEC 16.5: one command that agents and CI can run.
 *
 * Task names are resolved lazily through a [Callable] so a module only wires the gates that its own
 * plugins actually registered — a JVM module has no `lintDebug`, an Android module has no bare
 * `test` worth running twice.
 */
private fun Project.registerVerifyDebug() {
    val verify = tasks.register("verifyDebug") {
        group = "verification"
        description = "ktlint + detekt (type resolution) + Android Lint + unit tests + debug assembly."
    }

    verify.configure {
        dependsOn(Callable { resolveVerifyDebugDependencies() })
    }
}

/**
 * Detekt registers per-variant tasks (`detektDebug`) for Android modules and per-compilation tasks
 * (`detektMain`) for JVM modules. Both carry the compile classpath, which is what gives detekt type
 * resolution (PRODUCT_SPEC 16.3); the plain `detekt` task does not and is therefore not a gate.
 */
private fun Project.resolveVerifyDebugDependencies(): List<String> {
    val available = tasks.names
    val android = pluginManager.hasPlugin("com.android.base")
    val hasKotlin = pluginManager.hasPlugin("org.jetbrains.kotlin.android") ||
        pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")

    val typeResolvingDetekt = when {
        !hasKotlin -> emptyList()
        "detektDebug" in available ->
            listOf("detektDebug", "detektDebugUnitTest").filter(available::contains)
        else -> listOf("detektMain", "detektTest").filter(available::contains)
    }
    check(!hasKotlin || typeResolvingDetekt.isNotEmpty()) {
        "$path has Kotlin sources but no detekt task with type resolution. " +
            "Expected one of detektDebug/detektMain; found: ${available.filter { it.startsWith("detekt") }}"
    }

    val build = when {
        android -> listOf("lintDebug", "testDebugUnitTest", "assembleDebug")
        hasKotlin -> listOf("test")
        else -> emptyList()
    }

    return (listOf("ktlintCheck") + typeResolvingDetekt + build).filter(available::contains)
}
