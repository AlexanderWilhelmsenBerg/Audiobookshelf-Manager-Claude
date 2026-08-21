package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register

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
            registerSbomTask()

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
        /*
         * PRODUCT_SPEC 16.4 / 24.1 — the real application ID, chosen by the owner (ADR-0024).
         *
         * Reverse-DNS of a domain the owner controls, which is the convention and the only way the id is
         * verifiable by anyone else. It replaces the `com.example.` placeholder that Play rejects outright.
         *
         * **This is the `applicationId`, not the `namespace`.** They are different things and only this one
         * is the install's identity on a device and in Play. The Kotlin packages and every module's
         * `namespace` remain `com.example.shelfplayer`: renaming those would touch every file in the
         * repository, and Play neither sees nor cares about them. ADR-0024 records that split so nobody
         * later "finishes the job" and rewrites five hundred files for no effect.
         *
         * Moved now, before any release, for the reason ADR-0019 gives: Android identifies an install by
         * its `applicationId`, so changing it after somebody has the app installs a *second, empty* copy
         * rather than renaming the first — costing a fresh sign-in and every downloaded book.
         */
        applicationId = "org.homebord.bookwave"
        minSdk = project.libs.intVersion("minSdk")
        targetSdk = project.libs.intVersion("targetSdk")
        versionCode = 39
        // The name states what the build is, so a device-test result recorded against an APK can be
        // traced to one. It has to move with the code: it sat at `0.9.6-auto-shelves` for nine builds
        // while the code advanced, and every field report in that window named the wrong build — which
        // matters more now that the debug console prints this string for the user to paste.
        versionName = "0.9.13-switcher-unlock"
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

/**
 * PRODUCT_SPEC 18 — registers `sbom`, which describes what the release build actually contains.
 *
 * Wired to `releaseRuntimeClasspath` rather than to a variant's artefacts, because that configuration is
 * the post-conflict-resolution answer to "what is in the shipped application" and it resolves without
 * assembling anything — so the SBOM can be produced and reviewed on a pull request that never builds a
 * release APK.
 *
 * The licence written into the document's own metadata component is `GPL-3.0-or-later` (ADR-0024). It is a
 * literal here rather than read from `LICENSE`, so that changing the project's licence has to touch this
 * line: an SBOM claiming the wrong licence for the work itself is the one field in it nobody would think
 * to check.
 */
private fun Project.registerSbomTask() {
    // Captured here rather than reached for inside the configuration block below. `Task` is itself
    // `ExtensionAware`, so an `extensions` lookup in there resolves against the *task* and fails with
    // "Extension of type 'ApplicationExtension' does not exist" — at execution time, not at configuration
    // time, which is the worst place for it to surface.
    val android = extensions.getByType(ApplicationExtension::class.java)

    tasks.register<SbomTask>("sbom") {
        group = "verification"
        description = "Writes a CycloneDX 1.5 SBOM for the release runtime classpath (PRODUCT_SPEC 18)."

        rootComponent.set(
            configurations.named("releaseRuntimeClasspath").flatMap { configuration ->
                configuration.incoming.resolutionResult.rootComponent
            },
        )
        verificationMetadata.set(rootProject.layout.projectDirectory.file("gradle/verification-metadata.xml"))
        gradleUserHome.set(project.layout.dir(provider { gradle.gradleUserHomeDir }))
        applicationId.set(provider { android.defaultConfig.applicationId ?: "unknown" })
        versionName.set(provider { android.defaultConfig.versionName ?: "unknown" })
        projectLicense.set("GPL-3.0-or-later")
        failOnUnpinned.set(true)
        excludedModules.set(emptySet<String>())
        outputFile.set(layout.buildDirectory.file("reports/sbom/bom.json"))
    }
}
