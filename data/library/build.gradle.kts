plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
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
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
