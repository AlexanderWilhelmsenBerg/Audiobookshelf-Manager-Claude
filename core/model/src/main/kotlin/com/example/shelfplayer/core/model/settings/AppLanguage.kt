package com.example.shelfplayer.core.model.settings

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the language the app draws itself in.
 *
 * ### Why the list is closed and short
 *
 * Because a language this app has no translation for is not a language it can offer. The entries here are
 * exactly the `values` directories that exist — the default and `values-nb` — and lint's `MissingTranslation`
 * is what keeps them complete. Offering a language and then rendering English would be worse than not
 * offering it.
 *
 * ### Why each name is in its own language
 *
 * "Norsk bokmål", not "Norwegian Bokmål". Somebody looking for their own language recognises its own name,
 * and translating the list means a reader who cannot read the current language cannot find the one they can.
 * That is why [displayName] is a plain constant rather than a string resource: it must **not** change with
 * the active locale. [System] is the exception and is drawn from resources, because "follow the system" is a
 * sentence rather than a name.
 */
enum class AppLanguage(val tag: String, val displayName: String?) {
    /** Whatever the device asks for. The default, and what an empty stored tag means. */
    System(tag = "", displayName = null),
    English(tag = "en", displayName = "English"),
    NorwegianBokmal(tag = "nb", displayName = "Norsk bokmål"),
    ;

    companion object {
        /**
         * The stored tag, as a language.
         *
         * **A tag this build does not recognise reads back as [System].** A downgrade — an app update rolled
         * back, a translation withdrawn — must fall back to the device's language rather than to a
         * `values` directory that is no longer there.
         *
         * Compared on the primary subtag only, so a stored `nb-NO` written by a device that picked the
         * language through Android's own settings still resolves. The tags this app writes never carry a
         * region; the platform's do.
         */
        fun ofTag(tag: String): AppLanguage {
            val primary = tag.substringBefore('-').substringBefore('_').lowercase()
            if (primary.isEmpty()) return System
            return entries.firstOrNull { it.tag.isNotEmpty() && it.tag == primary } ?: System
        }
    }
}
