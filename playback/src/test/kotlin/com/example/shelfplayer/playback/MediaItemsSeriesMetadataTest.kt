package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Device regression from PR #78: Android Auto's Now Playing surface rendered title + artist only. */
@RunWith(RobolectricTestRunner::class)
class MediaItemsSeriesMetadataTest {

    @Test
    fun `series and sequence are exposed in the live media metadata byline`() {
        val metadata = MediaItems.queueFor(session(seriesLabel = "The Long Voyage #3")).item.mediaMetadata

        assertEquals("Book", metadata.title?.toString())
        assertEquals("Marisol Holt • The Long Voyage #3", metadata.artist?.toString())
        assertEquals("The Long Voyage #3", metadata.subtitle?.toString())
        assertEquals("The Long Voyage #3", metadata.albumTitle?.toString())
    }

    @Test
    fun `book outside a series keeps the existing author metadata`() {
        val metadata = MediaItems.queueFor(session(seriesLabel = null)).item.mediaMetadata

        assertEquals("Marisol Holt", metadata.artist?.toString())
        assertNull(metadata.subtitle)
        assertEquals("Book", metadata.albumTitle?.toString())
    }

    private fun session(seriesLabel: String?) = PlaybackSession(
        id = "session-a",
        profileId = ProfileId("profile-a"),
        bookId = LibraryItemId("book-a"),
        title = "Book",
        author = "Marisol Holt",
        coverUrl = null,
        startAt = Duration.ZERO,
        duration = 10.seconds,
        tracks = listOf(
            PlayableTrack(
                index = 0,
                url = "https://books.example/file/1",
                startOffset = Duration.ZERO,
                duration = 10.seconds,
                mimeType = "audio/mpeg",
                isExcluded = false,
            ),
        ),
        chapters = emptyList(),
        seriesLabel = seriesLabel,
    )
}
