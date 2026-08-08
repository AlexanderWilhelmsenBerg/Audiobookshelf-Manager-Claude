plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

android {
    namespace = "com.example.shelfplayer.playback"
}

/**
 * PRODUCT_SPEC 9.2 / PLAY-001 — the media service and everything that owns the player.
 *
 * ### Why this is its own module
 *
 * It is the only place in the app allowed to name ExoPlayer, `MediaSession` or a `Service`. That is the
 * boundary that keeps "one player, one session" (PLAY-001) structural rather than conventional: a
 * ViewModel cannot construct a second `ExoPlayer` because it cannot see the class.
 *
 * ### What it depends on, and what it does not
 *
 * `:domain` for the repository interfaces, `:core:model` for the session type. **Not** `:core:database`
 * and not `:core:datastore`: the service writes progress through `PlaybackRepository`, so it never names
 * a Room entity.
 *
 * `:core:network` is here for one thing — the authenticated `OkHttpClient` that the media data source
 * streams over. The server sends credential-free track URLs, so the `Authorization` header is what
 * fetches them (PRODUCT_SPEC 14.5), and that header comes from the app's own client rather than from a
 * second stack this module would otherwise have to build and keep in step.
 */
dependencies {
    api(projects.domain)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.network)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(projects.core.testing)
    // `MediaItem` and `Bundle` are Android types, so the playlist builder's tests need a runtime for
    // them. Robolectric rather than an instrumented tier, for the reason `:app`'s UI tests give: a test
    // suite that needs an emulator is a test suite this project's CI never runs.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
