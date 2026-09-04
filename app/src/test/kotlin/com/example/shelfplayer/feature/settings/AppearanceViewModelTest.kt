package com.example.shelfplayer.feature.settings

import androidx.datastore.core.DataStoreFactory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.ThemeAccents
import com.example.shelfplayer.core.model.settings.ThemeGlass
import com.example.shelfplayer.core.model.settings.ThemeGround
import com.example.shelfplayer.core.model.settings.ThemeSurfaces
import com.example.shelfplayer.core.model.settings.ThemeText
import com.example.shelfplayer.core.testing.MainDispatcherRule
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

/**
 * PRODUCT_SPEC SET-002 (Appearance) — the two writes one press makes, and the one it declines to make.
 *
 * ### Why this exists, and why against the real store
 *
 * `AccentScheme.following` is a complete truth table with its own passing tests, and none of them can say
 * that anything **calls** it. That is `docs/risks.md` R-43 and R-100 in one sentence: correct logic behind
 * a caller that never reaches it, which this project has shipped twice. So these drive the view model and
 * read back what actually landed in the store.
 *
 * The store is a real `DataStore` over a temporary file rather than a fake, because the fake would be the
 * thing under test — the property being checked is that *two* writes to *one* store leave it consistent,
 * and a double that recorded calls could not tell a reader whether the second overwrote the first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * A `TestDispatcher` as `Dispatchers.Main`, which is what makes `viewModelScope` run on the test's own
     * clock rather than on a main looper that does not exist here. `StandardTestDispatcher` rather than
     * the rule's unconfined default on purpose: the ordering of the two writes one press makes is part of
     * what is being checked, and an unconfined dispatcher would run them eagerly and hide it.
     */
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    /** **"Picking a theme chooses the right scheme."** Both writes land, and the pack's accent is one. */
    @Test
    fun `choosing a pack stores the pack and adopts its accent`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onBackgroundThemeChanged(TEAL.id)
        runCurrent()

        val stored = settings.settings.first()
        assertEquals(TEAL.id, stored.backgroundThemeId)
        assertEquals(AccentScheme.of(TEAL).key, stored.accentColorKey)
    }

    /**
     * **"But it should be possible to change the colors."**
     *
     * A colour chosen after the pack survives the pack still being on, and turning the pack off does not
     * reach back and undo it — only an accent that came from a pack is taken back to the default.
     */
    @Test
    fun `a deliberate colour survives the pack, and turning the pack off`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onBackgroundThemeChanged(TEAL.id)
        runCurrent()
        viewModel.onAccentChanged(AccentScheme.of(AccentColor.Plum))
        runCurrent()

        assertEquals(AccentColor.Plum.key, settings.settings.first().accentColorKey)

        viewModel.onBackgroundThemeChanged(null)
        runCurrent()

        val stored = settings.settings.first()
        assertEquals("", stored.backgroundThemeId)
        assertEquals(AccentColor.Plum.key, stored.accentColorKey)
    }

    /** Leaving a pack whose colours were never overridden gives the app its own palette back. */
    @Test
    fun `turning off a pack takes its borrowed accent with it`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onBackgroundThemeChanged(TEAL.id)
        runCurrent()
        viewModel.onBackgroundThemeChanged(null)
        runCurrent()

        assertEquals(AccentScheme.Default.key, settings.settings.first().accentColorKey)
    }

    /** Swapping one pack for another swaps the accent with it, rather than keeping the first pack's. */
    @Test
    fun `swapping packs swaps the accent`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onBackgroundThemeChanged(TEAL.id)
        runCurrent()
        viewModel.onBackgroundThemeChanged(NEBULA.id)
        runCurrent()

        val stored = settings.settings.first()
        assertEquals(NEBULA.id, stored.backgroundThemeId)
        assertEquals(AccentScheme.of(NEBULA).key, stored.accentColorKey)
    }

    /** An id naming a pack this build does not ship changes nothing but the (empty) selection. */
    @Test
    fun `an unknown pack id selects nothing and leaves the accent alone`() = runTest(dispatcher) {
        val settings = settings()
        val viewModel = viewModel(settings)

        viewModel.onBackgroundThemeChanged("solarized")
        runCurrent()

        val stored = settings.settings.first()
        assertEquals("", stored.backgroundThemeId)
        assertEquals("", stored.accentColorKey)
    }

    private fun settings() = AppSettingsDataSource(
        dataStore = DataStoreFactory.create(
            serializer = AppSettingsSerializer(),
            scope = CoroutineScope(dispatcher),
            produceFile = { folder.newFile("appearance-${counter++}.pb") },
        ),
        logger = SilentLogger,
    )

    private fun viewModel(settings: AppSettingsDataSource) = AppearanceViewModel(
        settings = settings,
        backgroundThemes = object : BackgroundThemeCatalog {
            override suspend fun themes(): List<BackgroundTheme> = listOf(TEAL, NEBULA)
            override suspend fun theme(id: String): BackgroundTheme? = themes().firstOrNull { it.id == id }
        },
    )

    private var counter = 0

    /** Nothing here asserts on logging, and a settings write logs on every path. */
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
