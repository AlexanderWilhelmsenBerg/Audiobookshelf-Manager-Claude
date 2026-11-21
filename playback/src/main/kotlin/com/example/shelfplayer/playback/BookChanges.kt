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
 * Playback collaborators need the same news, in the same order, every time a session opens: the outbox needs a row
 * before a byte of audio is fetched; the resume baseline stages a real server `/play` position before Media3
 * receives the item; the sleep timer needs the chapters so an end-of-chapter timer knows where the chapter
 * ends; auto-rewind needs the chapters so a rewind cannot cross a chapter start; and service-side History
 * uses the same book/chapter identity to detect crossings with the Activity absent.
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
    private val listeningHistory: ListeningHistoryRecorder,
) {
    /**
     * Commits durable session bookkeeping before publishing the new book to the player.
     * Only a direct phone Play gets the one-shot freshness exemption for its just-opened session.
     */
    internal suspend fun acceptBook(
        session: PlaybackSession,
        stillAuthorized: suspend () -> Boolean,
        initialPlayWillFollow: Boolean = false,
        install: () -> Unit,
    ): Boolean = sessionSync.acceptSession(session, stillAuthorized) {
        listeningHistory.onBookOpened(session.bookId, session.chapters, session.startAt, session.profileId)
        resumeBaseline.stageServerPosition(session.bookId, session.serverAcknowledgedStartPosition())
        resumeFreshness.onSessionOpenedImmediately(session, initialPlayWillFollow)
        sleepTimer.onBookChangedImmediately(session.chapters)
        autoRewind.onBookChanged(session.chapters)
        install()
    }

    /** PRODUCT_SPEC PLAY-004 — "chapter change" is one of the seven sync triggers. */
    fun onChapterCrossed() {
        sessionSync.request(SyncTrigger.ChapterChanged)
    }
}
