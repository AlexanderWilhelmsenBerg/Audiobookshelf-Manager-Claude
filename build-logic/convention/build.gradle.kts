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
    compileOnly(libs.ksp.gradlePlugin)
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
