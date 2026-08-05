plugins {
    id("shelfplayer.android.library")
    id("shelfplayer.hilt")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.example.shelfplayer.core.datastore"
}

/**
 * PRODUCT_SPEC SET-001 — settings live in Proto DataStore.
 *
 * Proto rather than Preferences because PRODUCT_SPEC SET-001 also requires versioned, tested
 * settings migration, and a typed schema is what makes "did this field change meaning?" answerable.
 * PRODUCT_SPEC SET-001 additionally forbids storing sensitive values here; tokens go to the
 * Keystore-backed store added in Phase 1, never to this file.
 */
dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    api(libs.androidx.datastore)
    api(libs.protobuf.kotlin.lite)

    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

/**
 * KSP compiles the same source set the protobuf plugin generates into, so the ordering has to be
 * declared. Without this, Gradle reports an implicit-dependency error between `kspDebugKotlin` and
 * `generateDebugProto` — the kind of failure that only shows up on a clean CI checkout.
 */
androidComponents {
    onVariants(selector().all()) { variant ->
        val capitalized = variant.name.replaceFirstChar(Char::uppercase)
        afterEvaluate {
            tasks.matching { it.name == "ksp${capitalized}Kotlin" }.configureEach {
                dependsOn("generate${capitalized}Proto")
            }
        }
    }
}
