package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Baseline for `:app`.
 *
 * PRODUCT_SPEC 4 (SDK levels), 15 (release hardening), 16.3 (Android Lint), 16.4 (base package).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("shelfplayer.quality")

            extensions.configure<ApplicationExtension> {
                compileSdk = libs.intVersion("compileSdk")
                configureDefaultConfig(this@with)
                configureBuildTypes()
                configurePackaging()

                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }

                buildFeatures {
                    buildConfig = true
                }

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

private fun ApplicationExtension.configureDefaultConfig(project: Project) {
    defaultConfig {
        // PRODUCT_SPEC 16.4 / 24.1: placeholder application ID, replaced before release.
        applicationId = "com.example.shelfplayer"
        minSdk = project.libs.intVersion("minSdk")
        targetSdk = project.libs.intVersion("targetSdk")
        versionCode = 14
        // The build under acceptance test names the phase it is being tested against
        // (`docs/phase-1-acceptance.md`), so a result recorded against an APK can be traced to one.
        versionName = "0.2.0-phase2w1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
}

private fun ApplicationExtension.configureBuildTypes() {
    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // PRODUCT_SPEC 15: Phase 0 declares no signing config, so release builds stay unsigned
            // and no key material can live in the repository.
        }
    }
}

private fun ApplicationExtension.configurePackaging() {
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}
