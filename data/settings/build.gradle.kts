plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
}

android {
    namespace = "com.example.shelfplayer.data.settings"
}

/**
 * PRODUCT_SPEC 9.3 / SET-001 — the module reserved for EPIC SET.
 *
 * It exists so that no ViewModel names `AppSettingsDataSource`. A screen asking the settings store for
 * a value directly is untestable without a real DataStore on disk, and it puts the decision of what a
 * setting *means* — its default, its precedence, its type — in whichever screen happened to need it
 * first. PRODUCT_SPEC SET-001 defines a five-level precedence chain; it needs one owner.
 *
 * `:core:datastore` is an `implementation` dependency, so the generated protobuf types stop here.
 */
dependencies {
    api(projects.domain)
    implementation(projects.core.datastore)

    testImplementation(projects.core.testing)
    testImplementation(libs.turbine)
}
