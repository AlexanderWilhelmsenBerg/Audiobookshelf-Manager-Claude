package com.example.shelfplayer.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * PRODUCT_SPEC 9.4: Hilt is the only dependency-injection mechanism; no service locator.
 *
 * JVM-only modules get `hilt-core` (the `javax.inject` + `dagger` annotations) without the Android
 * runtime, so `:domain` can declare `@Inject` constructors without gaining an Android dependency.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            dependencies {
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }

            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                dependencies {
                    add("implementation", libs.findLibrary("hilt-core").get())
                }
            }

            pluginManager.withPlugin("com.android.base") {
                pluginManager.apply("com.google.dagger.hilt.android")
                dependencies {
                    add("implementation", libs.findLibrary("hilt-android").get())
                    add("kspTest", libs.findLibrary("hilt-compiler").get())
                    add("testImplementation", libs.findLibrary("hilt-android-testing").get())
                }
            }
        }
    }
}
