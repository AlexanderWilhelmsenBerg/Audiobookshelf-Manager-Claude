package com.example.shelfplayer.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.domain.library.BookGroupKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/** LIB-002 / #227/#228: shareable native screenshots use fixture data, never the owner's catalogue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeBrowseRenderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w375dp-h812dp", fontScale = 1.0f)
    fun `native author header names authors and selects the author destination`() {
        renderAndCapture("authors-en-375dp", "Authors", "1 author")
    }

    @Test
    @Config(qualifiers = "nb-rNO-w375dp-h812dp", fontScale = 2.0f)
    fun `native Norwegian header and selected tab remain readable at large text`() {
        renderAndCapture("authors-nb-375dp-font2", "Forfattere", "1 forfatter")
    }

    private fun renderAndCapture(name: String, label: String, count: String) {
        lateinit var view: View
        compose.setContent {
            ShelfPlayerTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                HomeScreen(
                    uiState = state(
                        axis = HomeAxis.Authors,
                        books = emptyList(),
                        groups = listOf(genreGroup("Fixture author", count = 3).copy(kind = BookGroupKind.Author)),
                    ),
                    actions = noActions(),
                )
            }
        }
        compose.onNodeWithText(label).assertIsSelected()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals(count)
        lateinit var image: Bitmap
        compose.runOnIdle {
            image = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val directory = File("build/browse-screen-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
