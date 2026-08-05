plugins {
    id("shelfplayer.jvm.library")
    id("shelfplayer.hilt")
}

/**
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
