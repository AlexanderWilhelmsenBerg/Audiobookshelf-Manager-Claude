plugins {
    id("shelfplayer.jvm.library")
}

/**
 * PRODUCT_SPEC 9.1 — shared test utilities.
 *
 * This is a JVM module so that `:domain` and `:core:model` (which must never see Android) can use
 * it. Android-specific helpers such as an in-memory Room builder live next to the module that needs
 * them rather than being forced in here.
 */
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.junit4)
    api(libs.kotlin.test)
    api(libs.kotlinx.coroutines.test)
}
