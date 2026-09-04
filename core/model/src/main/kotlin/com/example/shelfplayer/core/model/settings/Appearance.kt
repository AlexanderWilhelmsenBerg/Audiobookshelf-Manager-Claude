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
 * PRODUCT_SPEC SET-002 — the accent actually in force: a built-in pair, or a bundled pack's own.
 *
 * ### Why a value type over the enum rather than more enum entries
 *
 * Because the pack accents are not knowable at compile time. [AccentColor] is a closed palette on purpose
 * and stays one; what a [BackgroundTheme] adds is a pair somebody authored against a specific picture, and
 * the set of those grows when a directory is added to the assets. An enum would have to be edited in step
 * or the new pack's colours would be unreachable — the same argument `app_theme_key` and
 * `library_sort_order` make for storing a key rather than an ordinal.
 *
 * ### Both tones come from the pack, and neither is derived
 *
 * A pack supplies `accent.primary` — a light tone, authored to sit on its own dark artwork — and
 * `accent.primaryContainer`, a dark one. That is exactly the pair [AccentColor] needs, so a pack accent is
 * as legible on a light ground as a built-in is, and nothing here invents a colour by arithmetic.
 * `AccentSchemeTest` measures every bundled pack rather than trusting that sentence.
 *
 * @property key what is stored. A built-in's is [AccentColor.key]; a pack's is its id behind
 *   [THEME_PREFIX], which is why a pack called `teal` could never be mistaken for the built-in *Teal*.
 */
data class AccentScheme(val key: String, val lightArgb: Long, val darkArgb: Long) {

    /** The tone for the ground the reader is actually looking at. */
    fun argbFor(isDark: Boolean): Long = if (isDark) darkArgb else lightArgb

    /** Whether this came from a bundled pack rather than the closed palette. */
    val isFromTheme: Boolean get() = key.startsWith(THEME_PREFIX)

    /** Whether this is [theme]'s own authored accent, which is what the pack is drawn with by default. */
    fun belongsTo(theme: BackgroundTheme): Boolean = key == keyFor(theme)

    companion object {
        /**
         * What a pack's accent key begins with.
         *
         * A prefix rather than a separate stored field, because the choice is *one* choice: a reader picks
         * an accent, and where it came from is a property of the answer rather than a second question. One
         * field also means an unknown key has one fallback path instead of two that can disagree.
         */
        const val THEME_PREFIX: String = "theme:"

        val Default: AccentScheme = of(AccentColor.Default)

        fun of(colour: AccentColor): AccentScheme =
            AccentScheme(key = colour.key, lightArgb = colour.lightArgb, darkArgb = colour.darkArgb)

        /**
         * A pack's own accent.
         *
         * `primaryContainer` is the light-ground tone. The packs are authored dark, so their `primary` is
         * the pale tone that reads on their artwork and the container is the deep one that reads on paper —
         * the same two roles [AccentColor] holds, in the same order.
         */
        fun of(theme: BackgroundTheme): AccentScheme = AccentScheme(
            key = keyFor(theme),
            lightArgb = theme.accents.primaryContainer,
            darkArgb = theme.accents.primary,
        )

        /** Every accent a reader may choose: the closed palette first, then one per bundled pack. */
        fun all(themes: List<BackgroundTheme>): List<AccentScheme> = AccentColor.entries.map(::of) + themes.map(::of)

        /**
         * The stored key, resolved.
         *
         * A key naming a pack this build no longer ships falls back to [Default] rather than to the
         * built-in whose name happens to follow the prefix — [THEME_PREFIX] is what keeps those two
         * namespaces apart, and losing a pack must not silently repaint the app in a colour nobody chose.
         */
        fun ofKey(key: String, themes: List<BackgroundTheme>): AccentScheme = when {
            key.startsWith(THEME_PREFIX) ->
                themes.firstOrNull { theme -> keyFor(theme) == key }?.let(::of) ?: Default

            else -> of(AccentColor.ofKey(key))
        }

        /**
         * The accent to write when the background theme changes, or `null` to leave the choice alone.
         *
         * *"Picking a theme chooses the right scheme, but it should be possible to change the colors."*
         * Both halves are here. Choosing a pack adopts its authored accent, because a pack is a picture and
         * a palette chosen against it and offering one without the other is offering half of it. Choosing
         * **None** only undoes that adoption — it returns to [Default] when the accent in force came from a
         * pack, and leaves a deliberate choice of *Plum* exactly where the reader put it.
         */
        fun following(current: AccentScheme, theme: BackgroundTheme?): AccentScheme? = when {
            theme != null -> of(theme).takeIf { next -> next != current }
            current.isFromTheme -> Default
            else -> null
        }

        private fun keyFor(theme: BackgroundTheme): String = THEME_PREFIX + theme.id
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

/**
 * PRODUCT_SPEC 2.10 (Appearance/accessibility) — how strongly the app's text stands off its ground.
 *
 * ### Why this is a contrast level and not a colour
 *
 * Because a colour is the bug. A transparent `Scaffold` container silently defaulted every word on the
 * screen to black, which on the AMOLED theme is black on black, and a device reported it as *"I can't
 * read"*. Offering *black* as a choice would put that exact state back within one tap, on purpose, with
 * no way for the app to tell the difference between a mistake and a preference.
 *
 * A level cannot do that. [High] is whichever of black or white reads on the current ground, [Soft]
 * moves towards the ground without reaching it, and [Automatic] is the scheme's own pairing. Every one
 * of the three is legible on every theme, which is the property a colour picker cannot promise.
 *
 * @property contrast how far from the ground the text sits, or `null` to leave the scheme's own choice
 *   alone. `1.0` is the furthest the ground allows — see `ShelfPlayerTheme`.
 */
enum class TextContrast(val key: String, val contrast: Float?) {
    Automatic(key = "auto", contrast = null),
    High(key = "high", contrast = 1.0f),

    /**
     * Softer than the scheme's own.
     *
     * For reading in the dark, where full contrast on a black ground is the thing that makes a page
     * glare. Deliberately not far enough to be hard to read — see the note on the enum.
     */
    Soft(key = "soft", contrast = 0.72f),
    ;

    companion object {
        val Default: TextContrast = Automatic

        fun ofKey(key: String): TextContrast = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * PRODUCT_SPEC SET-002 (Appearance) — how far the app's frosted surfaces smear what is behind them.
 *
 * ### Why the stored form needs a sentinel
 *
 * Because zero is a legitimate choice here — *no blur, wash only* — and proto3 cannot tell a stored zero
 * from a field nobody has written. So the same trick `sleep_timer_fade_seconds` documents: **-1 is off**
 * and 0 means never chosen, which reads back as [DEFAULT_DP]. Without it, turning the blur off would be
 * indistinguishable from never having touched the slider, and the next build's default would silently
 * turn it back on.
 *
 * [DEFAULT_DP] is the app's long-standing global blur, kept as the default so nobody who never opens the
 * slider sees any change at all.
 */
object GlassBlur {
    /** `GlassDefaults.BlurRadius`, in dp. The one number every frosted surface used before the slider. */
    const val DEFAULT_DP: Int = 28

    /** Past this the blur costs more than it shows, and a card stops reading as being over anything. */
    const val MAX_DP: Int = 48

    private const val OFF_STORED = -1

    /** The radius to draw, from what is on disk. */
    fun ofStored(stored: Int): Int = when {
        stored == OFF_STORED -> 0
        stored <= 0 -> DEFAULT_DP
        else -> stored.coerceAtMost(MAX_DP)
    }

    /** What to store for a chosen radius, so that *off* survives and *unset* stays distinguishable. */
    fun toStored(dp: Int): Int = if (dp <= 0) OFF_STORED else dp.coerceAtMost(MAX_DP)
}
