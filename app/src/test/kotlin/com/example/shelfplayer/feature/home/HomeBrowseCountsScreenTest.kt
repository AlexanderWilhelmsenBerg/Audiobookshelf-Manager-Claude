package com.example.shelfplayer.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.domain.library.BookFocus
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.SeriesShelf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** LIB-001/002, AUTH-002 / #228: displayed noun, scoped count and partial/offline status. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeBrowseCountsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `counts distinct entities rather than shared book memberships including zero`() {
        HomeAxis.entries.forEach { axis ->
            assertEquals(0, state(axis = axis, books = emptyList()).visibleEntityCount)
        }
        val series = seriesRow()
        assertEquals(1, state(axis = HomeAxis.Series, series = listOf(series, series)).visibleEntityCount)
        val group = genreGroup("Fixture group", count = 3)
        assertEquals(1, state(axis = HomeAxis.Authors, groups = listOf(group, group)).visibleEntityCount)
        assertEquals(1, state(axis = HomeAxis.Genres, groups = listOf(group, group)).visibleEntityCount)
        assertEquals(1, state(books = listOf(book(), book())).visibleEntityCount)
    }

    @Test
    fun `focused book results and profile rows replace the previous count`() {
        var shown by mutableStateOf(
            state(axis = HomeAxis.Authors, groups = listOf(genreGroup("Fixture A"), genreGroup("Fixture B"))),
        )
        compose.setContent { HomeScreen(uiState = shown, actions = noActions()) }
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("2 authors")
        shown = state().copy(focus = BookFocus(BookGroupKind.Author, "fixture-author", "Fixture author"))
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("1 book")
        shown = state(profile = profile().copy(id = com.example.shelfplayer.core.model.ProfileId("fixture-next")))
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("1 book")
    }

    @Test
    fun `partial and offline captions keep their caveat and the current entity`() {
        var shown by mutableStateOf(
            state(axis = HomeAxis.Series, series = listOf(seriesRow()), syncStatus = SyncStatus.PartiallySucceeded),
        )
        compose.setContent { HomeScreen(uiState = shown, actions = noActions()) }
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG)
            .assertTextEquals("1 series. The last refresh could not reach all of them.")
        shown =
            state(axis = HomeAxis.Authors, groups = listOf(genreGroup("Fixture author", count = 3)), isOffline = true)
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("1 author. Offline — showing cached library")
        shown = shown.copy(isOffline = false, isLoaded = false)
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "nb-rNO")
    fun `Norwegian uses localized singular plural and partial forms`() {
        var shown by mutableStateOf(
            state(axis = HomeAxis.Genres, groups = listOf(genreGroup("Fixture genre", count = 3))),
        )
        compose.setContent { HomeScreen(uiState = shown, actions = noActions()) }
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("1 sjanger")
        shown = state(axis = HomeAxis.Authors, groups = listOf(genreGroup("Fixture A"), genreGroup("Fixture B")))
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("2 forfattere")
        shown = state(axis = HomeAxis.Series, series = listOf(seriesRow()), syncStatus = SyncStatus.PartiallySucceeded)
        compose.waitForIdle()
        compose.onNodeWithTag(
            HOME_SYNC_STATUS_TEST_TAG,
        ).assertTextEquals("1 serie. Den siste oppdateringen nådde ikke alle.")
    }

    private fun seriesRow() = SeriesShelf(
        series = Series(book().serverId, SeriesId("fixture-series"), "Fixture series"),
        books = listOf(book(), book().copy(id = com.example.shelfplayer.core.model.LibraryItemId("fixture-next"))),
    )
}
