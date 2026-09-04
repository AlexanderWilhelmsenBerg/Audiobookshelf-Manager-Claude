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
 * A device run read as a defect and was not one. Pulling back from the leftmost settings tab appeared to
 * arrive at the **Series** axis specifically; the shelf had in fact been on Series when settings was
 * opened, so the gesture had returned to exactly where it came from. The owner confirmed it: *"the app is
 * working as intended — gesturing back from the settings page went back to the page in main view, the
 * previous one."*
 *
 * The test is kept because writing it is what settled the question, and because nothing else covers the
 * behaviour it settles: the whole path through a real `NavHost` — Home, a navigation to Settings, the
 * pull that leaves it, and the axis Home shows afterwards. Two mechanisms could have made a returning
 * reader land somewhere they never chose, and both are now closed by assertion rather than by argument:
 * the gesture reaching Home's pager as well as Settings', and a restored page index overriding the
 * authoritative axis.
 *
 * Three starting axes rather than one because those two failure modes differ by direction — a leaked
 * swipe moves the axis one step, a restored page pins it to whatever was saved — and only a set of
 * starting points can tell them apart. Series itself is deliberately absent: it is the one axis where a
 * pinned page index and a correct return are indistinguishable, which is precisely how the report arose.
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
