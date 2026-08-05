package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRODUCT_SPEC SET-001 / LIB-002 — the settings the app can honour today. */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettings()

    @Test
    fun `the stored choice is what the switch shows`() = runTest {
        settings.setHomeShowsLibraries(true)

        val state = observed(SettingsViewModel(settings))

        assertTrue(state.value.homeShowsLibraries)
        assertTrue(state.value.isLoaded)
    }

    /** PRODUCT_SPEC LIB-002 — the product default is the books, so the toggle starts off. */
    @Test
    fun `the default is the shelf of books`() = runTest {
        val state = observed(SettingsViewModel(settings))

        assertFalse(state.value.homeShowsLibraries)
    }

    @Test
    fun `toggling writes through and comes back`() = runTest {
        val viewModel = SettingsViewModel(settings)
        val state = observed(viewModel)

        viewModel.onHomeShowsLibrariesChanged(true)

        assertTrue(state.value.homeShowsLibraries)
        assertTrue(settings.stored.value)

        viewModel.onHomeShowsLibrariesChanged(false)

        assertFalse(state.value.homeShowsLibraries)
        assertFalse(settings.stored.value)
    }

    /**
     * The switch is disabled until the stored value has been read.
     *
     * Otherwise the first frame shows the default, and a tap in that instant writes the default back
     * over whatever is on disk.
     */
    @Test
    fun `the switch is inert until the store has answered`() {
        assertFalse(SettingsUiState().isLoaded)
    }

    /**
     * Keeps the `WhileSubscribed` state flow hot and returns it.
     *
     * Without a collector the flow never leaves its initial value, and counting emissions instead makes
     * the test depend on how many intermediate states the pipeline happens to produce.
     */
    private fun TestScope.observed(viewModel: SettingsViewModel): StateFlow<SettingsUiState> =
        viewModel.uiState.also { flow ->
            backgroundScope.launch(mainDispatcherRule.testDispatcher) { flow.collect { } }
        }

    private class FakeSettings : SettingsRepository {
        val stored = MutableStateFlow(false)

        override val homeShowsLibraries: Flow<Boolean> = stored

        override suspend fun setHomeShowsLibraries(enabled: Boolean) {
            stored.value = enabled
        }
    }
}
