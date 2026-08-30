import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.example.shelfplayer.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kotlin.composeCompilerGradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    // KSP is deliberately absent, and its absence is load-bearing.
    //
    // `kotlin-dsl` compiles this module with the Kotlin the *Gradle distribution* embeds — 2.0 under Gradle
    // 8.14.3 — rather than the version the app modules use. KSP 2.3's plugin jar carries Kotlin 2.3
    // metadata, which that compiler refuses to read, so having it on this classpath pinned the whole
    // project to an old KSP. Nothing here needed it: `HiltConventionPlugin` and `AndroidRoomConventionPlugin`
    // apply KSP by plugin id and add to the `"ksp"` configuration **by name**, so no KSP type is ever
    // referenced. Do not add it back without checking that first.
    compileOnly(libs.ktlint.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "shelfplayer.android.application"
            implementationClass =
                "com.example.shelfplayer.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "shelfplayer.android.application.compose"
            implementationClass =
                "com.example.shelfplayer.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "shelfplayer.android.library"
            implementationClass =
                "com.example.shelfplayer.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "shelfplayer.android.library.compose"
            implementationClass =
                "com.example.shelfplayer.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidRoom") {
            id = "shelfplayer.android.room"
            implementationClass = "com.example.shelfplayer.buildlogic.AndroidRoomConventionPlugin"
        }
        register("hilt") {
            id = "shelfplayer.hilt"
            implementationClass = "com.example.shelfplayer.buildlogic.HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "shelfplayer.jvm.library"
            implementationClass = "com.example.shelfplayer.buildlogic.JvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "shelfplayer.quality"
            implementationClass = "com.example.shelfplayer.buildlogic.QualityConventionPlugin"
        }
    }
}
