package com.example.shelfplayer.core.model.settings

/**
 * PRODUCT_SPEC SET-002 — the resolved device-wide appearance state.
 *
 * This is deliberately storage-agnostic. The DataStore still carries legacy and current fields side by side,
 * but presentation code should not have to know which proto field won or how an unknown stored key falls
 * back. `:data:settings` resolves those details into this model once.
 */
data class AppearanceSettings(
    val theme: AppTheme = AppTheme.Default,
    val accent: AccentScheme = AccentScheme.Default,
    val glassTint: GlassTint = GlassTint.Default,
    val cardGlassTintEnabled: Boolean = true,
    val systemGlassTintEnabled: Boolean = true,
    val textContrast: TextContrast = TextContrast.Default,
    val glassBlurDp: Int = GlassBlur.DEFAULT_DP,
    val backgroundThemes: List<BackgroundTheme> = emptyList(),
    val backgroundThemeId: String? = null,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
) {
    /** The selected bundled pack, or `null` when the app is using one of its plain themes. */
    val backgroundTheme: BackgroundTheme?
        get() = backgroundThemes.firstOrNull { it.id == backgroundThemeId }
}
