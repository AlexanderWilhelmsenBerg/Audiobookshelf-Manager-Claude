package com.example.shelfplayer.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Applies the compiler contract every ShelfPlayer module shares.
 *
 * Configured through the compile tasks rather than through a plugin-specific Kotlin extension so
 * that the same code works for JVM-only modules and Android modules.
 */
internal fun Project.configureKotlinCompilation() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // PRODUCT_SPEC 16.3: Kotlin compiler warnings are errors in CI.
            allWarningsAsErrors.set(this@configureKotlinCompilation.warningsAsErrors)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JAVA_VERSION.toString()
        targetCompatibility = JAVA_VERSION.toString()
    }
}
