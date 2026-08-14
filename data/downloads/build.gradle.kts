plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

android {
    namespace = "com.example.shelfplayer.data.downloads"
}

/**
 * PRODUCT_SPEC 9.3 / EPIC DL — downloads and the offline manifest.
 *
 * Its own module rather than more of `:data:library`, for two reasons that are both about blast radius.
 * The manifest is the only thing in the app that can make a book unplayable by being wrong, so it is worth
 * a boundary a reviewer can see the whole of; and Phase 3 adds a worker, a network policy and a storage
 * screen behind this same seam, all of which would otherwise land in the module that already holds the
 * catalogue sync.
 *
 * `:core:database` is an `implementation` dependency, so Room entities stop here and the rest of the app
 * sees `OfflineBook` (PRODUCT_SPEC 9.4, "DTOs and entities never escape their data modules").
 */
dependencies {
    api(projects.domain)
    implementation(projects.core.common)
    implementation(projects.core.database)

    testImplementation(projects.core.testing)
    // The manifest tests build a real database, because what is worth testing is what SQLite does with the
    // cascade and the reference count — not what a fake would have been told to do.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
