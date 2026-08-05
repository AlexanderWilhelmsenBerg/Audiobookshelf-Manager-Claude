plugins {
    id("shelfplayer.android.library.compose")
}

android {
    namespace = "com.example.shelfplayer.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
}
