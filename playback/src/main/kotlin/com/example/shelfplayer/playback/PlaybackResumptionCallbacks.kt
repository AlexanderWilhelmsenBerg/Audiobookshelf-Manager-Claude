package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.lock.ProfileLockGuard

/**
 * ROUTE-001 / PLAY-001 / BW-SLEEP-01 - Media3 cold resumption and metadata description.
 * Metadata never opens a server session. Playback uses the same guarded resolution transaction as
 * explicit browse selection and creates no first-Play freshness exemption or physical-key attribution.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackResumptionCallbacks(
    private val lastPlayedBookId: suspend () -> LibraryItemId?,
    private val resumeItem: suspend () -> MediaItem?,
    private val resolution: ControllerQueueResolution,
    private val lock: ProfileLockGuard,
    private val coldResumeStartPosition: ColdResumeStartPosition,
    private val logger: Logger,
) {
    /** The `isForPlayback = true` half: open the book and hand back a queue Media3 immediately plays. */
    suspend fun forPlayback(claim: ControllerQueueResolution.Claim): MediaSession.MediaItemsWithStartPosition {
        if (lockedFor(claim)) {
            logger.info(LogCategory.Playback, "A playback resume was suppressed while the account was locked")
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        resolution.requireCurrent(claim)
        val book = lastPlayedBookId()
        resolution.requireCurrent(claim)
        if (lockedFor(claim)) {
            logger.info(LogCategory.Playback, "A playback resume was suppressed after the account locked")
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        val queue = book?.let {
            resolution.resolve(claim, it, startAt = null)
        }
        return if (queue == null) {
            logger.info(LogCategory.Playback, "A resume was requested with nothing to resume")
            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        } else {
            // The future's scoped delivery classifies the following Media3 Play as passive.
            // Issue #138 — fault injection happens after the real `/play` session was staged above, so
            // ResumeFreshnessCoordinator still owns the real trusted/server position. Only Media3's
            // initial local install is replaced with zero, and only this playback-resumption callback
            // can consume the one-shot diagnostic.
            val startPositionMs = coldResumeStartPosition.forPlaybackResumption(queue.startPositionMs)
            logger.info(LogCategory.Playback, "Resuming the last book for a media button")
            MediaSession.MediaItemsWithStartPosition(listOf(queue.item), 0, startPositionMs)
        }
    }

    /**
     * The `isForPlayback = false` half: what *would* resume, described, with nothing opened.
     *
     * The same tile the car's recent root shows, because it is the same question asked by a different
     * surface — see [AutoLibrary.resumeItem]. The position travels in the media id rather than in the
     * start position, which Media3 discards on this path; it keeps the item self-describing for a
     * controller that hands it straight back.
     */
    suspend fun describe(claim: ControllerQueueResolution.Claim): MediaSession.MediaItemsWithStartPosition {
        if (lockedFor(claim)) {
            logger.info(LogCategory.Playback, "Resumption metadata was withheld while the account was locked")
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        resolution.requireCurrent(claim)
        val item = resumeItem()
        resolution.requireCurrent(claim)
        if (item != null && lockedFor(claim)) {
            logger.info(LogCategory.Playback, "Resumption metadata was withheld after the account locked")
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        if (item == null) {
            logger.info(LogCategory.Playback, "A resumable book was asked for and there is none")
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        logger.info(LogCategory.Playback, "Described the resumable book without opening a session")
        return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
    }

    private suspend fun lockedFor(claim: ControllerQueueResolution.Claim): Boolean {
        resolution.requireUnchanged(claim)
        val locked = lock.isActiveProfileLocked()
        resolution.requireUnchanged(claim)
        return locked
    }
}
