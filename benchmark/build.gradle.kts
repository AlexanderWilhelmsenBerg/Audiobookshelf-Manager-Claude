import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    id("shelfplayer.quality")
}

/**
 * PRODUCT_SPEC 17.3 / ADR-0025 — the four numbers, measured on hardware.
 *
 * ### Why this is a `com.android.test` module
 *
 * It builds its own APK and is depended on by nothing. Macrobenchmark works by driving the real
 * application from a separate process — starting it cold, reading its traces through the shell — which is
 * the only way to time a cold start honestly. Nothing here can reach into `:app`'s classes, and that is
 * the point: what it measures is what a user gets.
 *
 * ### Nothing here runs in CI, and that is not an oversight
 *
 * Every task this module owns needs a device attached, and this project's CI has no emulator (the same
 * constraint `docs/testing.md` records for the instrumented tier). A benchmark cannot be run on a shared
 * runner and mean anything anyway: the number would describe the runner's contention.
 *
 * What CI *does* do is compile it. The `debug` variant is deliberately left enabled so that
 * `shelfplayer.quality`'s `verifyDebug` runs ktlint, detekt with type resolution, Lint and `assembleDebug`
 * over these sources on every pull request. A benchmark that stops compiling six months from now is the
 * normal fate of code no gate touches, and this is the cheap half of preventing it. The `debug` variant is
 * never *run* — Macrobenchmark refuses a debuggable target, which is exactly what it should do.
 */
android {
    namespace = "com.example.shelfplayer.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        /*
         * Higher than the application's 26, and it has to be.
         *
         * `BaselineProfileRule` needs API 28 to read the profile back out of the shell, and
         * `<profileable android:shell="true">` — how every metric other than start-up timing gets its
         * data — is API 29. A benchmark module with the app's `minSdk` would install on a device it
         * cannot measure and report zeros.
         */
        minSdk = 29
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        /*
         * Matches `:app`'s build type of the same name — see `AndroidApplicationConventionPlugin`.
         *
         * The *test* APK is debuggable while the application under test is not; that is the normal shape
         * and the only one that works, since the shell has to be able to instrument this process.
         */
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"

    // The test APK and the application run in one process group, which is what lets the benchmark
    // compile and install a release-like target rather than needing a debuggable one.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit4)
}

// PRODUCT_SPEC 4 / 16.3 — the same compiler contract every other module gets from `build-logic`. Spelled
// out here because `configureKotlinCompilation` is internal to the convention plugins and this is the one
// module that is not built by one of them.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(
            providers.gradleProperty("shelfplayer.warningsAsErrors").orNull?.toBoolean() ?: false,
        )
    }
}
