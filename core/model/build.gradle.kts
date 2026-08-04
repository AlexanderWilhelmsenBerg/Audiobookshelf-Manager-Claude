plugins {
    id("shelfplayer.jvm.library")
}

/**
 * PRODUCT_SPEC 9.3 — the model layer has no dependencies at all.
 *
 * Keeping `:core:model` on the plain Kotlin/JVM plugin means an accidental `import android.*` in a
 * domain model is a compile error rather than something a reviewer has to notice.
 */
