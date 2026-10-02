package com.example.shelfplayer.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * #174 — what Media3 is told when a car selects an Android Auto Profiles row: the switch happened, and
 * there is no media.
 *
 * [PlaybackService] runs the switch; this turns its outcome into the answer `onSetMediaItems` must return.
 * The answer is **always a failed future**, because that is the one answer Media3 treats as "ignore this
 * request": for a legacy controller such as Android Auto the session does nothing at all, and a Media3
 * controller is told the request is not supported. Either way no media is set or prepared and Play is not
 * called — which is what keeps selecting a profile from starting, replacing or clearing a book.
 *
 * Outside the service so that contract is tested against the pinned Media3 version rather than assumed:
 * see `ProfileRowSelectionTest`.
 */
internal object ProfileRowSelection {

    /**
     * Waits for [switched], hands its result to [onSwitched], then declines the media request.
     *
     * [onSwitched] runs before the answer completes, so a refusal reaches the car before Media3 moves on.
     */
    fun answer(
        switched: ListenableFuture<SessionResult>,
        onSwitched: (SessionResult) -> Unit,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.transformAsync<SessionResult, MediaSession.MediaItemsWithStartPosition>(
            switched,
            { result ->
                onSwitched(result)
                refused()
            },
            MoreExecutors.directExecutor(),
        )

    /** The same answer for a selection that is refused before any switch runs. */
    fun refused(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException(NOT_MEDIA))

    private const val NOT_MEDIA = "A Profiles row selects a profile; it is not media"
}
