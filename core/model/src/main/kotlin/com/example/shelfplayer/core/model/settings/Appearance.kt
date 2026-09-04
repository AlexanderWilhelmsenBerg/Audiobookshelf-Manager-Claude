package com.example.shelfplayer.core.model.settings

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — which look the app draws itself in.
 *
 * ### Why this exists next to the stored `ThemeMode`
 *
 * The stored enum answers one question — light or dark — and [Amoled] is not an answer to it: it is dark
 * *and* a different palette. Keeping the domain type separate lets a theme carry more than a brightness
 * without every reader of the stored value having to learn about it, and it is what "prepare for more
 * themes" needs: a new look is one entry here plus one palette, and the stored enum gains one value.
 *
 * @property isDark what this theme resolves to, or `null` for [System] — the one entry whose answer is the
 *   device's rather than its own.
 */
enum class AppTheme(val key: String, val isDark: Boolean?) {
    System(key = "system", isDark = null),
    Light(key = "light", isDark = false),
    Dark(key = "dark", isDark = true),

    /**
     * Dark, with true black surfaces.
     *
     * Not a synonym for [Dark] with a darker grey. On an OLED panel a `#000000` pixel is an unlit pixel, so
     * the point of this theme is that large areas of the screen draw no power and show no backlight bleed —
     * which is also why the app's backdrop gradient is suppressed under it, and why the glass tint is worth
     * turning off here specifically: a white wash over black is grey, and grey is lit.
     */
    Amoled(key = "amoled", isDark = true),
    ;

    /** Whether this theme wants the backdrop flat rather than graded. See the note on [Amoled]. */
    val prefersFlatBackdrop: Boolean get() = this == Amoled

    companion object {
        val Default: AppTheme = System

        /** A key no build recognises reads back as [Default], the same rule `AppLanguage.ofTag` follows. */
        fun ofKey(key: String): AppTheme = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * PRODUCT_SPEC SET-002 — the app's accent, chosen from a closed palette.
 *
 * ### Why two tones per entry rather than one colour
 *
 * Because an accent has to stay legible on both grounds, and one hex cannot. Material 3 pairs a primary
 * with an `onPrimary` by tone, not by hue: the light scheme needs a dark accent to sit on a near-white
 * surface, the dark scheme a light one. A single stored colour used on both would be unreadable on one of
 * them — which is the whole reason the shipped palette already had two teals.
 *
 * ### Why a closed palette rather than a colour picker
 *
 * Every entry here is a pair somebody checked for contrast. An arbitrary picked colour is a pair nobody
 * checked, and the failure is not cosmetic: PRODUCT_SPEC 2.10 asks for a contrast floor, and a free picker
 * would let a reader choose a theme that fails it and then read nothing.
 */
enum class AccentColor(val key: String, val lightArgb: Long, val darkArgb: Long) {
    /** The palette the app has always shipped, and still the default. */
    Teal(key = "teal", lightArgb = 0xFF2F5D62, darkArgb = 0xFF9ACACC),
    Indigo(key = "indigo", lightArgb = 0xFF3A4E8F, darkArgb = 0xFFB4C2F7),
    Plum(key = "plum", lightArgb = 0xFF6B3C6E, darkArgb = 0xFFE4B6E6),
    Ember(key = "ember", lightArgb = 0xFF8A4A29, darkArgb = 0xFFFFB68E),
    Moss(key = "moss", lightArgb = 0xFF3B6141, darkArgb = 0xFFA7D4A7),
    Slate(key = "slate", lightArgb = 0xFF43505C, darkArgb = 0xFFB7C5D3),
    ;

    /** The tone for the ground this theme resolves to. */
    fun argbFor(isDark: Boolean): Long = if (isDark) darkArgb else lightArgb

    companion object {
        val Default: AccentColor = Teal

        fun ofKey(key: String): AccentColor = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * PRODUCT_SPEC SET-002 — the colour of the wash over the app's frosted surfaces.
 *
 * The wash is what makes a blur read as *frosted* rather than merely out of focus. It has always been
 * white; this is the choice of what else it may be. [FollowAccent] carries no colour of its own because
 * the accent is a runtime value — see [argbOr].
 */
enum class GlassTint(val key: String, private val argb: Long?) {
    /** What every frosted surface in the app used before this setting existed. */
    White(key = "white", argb = 0xFFFFFFFF),
    Warm(key = "warm", argb = 0xFFFFEBCB),
    Cool(key = "cool", argb = 0xFFCBE6FF),
    FollowAccent(key = "accent", argb = null),
    ;

    /** This tint's colour, or [accentArgb] for the entry that has none of its own. */
    fun argbOr(accentArgb: Long): Long = argb ?: accentArgb

    companion object {
        val Default: GlassTint = White

        fun ofKey(key: String): GlassTint = entries.firstOrNull { it.key == key } ?: Default
    }
}
