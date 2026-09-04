package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SyncTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-008 / PLAY-009 — everything that has to be told a book changed.
 *
 * Three singletons need the same news, in the same order, every time a session opens: the sleep timer needs
 * the chapters so an end-of-chapter timer knows where the chapter ends, the outbox needs a row before a byte
 * of audio is fetched, and auto-rewind needs the chapters so a rewind cannot cross a chapter start.
 *
 * Gathered here rather than listed at the call site for two reasons. It keeps the *order* in one place —
 * the outbox row must exist before playback can fail — and it means adding a fourth listener is a change to
 * this class instead of to [PlaybackController]'s constructor, which is what pushed that constructor past
 * detekt's parameter limit in the first place.
 */
@Singleton
class BookChanges @Inject constructor(
    private val sleepTimer: SleepTimerController,
    private val sessionSync: SessionSyncCoordinator,
    private val autoRewind: AutoRewindController,
) {
    /**
     * A session has been opened for a book. Called before the player is handed the item.
     *
     * The outbox row is written first, deliberately: a session recorded only once playback succeeded would
     * lose the listening of a book that started and then hit a network error (PLAY-005). This is suspending
     * because "written first" must be an ordering guarantee, not a coroutine scheduled for later.
     */
    suspend fun onBookOpened(session: PlaybackSession) {
        sessionSync.onSessionOpened(session)
        sleepTimer.onBookChanged(session.chapters)
        autoRewind.onBookChanged(session.chapters)
    }

    /** PRODUCT_SPEC PLAY-004 — "chapter change" is one of the seven sync triggers. */
    fun onChapterCrossed() {
        sessionSync.request(SyncTrigger.ChapterChanged)
    }
}
