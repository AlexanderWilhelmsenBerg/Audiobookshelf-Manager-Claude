plugins {
    id("shelfplayer.android.library.compose")
}

android {
    namespace = "com.example.shelfplayer.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    // PRODUCT_SPEC 4 / §129 — the breakpoints every adaptive screen measures itself against.
    // `api` rather than `implementation`: a screen that lays itself out by window size names
    // `WindowWidthSizeClass` in its own signature, so the type has to be on its compile classpath.
    api(libs.androidx.compose.material3.window.size)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
}
