package com.example.shelfplayer.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The single `libs` version catalog declared in `gradle/libs.versions.toml`. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int = version(alias).toInt()

/** PRODUCT_SPEC 4: Java bytecode target is 17 across every module. */
internal val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

internal const val JVM_TARGET: String = "17"

/**
 * PRODUCT_SPEC 16.3: Kotlin compiler warnings are errors in CI.
 *
 * Toggled with `-Pshelfplayer.warningsAsErrors=true` so that local iteration is not blocked by a
 * warning while CI still refuses to merge one.
 */
internal val Project.warningsAsErrors: Boolean
    get() = providers.gradleProperty("shelfplayer.warningsAsErrors").orNull?.toBoolean() ?: false
