package com.example.shelfplayer.buildlogic

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Baseline for every Android library module (`:core:*`, `:data:*`).
 *
 * PRODUCT_SPEC 4 (SDK levels, bytecode target), 16.3 (Android Lint), 17 (unit test defaults).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("shelfplayer.quality")

            extensions.configure<LibraryExtension> {
                compileSdk = libs.intVersion("compileSdk")

                defaultConfig {
                    minSdk = libs.intVersion("minSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }

                buildFeatures {
                    buildConfig = false
                }

                // Robolectric-backed Room and repository tests need real Android resources.
                testOptions.unitTests.isIncludeAndroidResources = true
                testOptions.unitTests.isReturnDefaultValues = true

                lint {
                    applyShelfPlayerLintRules()
                }
            }

            configureKotlinCompilation()

            dependencies {
                add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
                add("testImplementation", libs.findLibrary("junit4").get())
                add("testImplementation", libs.findLibrary("kotlin-test").get())
                add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }
}
