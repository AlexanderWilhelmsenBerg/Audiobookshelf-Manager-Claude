package com.example.shelfplayer.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PD-006: exercise actual Home cards, protecting the distinct genre and author callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeAuthorNavigationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `author card navigates by stable id without narrowing the origin Home state`() {
        var selected: AuthorId? = null
        var focused: BookGroup? = null
        val author = BookGroup(BookGroupKind.Author, "author-id", "Ada Ledger", listOf(book()))
        compose.setContent {
            HomeScreen(
                uiState = state(axis = HomeAxis.Authors, groups = listOf(author)),
                actions = noActions().copy(onAuthorSelected = { selected = it }, onGroupSelected = { focused = it }),
            )
        }
        compose.onNodeWithText("Ada Ledger").performClick()
        assertEquals(AuthorId("author-id"), selected)
        assertNull(focused)
    }

    @Test
    fun `genre card retains focused book results instead of author navigation`() {
        var selected: AuthorId? = null
        var focused: BookGroup? = null
        val genre = genreGroup("Mystery")
        compose.setContent {
            HomeScreen(
                uiState = state(axis = HomeAxis.Genres, groups = listOf(genre)),
                actions = noActions().copy(onAuthorSelected = { selected = it }, onGroupSelected = { focused = it }),
            )
        }
        compose.onNodeWithText("Mystery").performClick()
        assertEquals(genre, focused)
        assertNull(selected)
    }
}
