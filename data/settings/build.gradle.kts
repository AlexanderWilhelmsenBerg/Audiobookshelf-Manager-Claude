plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
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

    testImplementation(projects.core.testing)
    testImplementation(libs.turbine)
}
