package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.ResumeBaseline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-008 / PLAY-009 — everything that has to be told a book changed.
 *
 * Four singletons need the same news, in the same order, every time a session opens: the outbox needs a row
 * before a byte of audio is fetched; the resume baseline stages the server's `/play` position before Media3
 * receives the item; the sleep timer needs the chapters so an end-of-chapter timer knows where the chapter
 * ends; and auto-rewind needs the chapters so a rewind cannot cross a chapter start.
 *
 * Gathered here rather than listed at the call site for two reasons. It keeps the *order* in one place —
 * the outbox row must exist before playback can fail — and it means adding a listener is a change to this
 * class instead of to [PlaybackController]'s constructor, which is what pushed that constructor past
 * detekt's parameter limit in the first place.
 */
@Singleton
class BookChanges @Inject constructor(
    private val sleepTimer: SleepTimerController,
    private val sessionSync: SessionSyncCoordinator,
    private val autoRewind: AutoRewindController,
    private val resumeBaseline: ResumeBaseline,
) {
    /**
     * A session has been opened for a book. Called before the player is handed the item.
     *
     * The outbox row is written first, deliberately: a session recorded only once playback succeeded would
     * lose the listening of a book that started and then hit a network error (PLAY-005).
     *
     * The `/play` start position is staged **after** that durable-session request and **before** Media3 sees
     * the item. `PlaybackService.onMediaItemTransition` is what promotes it into an acknowledged baseline,
     * because that transition is the first point where both halves are true: the server chose the position
     * and the player actually owns the incoming book. This matters for a profile-restored paused book, which
     * otherwise never goes through play -> pause and therefore had no baseline for its next freshness check.
     *
     * A single-file fallback stages `null`. Its player position is file-relative while the server position is
     * book-relative, so claiming they agree would create exactly the kind of false evidence SYNC-002 avoids.
     */
    fun onBookOpened(session: PlaybackSession) {
        sessionSync.onSessionOpened(session)
        resumeBaseline.stageServerPosition(
            bookId = session.bookId,
            position = MediaItems.serverStartPositionFor(session),
        )
        sleepTimer.onBookChanged(session.chapters)
        autoRewind.onBookChanged(session.chapters)
    }

    /** PRODUCT_SPEC PLAY-004 — "chapter change" is one of the seven sync triggers. */
    fun onChapterCrossed() {
        sessionSync.request(SyncTrigger.ChapterChanged)
    }
}
