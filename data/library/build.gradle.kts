plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

android {
    namespace = "com.example.shelfplayer.data.library"
}

/**
 * PRODUCT_SPEC 9.3 — data modules implement domain repository interfaces.
 *
 * `:core:database` and `:core:network` are `implementation` dependencies, so entities and gateway
 * internals stop here: `:app` and `:domain` see only `:core:model` types.
 */
dependencies {
    api(projects.domain)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)

    testImplementation(projects.core.testing)
    // The repository test builds a real in-memory database, so Room is on the *test* classpath only.
    // Production code in this module still cannot see it (PRODUCT_SPEC 9.3).
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
