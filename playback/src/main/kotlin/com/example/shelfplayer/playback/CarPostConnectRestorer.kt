package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.model.LibraryItemId

/**
 * Issue #88 / #185 — installs the last book into an idle player once a car controller is connected.
 *
 * It owns only the decision and the install; every collaborator is a narrow function so the behaviour is
 * testable against a real player without constructing the service. Behaviour is the service's original
 * post-connect behaviour, moved verbatim:
 * - Arm / ArmAndPlay open a playable queue, set it, prepare it, and play only for ArmAndPlay.
 * - None (the Never policy) installs the display-only holder: no prepare, no play, no /play session.
 * - Every install re-checks the profile lock and player emptiness after each suspension, because the
 *   driver or phone may have changed state while a network read was in flight.
 */
internal class CarPostConnectRestorer(
    private val isProfileLocked: () -> Boolean,
    /** The last played book id, after one allowed account refresh when the cache was empty. */
    private val lastPlayedBookId: suspend () -> LibraryItemId?,
    /** The metadata-only holder for the resume candidate, after one allowed account refresh. */
    private val heldResume: suspend () -> AutoLibrary.HeldResume?,
    private val openQueue: suspend (LibraryItemId) -> MediaItems.Queue?,
    private val observer: Observer,
) {
    /** Diagnostics seam; implementations must not affect behaviour. */
    interface Observer {
        /** [player] is null for messages that never carried player fields. */
        fun note(message: String, player: Player?)

        fun installing(direction: String, item: MediaItem, extraFields: List<LogField>)
    }

    /** Applies the policy [action] to [current]. */
    suspend fun apply(action: AutoStartAction, current: Player) {
        when (action) {
            AutoStartAction.ArmAndPlay -> startLastBook(current, play = true)

            AutoStartAction.Arm -> startLastBook(current, play = false)

            AutoStartAction.Suppressed ->
                observer.note("A car connected while the account was locked; nothing started", null)

            // "Never react" means no audio/session side effect. Issue #88 still publishes the last
            // identity so Android Auto is not left in STATE_NONE with an empty playback surface.
            AutoStartAction.None -> holdLastBook(current)
        }
    }

    /** Loads the last played book, playing it or leaving it paused. Silent when there is nothing to load. */
    suspend fun startLastBook(current: Player, play: Boolean) {
        val bookId = lastPlayedBookId()
        if (bookId == null) {
            observer.note("Android Auto post-connect found no resumable book", null)
            return
        }
        if (superseded(current)) {
            observer.note("Android Auto post-connect playable install was superseded", current)
            return
        }
        val queue = openQueue(bookId)
        if (queue == null) {
            observer.note("Android Auto post-connect could not open the resume queue", null)
            return
        }
        if (superseded(current)) {
            observer.note("Android Auto post-connect playable queue was superseded", current)
            return
        }
        observer.installing(
            direction = "installing",
            item = queue.item,
            extraFields = listOf(
                LogField.Public("mode", if (play) "play" else "arm"),
                LogField.Millis("startAt", queue.startPositionMs),
            ),
        )
        current.setMediaItem(queue.item, queue.startPositionMs)
        current.prepare()
        if (play) current.play()
    }

    /**
     * Issue #88 — gives a Never-policy car a current book without opening /play or preparing audio.
     *
     * The second lock/emptiness check is after the possible account reconcile.
     */
    suspend fun holdLastBook(current: Player) {
        if (isProfileLocked()) {
            observer.note("Android Auto held resume was suppressed while locked", null)
            return
        }
        val held = heldResume()
        if (held == null) {
            observer.note("Android Auto held resume candidate was empty", null)
            return
        }
        if (superseded(current)) {
            observer.note("Android Auto held resume install was superseded", current)
            return
        }
        observer.installing(
            direction = "installing-holder",
            item = held.item,
            extraFields = listOf(LogField.Millis("startAt", held.startPositionMs)),
        )
        current.setMediaItem(held.item, held.startPositionMs)
        observer.note("A car connected and the last book was held for display", current)
    }

    private fun superseded(current: Player): Boolean = isProfileLocked() || current.mediaItemCount > 0
}
