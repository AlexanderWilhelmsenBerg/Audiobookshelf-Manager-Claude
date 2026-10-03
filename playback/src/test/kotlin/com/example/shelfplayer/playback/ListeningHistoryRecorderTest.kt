package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ListeningHistoryRecorderTest {
    @Test
    fun `ordinary listening records the chapter crossing and exact captured sample without an activity`() = runTest {
        val history = History()
        val recorder = ListeningHistoryRecorder(history)
        val chapters = listOf(chapter(1, Duration.ZERO, 20.seconds), chapter(2, 20.seconds, 40.seconds))
        recorder.onBookOpened(BOOK, chapters, 10.seconds, OWNER)
        recorder.sample(BOOK, 15.seconds, AT, OWNER)
        recorder.sample(BOOK, 20.seconds, AT.plusSeconds(5), OWNER)
        recorder.sample(BOOK, 25.seconds, AT.plusSeconds(10), OWNER)
        assertEquals(
            listOf(
                PlaybackEvent.ListeningProgress,
                PlaybackEvent.ChapterCrossed,
                PlaybackEvent.ListeningProgress,
                PlaybackEvent.ListeningProgress,
            ),
            history.rows.map { it.event },
        )
        assertEquals(20.seconds, history.rows[1].to)
        assertEquals(AT.plusSeconds(5), history.rows[1].at)
        assertEquals(listOf<ProfileId?>(OWNER, OWNER, OWNER, OWNER), history.owners)
    }

    @Test
    fun `book replacement and missing chapters cannot invent crossings`() = runTest {
        val history = History()
        val recorder = ListeningHistoryRecorder(history)
        recorder.onBookOpened(BOOK, listOf(chapter(1, Duration.ZERO, 20.seconds)), 10.seconds, OWNER)
        recorder.onBookOpened(BOOK, emptyList(), Duration.ZERO, OWNER)
        recorder.sample(BOOK, 30.seconds, AT, OWNER)
        assertEquals(listOf(PlaybackEvent.ListeningProgress), history.rows.map { it.event })
    }

    private fun chapter(id: Int, start: Duration, end: Duration) =
        Chapter(ServerId("server"), BOOK, id, "Chapter $id", start, end)

    @Test
    fun `captured crossing retains the old account and chapter through replacement before persistence`() = runTest {
        val history = History()
        val recorder = ListeningHistoryRecorder(history)
        val chapters = listOf(chapter(1, Duration.ZERO, 20.seconds), chapter(2, 20.seconds, 40.seconds))
        recorder.onBookOpened(BOOK, chapters, 10.seconds, OWNER)
        val captured = recorder.capture(BOOK, 25.seconds, AT, OWNER)
        recorder.onBookOpened(BOOK, emptyList(), Duration.ZERO, ProfileId("other"))
        recorder.persist(captured)
        assertEquals(
            listOf(PlaybackEvent.ChapterCrossed, PlaybackEvent.ListeningProgress),
            history.rows.map {
                it.event
            },
        )
        assertEquals(listOf<ProfileId?>(OWNER, OWNER), history.owners)
    }

    private class History : PlaybackHistoryRepository {
        val rows = mutableListOf<PlaybackHistoryEntry>()
        val owners = mutableListOf<ProfileId?>()
        override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> = emptyFlow()
        override suspend fun record(
            bookId: LibraryItemId,
            event: PlaybackEvent,
            from: Duration?,
            to: Duration,
            detail: Duration?,
            at: Instant?,
            owner: ProfileId?,
        ) {
            rows += PlaybackHistoryEntry(rows.size.toString(), event, from, to, detail, requireNotNull(at))
            owners += owner
        }
        override suspend fun refreshServerSessions(bookId: LibraryItemId) = Unit
        override suspend fun clear(bookId: LibraryItemId) = Unit
    }

    private companion object {
        val BOOK = LibraryItemId("book")
        val OWNER = ProfileId("owner")
        val AT: Instant = Instant.parse("2026-10-03T20:00:00Z")
    }
}
