package com.example.shelfplayer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
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
                configureReleaseSigning(this@with)
                // PRODUCT_SPEC 15 / R-68 — one stable debug key, so `adb install -r` is an upgrade
                // rather than a reinstall. Deliberately separate from the release inputs above.
                configureDebugSigning(this@with)
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
            disableTestsForBenchmarkVariant()
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
        /*
         * The build names itself after the pull request it came from — see [BuildIdentity] for why the
         * pull request is a safe counter where a timestamp was not, and for what an unnumbered build does.
         *
         * These were two hand-written constants, and the comment that used to stand here said the name
         * "has to move with the code" and then described it failing to for nine builds. It had since sat
         * still for dozens more. Nothing has to be remembered now.
         */
        val build = project.buildIdentity()
        versionCode = build.versionCode
        versionName = build.versionName
        // Which commit, and which pull request, for a tester holding two APKs and a report to file. Read by
        // the About tab and the debug console; `BUILD_TYPE` already distinguishes debug from release.
        buildConfigField("String", "GIT_COMMIT", "\"${build.commit}\"")
        buildConfigField("String", "PULL_REQUEST", "\"${build.pullRequest}\"")
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
            /*
             * PRODUCT_SPEC 15 — no signing configuration is declared *here*, and none ever will be.
             *
             * `configureReleaseSigning` adds one only when a keystore is supplied from outside the
             * checkout, and does nothing at all otherwise, which is the case CI takes: an unsigned
             * release, exactly as before. `ReleaseSigning.kt` carries the rules and the refusals.
             */
        }
        /*
         * PRODUCT_SPEC 17.3 / ADR-0025 — the variant the macrobenchmarks measure.
         *
         * **It has to be release-like or the numbers are fiction.** A debug build is not R8-shrunk, runs
         * without the optimisations that dominate cold start, and Macrobenchmark refuses a debuggable
         * target outright. `initWith(release)` therefore copies minification, resource shrinking and the
         * ProGuard set, so what is timed is as close to the shipped application as a measurable build can
         * be.
         *
         * Two deliberate differences from release, and no others:
         *
         *  - **It is signed with the debug key**, unconditionally, rather than inheriting whatever
         *    `configureReleaseSigning` did or did not set up. An unsigned APK cannot be installed and so
         *    could not be measured, and a benchmark that only runs on a machine holding the upload key
         *    would be a benchmark nobody runs. The debug keystore is generated by the SDK on the machine
         *    running the build and is not in this repository, so this adds no key material to it.
         *  - **It is profileable.** `<profileable android:shell="true">` is what lets the shell read the
         *    process's traces; without it Macrobenchmark can time a cold start and nothing inside one.
         *
         * `matchingFallbacks` points at release so every library module — none of which defines this build
         * type — contributes its release variant rather than failing to resolve.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isProfileable = true
            // R8 would otherwise delete the seeding receiver, which nothing in the application calls.
            proguardFiles("benchmark-rules.pro")
        }
    }
}

/**
 * PRODUCT_SPEC 17.3 — the benchmark variant builds an application and nothing else.
 *
 * Without this, adding a third build type silently adds `testBenchmarkUnitTest` and a third Kover variant
 * to a project whose coverage gate is a root-level aggregate — so a variant that exists only to be
 * measured on a device would start contributing to, and being required by, the coverage number. It would
 * also fail: `ui-test-manifest` is a `debugImplementation`, so the Robolectric screen tests have no
 * activity to launch outside debug.
 *
 * Turning both test components off states that in one place, rather than leaving a reader of `:app`'s
 * build file to infer it from an exclusion glob.
 */
private fun Project.disableTestsForBenchmarkVariant() {
    extensions.configure<ApplicationAndroidComponentsExtension> {
        beforeVariants(selector().withBuildType("benchmark")) { variant ->
            // The host-test builder rather than `enableUnitTest`, which AGP 9 removes.
            variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
            variant.enableAndroidTest = false
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
