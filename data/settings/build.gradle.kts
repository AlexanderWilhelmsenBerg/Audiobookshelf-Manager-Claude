plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
    // SET-002 — the bundled background themes are JSON beside their artwork; this parses them. The DTOs
    // are private to `BundledBackgroundThemeCatalog` and map to `BackgroundTheme` before they escape it.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.shelfplayer.data.settings"
}

/**
 * PRODUCT_SPEC 9.3 / SET-001, SET-002 — the module reserved for EPIC SET.
 *
 * It holds what the settings screen reads. Today that is the storage diagnostics (SET-002,
 * Privacy/diagnostics); when a real setting arrives it lands here too, so that no ViewModel ever names
 * `AppSettingsDataSource`: a screen asking the store for a value directly is untestable without a
 * DataStore on disk, and it puts the decision of what a setting *means* — its default, its place in
 * SET-001's five-level precedence chain — in whichever screen happened to need it first.
 *
 * `:core:database` and `:core:datastore` are `implementation` dependencies, so Room entities and the
 * generated protobuf types stop here.
 */
dependencies {
    api(projects.domain)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    // PRODUCT_SPEC PLAY-001 — for the `PlaybackDeviceIdentity` seam, whose stored half lives here.
    implementation(projects.core.network)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    // The sleep-timer repository test builds a real database and a real DataStore file, because the
    // properties worth testing are what each does with an *unset* value.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
