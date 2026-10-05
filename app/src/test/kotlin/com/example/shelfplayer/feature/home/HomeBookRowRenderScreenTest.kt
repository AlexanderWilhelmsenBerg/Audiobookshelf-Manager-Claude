package com.example.shelfplayer.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.LibraryItemId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** LIB-002 / 17.3: actual general row caller and fallback pixels, not hardware blur/timing evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeBookRowRenderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w375dp-h812dp", fontScale = 1.0f)
    fun `flat row opens details without issuing play and retains count semantics`() {
        renderAndCapture("flat-book-en-375dp", "Books", "1 book")
    }

    @Test
    @Config(qualifiers = "nb-rNO-w375dp-h812dp", fontScale = 2.0f)
    fun `large text Norwegian flat row keeps title details action and selected axis`() {
        renderAndCapture("flat-book-nb-375dp-font2", "Bøker", "1 bok")
    }

    private fun renderAndCapture(name: String, axis: String, caption: String) {
        lateinit var view: View
        var selected: LibraryItemId? = null
        var playCalls = 0
        val fixture = book().copy(title = "Fixture book", duration = 5.hours)
        compose.setContent {
            ShelfPlayerTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                HomeScreen(
                    uiState = state(books = listOf(fixture), isOffline = false),
                    actions = noActions().copy(
                        onBookSelected = { selected = it },
                        onBookPlaySelected = { playCalls++ },
                    ),
                )
            }
        }
        compose.onNodeWithText(axis).assertIsSelected()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals(caption)
        compose.onNodeWithText("Fixture book").assertIsDisplayed().performClick()
        assertEquals(fixture.id, selected)
        assertEquals(0, playCalls)
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val output = File("build/scroll-screen-evidence").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
