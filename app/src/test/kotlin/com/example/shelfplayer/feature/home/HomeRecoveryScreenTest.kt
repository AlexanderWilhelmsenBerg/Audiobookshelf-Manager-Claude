package com.example.shelfplayer.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookFocus
import com.example.shelfplayer.domain.library.BookGroupKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LIB-002/spec21, #195: actual Home recovery dispatch and distinguishable rendered status shapes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRecoveryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `no matches clears one constraint at a time without changing library or starting work`() {
        val scoped = library("Fixture library")
        var current by mutableStateOf(
            state(books = emptyList(), scopedTo = scoped).copy(
                query = "unmatched",
                isSearching = true,
                filter = BookFilter.Downloaded,
                focus = BookFocus(BookGroupKind.Genre, "fixture", "Fixture genre"),
            ),
        )
        lateinit var view: View
        var refreshCalls = 0
        var playCalls = 0
        compose.setContent {
            view = LocalView.current
            HomeScreen(
                uiState = current,
                actions = noActions().copy(
                    onQueryChanged = { current = current.copy(query = it) },
                    onFilterChanged = { current = current.copy(filter = it) },
                    onFocusCleared = { current = current.copy(focus = null) },
                    onRefresh = { refreshCalls++ },
                    onBookPlaySelected = { playCalls++ },
                ),
            )
        }
        compose.onNodeWithText("Clear search").assertIsDisplayed()
        capture(view, "recovery-en-375dp")
        compose.onNodeWithText("Clear search").performClick()
        assertEquals("", current.query)
        assertEquals(BookFilter.Downloaded, current.filter)
        assertTrue(current.focus != null)
        compose.onNodeWithText("Reset filters").assertIsDisplayed().performClick()
        assertEquals(BookFilter.All, current.filter)
        assertTrue(current.focus != null)
        compose.onNodeWithText("Clear selection").assertIsDisplayed().performClick()
        assertEquals(null, current.focus)
        assertEquals(scoped, current.scopedTo)
        assertEquals(HomeAxis.Books, current.axis)
        assertTrue(current.isSearching)
        assertEquals(0, refreshCalls)
        assertEquals(0, playCalls)
    }

    @Test
    @Config(qualifiers = "nb-rNO-w320dp-h812dp", fontScale = 2.0f)
    fun `offline Norwegian large text exposes search recovery without requesting refresh`() {
        var query by mutableStateOf("fixture")
        lateinit var view: View
        var refreshCalls = 0
        compose.setContent {
            view = LocalView.current
            HomeScreen(
                uiState = state(isOffline = true, books = emptyList()).copy(query = query),
                actions = noActions().copy(
                    onQueryChanged = { query = it },
                    onRefresh = { refreshCalls++ },
                ),
            )
        }
        compose.onNodeWithText("Tøm søk").assertIsDisplayed()
        capture(view, "recovery-nb-320dp-font2")
        compose.onNodeWithText("Tøm søk").performClick()
        assertEquals("", query)
        compose.onNodeWithText("Ingen tilkobling").assertIsDisplayed()
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `reachable unreachable unknown and offline have different monochrome silhouettes`() {
        var current by mutableStateOf(state())
        lateinit var view: View
        compose.setContent {
            ShelfPlayerTheme(darkTheme = false, dynamicColor = false) {
                view = LocalView.current
                HomeScreen(uiState = current, actions = noActions())
            }
        }
        val states = listOf(
            Triple(ServerStatus.Reachable, false, "Server reachable"),
            Triple(ServerStatus.Unreachable, false, "Server not reachable"),
            Triple(ServerStatus.Unknown, false, "Server status unknown"),
            Triple(ServerStatus.Reachable, true, "Device offline, server status unknown"),
        )
        val masks = states.mapIndexed { index, (status, offline, description) ->
            compose.runOnIdle { current = current.copy(serverStatus = status, isOffline = offline) }
            val node = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
            val bitmap = capture(view, "status-$index")
            val bounds = node.boundsInRoot
            val left = bounds.left.roundToInt()
            val top = bounds.top.roundToInt()
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            val background = bitmap[left, top]
            buildString {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        append(if (bitmap[left + x, top + y] == background) '0' else '1')
                    }
                }
            }.also { assertTrue('1' in it, "Status must draw a visible mark") }
        }
        assertEquals(4, masks.distinct().size, "Connection states need distinct shapes without color")
    }

    private fun capture(view: View, name: String): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            view.draw(canvas)
        }
        val output = File("build/home-recovery-renders").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }
}
