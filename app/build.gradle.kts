plugins {
    id("shelfplayer.android.application.compose")
    id("shelfplayer.hilt")
    alias(libs.plugins.ksp)
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

android {
    namespace = "com.example.shelfplayer"

    buildFeatures {
        buildConfig = true
    }

    /**
     * PRODUCT_SPEC SET-002 / ADR-0022 — the language setting is why this is off.
     *
     * An app bundle splits resources by language by default and Play installs only the device's own. An
     * in-app language picker then offers a language whose strings are not on the device, and every label
     * silently falls back to English — which is exactly the failure the setting exists to fix. The
     * alternative is Play Core's on-demand language download; disabling the split costs a few hundred
     * kilobytes and no runtime dependency.
     */
    bundle {
        language {
            enableSplit = false
        }
    }
}

/**
 * PRODUCT_SPEC 9.2 / ADR-0002 — Phase 0 hosts the `feature:*` code as packages inside `:app`.
 *
 * The package boundaries from PRODUCT_SPEC 16.4 are respected (`feature.home`, `feature.library`,
 * `feature.book`), so promoting each of them to its own Gradle module later is a move, not a
 * rewrite. The core, data, domain and playback boundaries are already real modules, because those
 * are the ones the dependency rules in PRODUCT_SPEC 9.3 actually constrain.
 */
dependencies {
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.data.auth)
    implementation(projects.data.downloads)
    implementation(projects.data.library)
    implementation(projects.data.settings)
    implementation(projects.domain)
    // PRODUCT_SPEC PLAY-001 — the media service and the controller the screens drive it through.
    implementation(projects.playback)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // PRODUCT_SPEC SYNC-003 — persistent background refresh. `hilt-work` is what lets a Worker be
    // constructed with injected dependencies rather than reaching into the graph through a static.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.haze)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.turbine)
    // PRODUCT_SPEC 17.1 — the UI tier, on the JVM.
    //
    // `ui-test-junit4` with Robolectric rather than an `androidTest` source set, so these run inside
    // `verifyDebug` on every build and on every pull request. An instrumented tier needs an emulator,
    // which this project's CI does not have, and a test suite nothing runs is not a regression net.
    //
    // What it can assert is the semantics tree — content descriptions, roles, toggleable state, merged
    // nodes — which is exactly what a screen reader consumes. What it cannot assert is what a real
    // device does with it: that is PRODUCT_SPEC 17.2's matrix, and it needs hardware.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * PRODUCT_SPEC 17.1 — the rendered UI tier runs on the debug variant, and only there.
 *
 * `ui-test-manifest` is what declares the `ComponentActivity` that `createComposeRule` launches, and it
 * is a `debugImplementation` on purpose: shipping a test activity in a release build would be a strange
 * thing to do to fix a test. The release unit-test variant therefore has no activity to launch, and
 * these classes are excluded from it rather than being made to pass by weakening the release build.
 *
 * Nothing is lost. The debug variant is what `verifyDebug` gates on and what CI runs, and the code under
 * test is identical in both — a Compose semantics tree does not change with the build type.
 *
 * **The `ScreenTest` suffix is a contract, not a description.** Any test class that calls
 * `createComposeRule` has to carry it, whether or not the thing it renders is a screen; one that does not
 * fails only in the release variant, with an unresolvable launcher intent rather than an assertion.
 * (Do not write the exclusion's glob inside a KDoc block — its `*` followed by `/` closes the comment.)
 */
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        exclude("**/*ScreenTest.class")
    }
}
