package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.AppearanceSettings
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeChoice
import com.example.shelfplayer.domain.repository.AppearanceRepository
import com.example.shelfplayer.domain.settings.BackgroundThemeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** PRODUCT_SPEC SET-002 / 9.3 — appearance settings with DataStore kept below the data boundary. */
@Singleton
class DefaultAppearanceRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val backgroundThemes: BackgroundThemeCatalog,
    private val logger: Logger,
) : AppearanceRepository {

    private val catalog = flow { emit(backgroundThemes.themes()) }

    override fun observeAppearance(): Flow<AppearanceSettings> = combine(
        settings.settings,
        catalog,
    ) { stored, themes ->
        AppearanceSettings(
            theme = if (stored.appThemeKey.isNotEmpty()) {
                AppTheme.ofKey(stored.appThemeKey)
            } else {
                stored.themeMode.asAppTheme()
            },
            accent = AccentScheme.ofKey(stored.accentColorKey, themes),
            glassTint = GlassTint.ofKey(stored.glassTintKey),
            cardGlassTintEnabled = !stored.cardGlassTintDisabled,
            systemGlassTintEnabled = !stored.systemGlassTintDisabled,
            textContrast = TextContrast.ofKey(stored.textContrastKey),
            glassBlurDp = GlassBlur.ofStored(stored.glassBlurDp),
            backgroundThemes = themes,
            backgroundThemeId = stored.backgroundThemeId.takeIf { id -> themes.any { it.id == id } },
            dynamicColor = stored.dynamicColor,
            language = AppLanguage.ofTag(stored.appLanguageTag),
        )
    }

    override suspend fun setThemeChoice(choice: ThemeChoice): AppResult<Unit> = write {
        // Plain theme first: while a pack is still selected it hides this write, so clearing the pack on the
        // following step reveals the new theme without one frame of the old plain theme.
        if (choice is ThemeChoice.Plain) settings.setAppTheme(choice.theme)
        val pack = (choice as? ThemeChoice.Pack)?.pack
        val themes = backgroundThemes.themes()
        val current = AccentScheme.ofKey(settings.settings.first().accentColorKey, themes)
        AccentScheme.following(current, pack)?.let { next -> settings.setAccent(next) }
        settings.setBackgroundThemeId(pack?.id)
    }

    override suspend fun setAccent(accent: AccentScheme): AppResult<Unit> = write {
        settings.setAccent(accent)
    }

    override suspend fun setGlassTint(tint: GlassTint): AppResult<Unit> = write {
        settings.setGlassTint(tint)
    }

    override suspend fun setCardGlassTintEnabled(enabled: Boolean): AppResult<Unit> =
        write { settings.setCardGlassTintEnabled(enabled) }

    override suspend fun setSystemGlassTintEnabled(enabled: Boolean): AppResult<Unit> =
        write { settings.setSystemGlassTintEnabled(enabled) }

    override suspend fun setTextContrast(contrast: TextContrast): AppResult<Unit> =
        write { settings.setTextContrast(contrast) }

    override suspend fun setGlassBlurDp(dp: Int): AppResult<Unit> = write {
        settings.setGlassBlurDp(dp)
    }

    override suspend fun setDynamicColor(enabled: Boolean): AppResult<Unit> =
        write { settings.setDynamicColor(enabled) }

    override suspend fun setLanguage(language: AppLanguage): AppResult<Unit> =
        write { settings.setAppLanguage(language) }

    private suspend fun write(block: suspend () -> Unit): AppResult<Unit> =
        resultOf(onError = ::storeFailure) { block() }

    private fun storeFailure(throwable: Throwable): AppError {
        logger.warn(LogCategory.Settings, "Appearance settings could not be written", throwable = throwable)
        return AppError.Storage(summary = "The appearance setting could not be saved.")
    }
}

/** Legacy proto brightness used only when an install predates `app_theme_key`. */
private fun ThemeMode.asAppTheme(): AppTheme = when (this) {
    ThemeMode.THEME_MODE_LIGHT -> AppTheme.Light
    ThemeMode.THEME_MODE_DARK -> AppTheme.Dark
    ThemeMode.THEME_MODE_SYSTEM,
    ThemeMode.THEME_MODE_UNSPECIFIED,
    ThemeMode.UNRECOGNIZED,
    -> AppTheme.System
}
