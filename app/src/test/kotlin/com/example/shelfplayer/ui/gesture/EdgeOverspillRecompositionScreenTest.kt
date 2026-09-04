package com.example.shelfplayer.ui.gesture

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC 16.2 — the overspill has to survive the recompositions that happen *during* a drag.
 *
 * ### The defect this pins
 *
 * [rememberEdgeOverspill] used to key its `remember` on `onPulledPastStart`. That looks harmless and is
 * not: the callback `SettingsScreen` receives is `navController::navigateUp`, and `NavHostController` is
 * not a stable type, so the compiler cannot memoise the reference and produces a new one on every
 * recomposition. Keyed on it, every recomposition threw away the [EdgeOverspill] holding the gesture —
 * `pulled` back to zero, a fresh `Animatable` at rest — and the page snapped back to its edge mid-drag.
 *
 * A device named the cause by the asymmetry: *"in the settings, gesturing on left on the left most tab
 * don't overspill"*, while Home overspilled correctly. Home is the screen that passes **no** callback, so
 * its key was the constant `null` and its state survived.
 *
 * ### Why identity, and not a pixel offset
 *
 * The state that a drag accumulates lives in the object. If the object survives, the drag survives; if it
 * does not, no amount of offset assertion afterwards can tell you why. Asserting identity states the
 * invariant directly and fails for the actual reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EdgeOverspillRecompositionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a caller handing over a new callback every recomposition keeps the same overspill`() {
        var tick by mutableStateOf(0)
        val seen = mutableListOf<EdgeOverspill>()
        val left = mutableListOf<Int>()
        compose.setContent {
            // Read in composition, so changing it recomposes; captured by the lambda, so the lambda's
            // identity changes with it — the same thing an unstable receiver does to a `::` reference,
            // without needing a NavController to prove it.
            val current = tick
            val overspill = rememberEdgeOverspill(onPulledPastStart = { left += current })
            SideEffect { seen += overspill }
        }

        compose.runOnIdle { tick = 1 }
        compose.runOnIdle { tick = 2 }
        compose.runOnIdle {}

        assertEquals(3, seen.size, "the test needs three compositions to be proving anything")
        assertEquals(1, seen.distinct().size, "the overspill was rebuilt, losing the drag in progress")
    }
}
