package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Compose-enabled Android application module (`:app`). */
class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("shelfplayer.android.application")

            extensions.configure<ApplicationExtension> {
                buildFeatures {
                    compose = true
                }
            }

            configureCompose()

            dependencies {
                add("implementation", libs.findLibrary("androidx-compose-ui").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
                add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
            }
        }
    }
}
