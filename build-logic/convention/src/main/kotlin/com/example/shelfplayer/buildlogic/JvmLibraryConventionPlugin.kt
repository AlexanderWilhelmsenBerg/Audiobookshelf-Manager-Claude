package com.example.shelfplayer.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Baseline for pure-JVM modules (`:core:model`, `:core:common`, `:domain`).
 *
 * PRODUCT_SPEC 9.3: the domain layer must not be able to reach the Android framework. Keeping these
 * modules on the plain Kotlin/JVM plugin makes that a compile error rather than a review comment.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("shelfplayer.quality")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            configureKotlinCompilation()

            tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                useJUnit()
            }

            dependencies {
                add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
                add("testImplementation", libs.findLibrary("junit4").get())
                add("testImplementation", libs.findLibrary("kotlin-test").get())
                add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }
}
