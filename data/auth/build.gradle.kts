plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

android {
    namespace = "com.example.shelfplayer.data.auth"
}

/**
 * PRODUCT_SPEC 9.3 / `docs/architecture/module-boundaries.md` — the module reserved for
 * AUTH-001…AUTH-004.
 *
 * It is the one module that sees both `:core:network` (which declares `TokenProvider` and owns the
 * wire calls) and `:core:datastore` (which holds the encrypted credential), which is what lets the
 * in-memory token cache live next to the sign-out that has to clear it. `:app` no longer binds that
 * seam, so nothing outside this module can name the object holding a decrypted token.
 */
dependencies {
    api(projects.domain)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)

    testImplementation(projects.core.testing)
    // The repository tests build a real in-memory database, so Room is on the *test* classpath only.
    // Production code in this module still cannot see it (PRODUCT_SPEC 9.3).
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
