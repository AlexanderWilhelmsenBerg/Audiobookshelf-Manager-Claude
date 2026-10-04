package com.example.shelfplayer.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** LIB-002 / #227: exercise the pager callback with stable actions, as the production route does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeSelectionRegressionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `return swipe updates selected label with a stable callback`() {
        var axis by mutableStateOf(HomeAxis.Books)
        val actions = noActions().copy(onAxisChanged = { axis = it })
        compose.setContent { HomeScreen(uiState = state(axis = axis), actions = actions) }
        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(HomeAxis.Series, axis)
        compose.onNodeWithText("Series").assertIsSelected()
        // The neighbour may be loading; the pager itself still accepts the return gesture.
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(HomeAxis.Books, axis)
        compose.onNodeWithText("Books").assertIsSelected()
    }

    @Test
    fun `taps and return swipes keep the final destination selected`() {
        var axis by mutableStateOf(HomeAxis.Authors)
        val actions = noActions().copy(onAxisChanged = { axis = it })
        compose.setContent {
            HomeScreen(uiState = state(axis = axis, groups = listOf(genreGroup("Fixture group"))), actions = actions)
        }
        compose.onNodeWithText("Genres").performClick()
        compose.waitForIdle()
        assertEquals(HomeAxis.Genres, axis)
        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(HomeAxis.Authors, axis)
        compose.onNodeWithText("Authors").assertIsSelected()
        compose.onNodeWithText("Books").performClick()
        compose.waitForIdle()
        assertEquals(HomeAxis.Books, axis)
        compose.onNodeWithText("Books").assertIsSelected()
    }

    @Test
    fun `a cancelled drag keeps the original selected destination`() {
        var axis by mutableStateOf(HomeAxis.Books)
        val actions = noActions().copy(onAxisChanged = { axis = it })
        compose.setContent { HomeScreen(uiState = state(axis = axis), actions = actions) }
        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput {
            down(centerRight)
            moveTo(centerRight - Offset(width * 0.28f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(HomeAxis.Books, axis)
        compose.onNodeWithText("Books").assertIsSelected()
    }
}
