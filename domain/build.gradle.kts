plugins {
    id("shelfplayer.jvm.library")
    id("shelfplayer.hilt")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

/*
 * PRODUCT_SPEC 9.3 — the domain layer depends only on `:core:model` and `:core:common`.
 *
 * It is a JVM module on purpose: policy that decides what to download, when to sync and which
 * permission is missing must be testable without an emulator and must not be able to reach a
 * `Context`.
 */
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    testImplementation(projects.core.testing)
    testImplementation(libs.turbine)
}

/*
 * PRODUCT_SPEC 17.3 — contributes to the root `gate` coverage variant (see the root build file). A JVM
 * module has a single `jvm` variant, so this adds no release work; it exists because a custom variant in
 * the root aggregate must exist under the same name in every project it aggregates.
 */
kover {
    currentProject {
        createVariant("gate") {
            add("jvm")
        }
    }
}
