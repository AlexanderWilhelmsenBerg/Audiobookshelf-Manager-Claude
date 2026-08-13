package com.example.shelfplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 — the media notification, with the book's remaining time on it.
 *
 * ### The problem this solves
 *
 * Media3's notification shows the current *media item's* progress, and this app's playlist is one item per
 * audio file. On a book with a file per chapter the times read as "left in this chapter" — useful, but not
 * the number somebody deciding whether to start another chapter is after. A device run asked for the book's
 * remaining time as well, and this puts it on the second line beside the author.
 *
 * ### Why a wrapper rather than a subclass
 *
 * Both, in fact, and the split is forced by the library.
 * [DefaultMediaNotificationProvider.getNotificationContentText] is `protected` and overridable, which is how
 * the text gets in — but `createNotification` is **final**, so a subclass cannot capture the
 * [MediaNotification.Provider.Callback] that Media3 hands out for posting an updated notification later.
 * That callback is the only public way to refresh a notification between player events, and a countdown
 * needs one. So the subclass supplies the text and this wrapper keeps the callback.
 *
 * ### The refresh is once a minute at most
 *
 * The label is minute-granular, so [refreshIfChanged] does nothing until the minute rolls over. The service
 * calls it on the journal tick it already runs, which means no new timer and at most one notification update
 * per minute of listening — rather than one every five seconds for the life of a session.
 *
 * ### Failure mode
 *
 * Everything here degrades to the notification Media3 would have built anyway: no player, no duration, or an
 * unattached [BookRemaining] all produce the author alone, which is exactly what the default does.
 */
@OptIn(UnstableApi::class)
@Singleton
class BookNotificationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val remaining: BookRemaining,
) : MediaNotification.Provider {

    private val delegate = object : DefaultMediaNotificationProvider(context) {
        /**
         * The author, then the book's remaining time.
         *
         * The author stays first because it is the stable part: a line whose leading text changes every
         * minute is harder to read at a glance than one whose tail does.
         */
        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
            val author = metadata.artist
            val left = remaining.label(context)
            return when {
                left == null -> author
                author.isNullOrBlank() -> left
                else -> context.getString(R.string.player_book_remaining_separator, author, left)
            }
        }
    }

    /**
     * The last request, kept so the notification can be rebuilt without Media3 asking again.
     *
     * This is the same thing the library's own artwork loading does: build now with what is known, and post
     * a better one through the callback when it arrives.
     */
    private var pending: Request? = null

    private var lastLabel: String? = null

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        pending = Request(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)
        lastLabel = remaining.label(context)
        return delegate.createNotification(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    /**
     * Rebuilds the notification when the remaining-time label has changed, and not otherwise.
     *
     * The equality check is what keeps this to one update a minute — and it is also what stops a loop, in the
     * event that posting a notification ever caused Media3 to ask for another one: the second pass would find
     * the same label and return.
     *
     * Main thread, called from the service's journal tick.
     */
    fun refreshIfChanged() {
        val request = pending ?: return
        val label = remaining.label(context) ?: return
        if (label == lastLabel) return
        lastLabel = label
        request.callback.onNotificationChanged(
            delegate.createNotification(
                request.session,
                request.customLayout,
                request.actionFactory,
                request.callback,
            ),
        )
    }

    /** Dropped with the session, so a released session cannot be handed to a callback. */
    fun release() {
        pending = null
        lastLabel = null
    }

    private data class Request(
        val session: MediaSession,
        val customLayout: ImmutableList<CommandButton>,
        val actionFactory: MediaNotification.ActionFactory,
        val callback: MediaNotification.Provider.Callback,
    )
}
