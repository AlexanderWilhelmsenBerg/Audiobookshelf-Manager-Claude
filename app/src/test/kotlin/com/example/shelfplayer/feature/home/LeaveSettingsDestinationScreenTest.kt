package com.example.shelfplayer.feature.home

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.shelfplayer.feature.settings.PlaybackSettingsActions
import com.example.shelfplayer.feature.settings.ServerTabInputs
import com.example.shelfplayer.feature.settings.SettingsScreen
import com.example.shelfplayer.feature.settings.SettingsUiState
import com.example.shelfplayer.feature.settings.SleepTimerSettingsActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-002 / 16.2 — leaving settings by the gesture lands on the axis the shelf was left on.
 *
 * ### Why this exists
 *
 * A device reported that pulling back from the leftmost settings tab arrived at the **Series** axis
 * specifically, rather than wherever the shelf had been. Series is `HomeAxis.entries[1]`, so an axis the
 * reader never chose can only come from the pager's page index disagreeing with the authoritative axis —
 * either the gesture reaching Home as well as Settings, or the page being restored over the axis.
 *
 * These three cases exercise the whole path through a real `NavHost`: Home, a navigation to Settings, the
 * pull that leaves it, and the axis Home is showing afterwards. **They pass**, on Books, Authors and
 * Genres alike, which is worth stating plainly: this harness does not reproduce the report, so the case
 * that does is still unknown. What it does do is close the two mechanisms above, so neither can be the
 * cause silently, and pin the destination against a future regression.
 *
 * Three starting axes rather than one because the failure modes differ by direction: a leaked swipe would
 * move the axis one step, and a restored page would pin it to whatever was saved, and only a set of
 * starting points can tell those apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LeaveSettingsDestinationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun probe(start: HomeAxis): HomeAxis {
        var axis by mutableStateOf(start)
        compose.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        uiState = state(axis = axis, groups = listOf(genreGroup("Ursula Le Guin"))),
                        actions = remember { noActions() }.copy(onAxisChanged = { settled -> axis = settled }),
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        uiState = SettingsUiState(),
                        serverTab = ServerTabInputs(),
                        sleepTimerActions = SleepTimerSettingsActions(
                            onDefaultChanged = {},
                            onFadeChanged = {},
                            onShakeChanged = {},
                            onRewindOnStopChanged = {},
                        ),
                        playbackActions = PlaybackSettingsActions(
                            onSpeedChanged = {},
                            onSkipsChanged = {},
                            onAutoRewindChanged = {},
                            onBufferChanged = {},
                        ),
                        onNavigateUp = { nav.navigateUp() },
                    )
                }
            }
            LaunchedEffect(Unit) { nav.navigate("settings") }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        return axis
    }

    @Test
    fun `leaving settings from Books lands back on Books`() {
        assertEquals(HomeAxis.Books, probe(HomeAxis.Books))
    }

    @Test
    fun `leaving settings from Authors lands back on Authors`() {
        assertEquals(HomeAxis.Authors, probe(HomeAxis.Authors))
    }

    @Test
    fun `leaving settings from Genres lands back on Genres`() {
        assertEquals(HomeAxis.Genres, probe(HomeAxis.Genres))
    }
}
