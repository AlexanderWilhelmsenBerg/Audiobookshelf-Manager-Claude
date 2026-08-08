package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.playback.GlobalTimeline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003 — an open session as a Media3 playlist.
 *
 * ### One book is one playlist, and every item carries the same media id
 *
 * Media3 does not require ids to be unique within a playlist, and making them unique would cost the one
 * property this design depends on: `player.currentMediaItem.mediaId` is the book being listened to, at
 * any moment, in any track. That is what the progress journal reads, and it keeps working after the app
 * process is gone and only the service is left.
 *
 * ### Why the offsets travel in `extras`
 *
 * The service journals a **global** book position, and to compute one from a player position it needs
 * the current track's start on the book's timeline. It cannot ask the app for it: the app may not be
 * running. Putting the value in each item's metadata means the fact travels with the thing it describes,
 * survives the binder, and survives Media3 restoring a playlist after process death.
 */
object MediaItems {

    /** Milliseconds. The current track's start on the global book timeline. */
    const val KEY_TRACK_START_OFFSET_MS = "com.example.shelfplayer.playback.TRACK_START_OFFSET_MS"

    /** Milliseconds. The whole book's duration, which a single track's does not give. */
    const val KEY_BOOK_DURATION_MS = "com.example.shelfplayer.playback.BOOK_DURATION_MS"

    /** A playlist plus where to start it. */
    data class Queue(val items: List<MediaItem>, val startIndex: Int, val startPositionMs: Long)

    /**
     * Builds the playlist for [session], positioned at the resume point the server reported.
     *
     * Excluded tracks are dropped rather than skipped at playback time (PLAY-003): a playlist that does
     * not contain them cannot play them by accident, and the window indices then line up with the list
     * the timeline arithmetic was done against.
     */
    fun queueFor(session: PlaybackSession): Queue {
        val tracks = session.playableTracks
        val cursor = GlobalTimeline.cursorFor(tracks, session.startAt)
        val items = tracks.map { track ->
            val extras = Bundle().apply {
                putLong(KEY_TRACK_START_OFFSET_MS, track.startOffset.inWholeMilliseconds)
                putLong(KEY_BOOK_DURATION_MS, session.duration.inWholeMilliseconds)
            }
            MediaItem.Builder()
                .setMediaId(session.bookId.value)
                .setUri(track.url)
                .setMimeType(track.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        // The **book's** title and author on every track, not the file's. The
                        // notification and the lock screen show whatever the current item says, and
                        // "03 - Tidewatch.mp3" is not what a listener wants to read there.
                        .setTitle(session.title)
                        .setArtist(session.author)
                        .setAlbumTitle(session.title)
                        .setArtworkUri(session.coverUrl?.let(android.net.Uri::parse))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
        return Queue(items, cursor.index, cursor.offset.inWholeMilliseconds)
    }

    /** The book this item belongs to. */
    fun bookIdOf(item: MediaItem): LibraryItemId = LibraryItemId(item.mediaId)

    /**
     * The global book position for a player position inside [item].
     *
     * The same arithmetic as [GlobalTimeline.positionOf], reached from the side of the boundary that
     * holds a [MediaItem] rather than a track list — which is the side the service is on.
     */
    fun globalPositionOf(item: MediaItem, playerPositionMs: Long): Duration = GlobalTimeline.positionOf(
        trackStartOffset = item.trackStartOffset(),
        offset = playerPositionMs.coerceAtLeast(0).milliseconds,
    )

    /** The whole book's duration, or [Duration.ZERO] when the item does not carry one. */
    fun bookDurationOf(item: MediaItem): Duration =
        (item.mediaMetadata.extras?.getLong(KEY_BOOK_DURATION_MS) ?: 0L).coerceAtLeast(0).milliseconds

    private fun MediaItem.trackStartOffset(): Duration =
        (mediaMetadata.extras?.getLong(KEY_TRACK_START_OFFSET_MS) ?: 0L).coerceAtLeast(0).milliseconds
}
