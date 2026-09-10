package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.ResumeBaseline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The session start that is actually server evidence.
 *
 * Offline sessions deliberately use a blank id and seed [PlaybackSession.startAt] from local cached progress.
 * They are valid queues, but they cannot say what the server acknowledged because no server session exists.
 */
internal fun PlaybackSession.serverAcknowledgedStartPosition() =
    id.takeIf(String::isNotBlank)?.let { MediaItems.serverStartPositionFor(this) }

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-008 / PLAY-009 — everything that has to be told a book changed.
 *
 * Five singletons need the same news, in the same order, every time a session opens: the outbox needs a row
 * before a byte of audio is fetched; the resume baseline stages a real server `/play` position before Media3
 * receives the item; the sleep timer needs the chapters so an end-of-chapter timer knows where the chapter
 * ends; and auto-rewind needs the chapters so a rewind cannot cross a chapter start.
 *
 * Gathered here rather than listed at the call site for two reasons. It keeps the *order* in one place —
 * the outbox row must exist before playback can fail — and it means adding a listener is a change to this
 * class instead of to [PlaybackController]'s constructor, which is what pushed that constructor past
 * detekt's parameter limit in the first place.
 */
@Singleton
class BookChanges @Inject internal constructor(
    private val sleepTimer: SleepTimerController,
    private val sessionSync: SessionSyncCoordinator,
    private val autoRewind: AutoRewindController,
    private val resumeBaseline: ResumeBaseline,
    private val resumeFreshness: ResumeFreshnessCoordinator,
) {
    /**
     * A session has been opened for a book. Called before the player is handed the item.
     *
     * The outbox row is written first, deliberately: a session recorded only once playback succeeded would
     * lose the listening of a book that started and then hit a network error (PLAY-005). This is suspending
     * because "written first" must be an ordering guarantee, not a coroutine scheduled for later.
     *
     * A **server-backed** `/play` start position is staged after that durable-session request and before
     * Media3 sees the item. `PlaybackService.onMediaItemTransition` promotes it into an acknowledged baseline,
     * because that transition is the first point where both halves are true: the server chose the position
     * and the player actually owns the incoming book. A blank session id means an offline/local session; its
     * `startAt` is cached local progress and must never be promoted as server agreement.
     *
     * A single-file fallback also stages `null`. Its player position is file-relative while the server
     * position is book-relative, so claiming they agree would create exactly the false evidence SYNC-002
     * avoids.
     *
     * [initialPlayWillFollow] is true only when this server session was opened as part of the same action
     * that will immediately issue Play. The coordinator uses that to mint a one-shot first-Play exemption;
     * arm-only sessions pass false so a later Play still performs normal resume freshness.
     */
    suspend fun onBookOpened(session: PlaybackSession, initialPlayWillFollow: Boolean = false) {
        sessionSync.onSessionOpened(session)
        resumeBaseline.stageServerPosition(
            bookId = session.bookId,
            position = session.serverAcknowledgedStartPosition(),
        )
        // Issue #91 — keep the remote ABS session id service-side so realtime evidence can reject
        // BookWave's own sync echo without exposing that identifier through MediaMetadata extras.
        resumeFreshness.onSessionOpened(
            session = session,
            initialPlayWillFollow = initialPlayWillFollow,
        )
        sleepTimer.onBookChanged(session.chapters)
        autoRewind.onBookChanged(session.chapters)
    }

    /** PRODUCT_SPEC PLAY-004 — "chapter change" is one of the seven sync triggers. */
    fun onChapterCrossed() {
        sessionSync.request(SyncTrigger.ChapterChanged)
    }
}
