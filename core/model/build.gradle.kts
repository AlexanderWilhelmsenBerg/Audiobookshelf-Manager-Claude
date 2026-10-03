plugins {
    id("shelfplayer.jvm.library")
    // PRODUCT_SPEC 17.3 — this module contributes coverage data; the thresholds live in the root build.
    alias(libs.plugins.kover)
}

/*
 * PRODUCT_SPEC 9.3 — the model layer has no dependencies at all.
 *
 * Keeping `:core:model` on the plain Kotlin/JVM plugin means an accidental `import android.*` in a
 * domain model is a compile error rather than something a reviewer has to notice.
 */

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
