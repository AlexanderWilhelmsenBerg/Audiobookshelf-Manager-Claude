package com.example.shelfplayer.feature.settings

import androidx.datastore.core.DataStoreFactory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.ThemeAccents
import com.example.shelfplayer.core.model.settings.ThemeChoice
import com.example.shelfplayer.core.model.settings.ThemeGlass
import com.example.shelfplayer.core.model.settings.ThemeGround
import com.example.shelfplayer.core.model.settings.ThemeSurfaces
import com.example.shelfplayer.core.model.settings.ThemeText
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.data.settings.DefaultAppearanceRepository
import com.example.shelfplayer.domain.settings.BackgroundThemeCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/** PRODUCT_SPEC SET-002 — appearance writes still reach one real DataStore through the repository boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    @Test
    fun `choosing a pack stores the pack and adopts its accent`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(TEAL))
        runCurrent()

        val stored = settings.settings.first()
        assertEquals(TEAL.id, stored.backgroundThemeId)
        assertEquals(AccentScheme.of(TEAL).key, stored.accentColorKey)
    }

    @Test
    fun `a deliberate colour survives the pack, and turning the pack off`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(TEAL))
        runCurrent()
        viewModel.onAccentChanged(AccentScheme.of(AccentColor.Plum))
        runCurrent()
        assertEquals(AccentColor.Plum.key, settings.settings.first().accentColorKey)

        viewModel.onThemeChoiceChanged(ThemeChoice.Plain(AppTheme.Dark))
        runCurrent()

        val stored = settings.settings.first()
        assertEquals("", stored.backgroundThemeId)
        assertEquals(AccentColor.Plum.key, stored.accentColorKey)
    }

    @Test
    fun `turning off a pack takes its borrowed accent with it`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(TEAL))
        runCurrent()
        viewModel.onThemeChoiceChanged(ThemeChoice.Plain(AppTheme.Dark))
        runCurrent()

        assertEquals(AccentScheme.Default.key, settings.settings.first().accentColorKey)
    }

    @Test
    fun `swapping packs swaps the accent`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(TEAL))
        runCurrent()
        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(NEBULA))
        runCurrent()

        val stored = settings.settings.first()
        assertEquals(NEBULA.id, stored.backgroundThemeId)
        assertEquals(AccentScheme.of(NEBULA).key, stored.accentColorKey)
    }

    @Test
    fun `choosing one of the app's own looks writes the theme and clears the pack`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(TEAL))
        runCurrent()
        viewModel.onThemeChoiceChanged(ThemeChoice.Plain(AppTheme.Amoled))
        runCurrent()

        val stored = settings.settings.first()
        assertEquals("", stored.backgroundThemeId)
        assertEquals(AppTheme.Amoled.key, stored.appThemeKey)
    }

    @Test
    fun `a pack does not overwrite the plain theme underneath it`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onThemeChoiceChanged(ThemeChoice.Plain(AppTheme.Amoled))
        runCurrent()
        viewModel.onThemeChoiceChanged(ThemeChoice.Pack(TEAL))
        runCurrent()

        val stored = settings.settings.first()
        assertEquals(TEAL.id, stored.backgroundThemeId)
        assertEquals(AppTheme.Amoled.key, stored.appThemeKey)
    }

    private fun settings() = AppSettingsDataSource(
        dataStore = DataStoreFactory.create(
            serializer = AppSettingsSerializer(),
            scope = CoroutineScope(dispatcher),
            produceFile = { folder.newFile("appearance-${counter++}.pb") },
        ),
        logger = SilentLogger,
    )

    private fun viewModel(settings: AppSettingsDataSource): AppearanceViewModel {
        val catalog = object : BackgroundThemeCatalog {
            override suspend fun themes(): List<BackgroundTheme> = listOf(TEAL, NEBULA)
            override suspend fun theme(id: String): BackgroundTheme? = themes().firstOrNull { it.id == id }
        }
        return AppearanceViewModel(DefaultAppearanceRepository(settings, catalog, SilentLogger))
    }

    private var counter = 0

    private object SilentLogger : Logger {
        override fun log(event: LogEvent) = Unit
    }

    private companion object {
        val TEAL = backgroundTheme(id = "teal_horizon", name = "Teal Horizon", primary = 0xFF57D6CF)
        val NEBULA = backgroundTheme(id = "nebula_glow", name = "Nebula Glow", primary = 0xFF9FCCFF)

        fun backgroundTheme(id: String, name: String, primary: Long) = BackgroundTheme(
            id = id,
            name = name,
            pack = "Test Pack",
            isDark = true,
            ground = ThemeGround(asset = "themes/$id/background.webp", base = 0xFF000000, scrim = 0x80000000),
            surfaces = ThemeSurfaces(
                card = 0xCC101010,
                cardElevated = 0xDD181818,
                navigation = 0xD9101010,
                divider = 0x33FFFFFF,
            ),
            glass = ThemeGlass(tint = 0x33FFFFFF, border = 0x59FFFFFF, blurDp = 24),
            accents = ThemeAccents(
                primary = primary,
                primaryContainer = 0xFF165D5D,
                onPrimary = 0xFF002625,
                secondary = 0xFF96E6CF,
                tertiary = 0xFF7DBFD9,
                error = 0xFFFF8B91,
            ),
            text = ThemeText(
                primary = 0xFFF0FFFD,
                secondary = 0xFFC8E9E5,
                muted = 0xFF90B7B4,
                inverse = 0xFF002625,
            ),
        )
    }
}
