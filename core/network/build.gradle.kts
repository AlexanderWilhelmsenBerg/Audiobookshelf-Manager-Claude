plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.shelfplayer.core.network"
}

/**
 * PRODUCT_SPEC 9.3 — network DTOs never leave this module.
 *
 * The gateway interfaces expose `:core:model` types and `AppResult`; nothing in `:domain`, `:data`
 * or `:app` can name a wire type, because none of them see one.
 */
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
