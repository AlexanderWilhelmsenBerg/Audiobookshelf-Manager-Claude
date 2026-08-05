plugins {
    id("shelfplayer.android.application.compose")
    id("shelfplayer.hilt")
}

android {
    namespace = "com.example.shelfplayer"

    buildFeatures {
        buildConfig = true
    }
}

/**
 * PRODUCT_SPEC 9.2 / ADR-0002 — Phase 0 hosts the `feature:*` code as packages inside `:app`.
 *
 * The package boundaries from PRODUCT_SPEC 16.4 are respected (`feature.home`, `feature.library`,
 * `feature.book`), so promoting each of them to its own Gradle module later is a move, not a
 * rewrite. The core, data, domain and playback boundaries are already real modules, because those
 * are the ones the dependency rules in PRODUCT_SPEC 9.3 actually constrain.
 */
dependencies {
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.data.auth)
    implementation(projects.data.library)
    implementation(projects.data.settings)
    implementation(projects.domain)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
