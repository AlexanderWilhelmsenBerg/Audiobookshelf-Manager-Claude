package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlayableTrack
import com.example.shelfplayer.core.model.playback.PlaybackSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class RememberedBookRecorderTest {

    @Test
    fun `actual local playback remembers the owned book`() = runTest {
        val repository = FakeRememberedBooks()
        val recorder = RememberedBookRecorder(
            rememberedBooks = repository,
            applicationScope = backgroundScope,
        )

        recorder.onPlaying(MediaItems.queueFor(session()).item)

        assertEquals(BOOK, repository.valueFor(OWNER))
    }

    @Test
    fun `an item without profile ownership cannot claim a remembered book`() = runTest {
        val repository = FakeRememberedBooks()
        val recorder = RememberedBookRecorder(repository, backgroundScope)

        recorder.onPlaying(MediaItem.Builder().setMediaId(BOOK.value).build())

        assertNull(repository.valueFor(OWNER))
    }

    private fun session() = PlaybackSession(
        profileId = OWNER,
        id = "session-1",
        bookId = BOOK,
        title = "Book A",
        author = "Author",
        coverUrl = null,
        startAt = 10.minutes,
        duration = 60.minutes,
        tracks = listOf(
            PlayableTrack(
                index = 0,
                url = "https://books.example/audio.mp3",
                startOffset = kotlin.time.Duration.ZERO,
                duration = 60.minutes,
                mimeType = "audio/mpeg",
                isExcluded = false,
            ),
        ),
        chapters = listOf(
            Chapter(SERVER, BOOK, 0, "One", kotlin.time.Duration.ZERO, 60.minutes),
        ),
    )

    private companion object {
        val OWNER = ProfileId("profile-1")
        val BOOK = LibraryItemId("book-a")
        val SERVER = ServerId("server-1")
    }
}
