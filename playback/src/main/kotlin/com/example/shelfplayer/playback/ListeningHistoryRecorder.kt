package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.domain.playback.GlobalTimeline
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** PLAY-003 / PLAY-004 — service-owned sampling; works without an Activity or internet. Main confined. */
@Singleton
class ListeningHistoryRecorder @Inject constructor(private val history: PlaybackHistoryRepository) {
    private var bookId: LibraryItemId? = null
    private var chapters: List<Chapter> = emptyList()
    private var previous: Chapter? = null
    private var owner: ProfileId? = null

    fun onBookOpened(bookId: LibraryItemId, chapters: List<Chapter>, position: Duration, owner: ProfileId? = null) {
        this.bookId = bookId
        this.chapters = chapters
        previous = GlobalTimeline.chapterAt(chapters, position)
        this.owner = owner
    }

    suspend fun sample(bookId: LibraryItemId, position: Duration, at: Instant, owner: ProfileId?) {
        persist(capture(bookId, position, at, owner))
    }

    /** Capture chapter and account identity before Room work can suspend across a book/profile change. */
    fun capture(bookId: LibraryItemId, position: Duration, at: Instant, owner: ProfileId?): Sample {
        val sameOwner = this.bookId == bookId && this.owner == owner
        val chapter = if (sameOwner) GlobalTimeline.chapterAt(chapters, position) else null
        val crossed = previous != null && chapter != null && chapter != previous
        if (sameOwner) previous = chapter
        return Sample(bookId, position, at, owner, crossed)
    }

    suspend fun persist(sample: Sample) {
        if (sample.crossed) {
            history.record(
                sample.bookId,
                PlaybackEvent.ChapterCrossed,
                null,
                sample.position,
                at = sample.at,
                owner = sample.owner,
            )
        }
        history.recordProgress(sample.bookId, sample.position, sample.at, sample.owner)
    }

    data class Sample(
        val bookId: LibraryItemId,
        val position: Duration,
        val at: Instant,
        val owner: ProfileId?,
        val crossed: Boolean,
    )
}
