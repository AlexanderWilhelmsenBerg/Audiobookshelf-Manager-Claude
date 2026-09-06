package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppearanceSettings
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeChoice
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC SET-002 / 9.3 — the device-wide appearance settings as domain values.
 *
 * DataStore/protobuf compatibility belongs below this boundary. Both the application shell and the settings
 * screen consume the same resolved state so there is one interpretation of legacy theme fields and bundled
 * theme keys rather than two presentation-layer copies.
 */
interface AppearanceRepository {
    fun observeAppearance(): Flow<AppearanceSettings>

    suspend fun setThemeChoice(choice: ThemeChoice): AppResult<Unit>
    suspend fun setAccent(accent: AccentScheme): AppResult<Unit>
    suspend fun setGlassTint(tint: GlassTint): AppResult<Unit>
    suspend fun setCardGlassTintEnabled(enabled: Boolean): AppResult<Unit>
    suspend fun setSystemGlassTintEnabled(enabled: Boolean): AppResult<Unit>
    suspend fun setTextContrast(contrast: TextContrast): AppResult<Unit>
    suspend fun setGlassBlurDp(dp: Int): AppResult<Unit>
    suspend fun setDynamicColor(enabled: Boolean): AppResult<Unit>
    suspend fun setLanguage(language: AppLanguage): AppResult<Unit>
}
