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

    /*
     * PRODUCT_SPEC 17.2 / AUTH-005 — the instrumented tier, and the one place in this repository that
     * has a reason to exist.
     *
     * Robolectric has no `AndroidKeyStore` provider, so `KeystoreLockCipher` and everything built on it
     * — which is the whole profile lock's storage — is unreachable from the JVM suite. `docs/risks.md`
     * R-39 has recorded that as untested since the feature landed. These tests run against the real
     * provider on a real device and need nothing else: no Hilt, no UI, no biometric hardware.
     *
     * `:core:testing` is a JVM module, so it serves both tiers. Nothing here needs `junit-ktx`, which is
     * the one androidx.test artifact this project has no verification checksum for.
     */
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
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
