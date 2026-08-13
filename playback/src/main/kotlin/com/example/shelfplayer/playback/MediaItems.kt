package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003 — an open session as **one** Media3 item.
 *
 * ### A book is one timeline window (ADR-0016)
 *
 * Waves 1–4 built this as a playlist, one item per audio file, with each item's extras carrying its offset on
 * the book's timeline so a global position could be reconstructed. That worked, and it leaked in the one place
 * the app does not control: Media3 reports the *current item's* position and duration to every controller, so
 * the notification and the lock screen described the file rather than the book. On a library with a file per
 * chapter it read as "time left in this chapter".
 *
 * Now the book is a single [MediaItem] whose extras carry the track list, and [BookMediaSourceFactory] turns
 * that into a `ConcatenatingMediaSource2` — several sources presented as one period whose duration is the sum.
 * The player reports book-global positions natively, and nothing in the app converts anything.
 *
 * ### Why the track list travels in extras
 *
 * A `MediaController` can only hand the session `MediaItem`s; it cannot hand it a `MediaSource`. Putting the
 * URLs in the item means the *item* is a complete description of the book, so a controller that has never seen
 * this app's session types — Android Auto, a headset resume, Media3 restoring after process death — can still
 * produce a playable book. A back channel from the app to the service would work today and break all three.
 */
object MediaItems {

    /** The track URLs, in play order. Absent or empty means the item is not one of ours. */
    const val KEY_TRACK_URLS = "com.example.shelfplayer.playback.TRACK_URLS"

    /**
     * Each track's length in milliseconds, parallel to [KEY_TRACK_URLS].
     *
     * `ConcatenatingMediaSource2` needs every duration up front — that is what lets it present one window
     * without having prepared the sources. A track reporting zero is why [BookMediaSourceFactory] keeps a
     * fallback.
     */
    const val KEY_TRACK_DURATIONS_MS = "com.example.shelfplayer.playback.TRACK_DURATIONS_MS"

    /** Each track's mime type, parallel to [KEY_TRACK_URLS]. An entry may be empty when the server sent none. */
    const val KEY_TRACK_MIME_TYPES = "com.example.shelfplayer.playback.TRACK_MIME_TYPES"

    /** A book plus where to start it. */
    data class Queue(val item: MediaItem, val startPositionMs: Long)

    /**
     * Builds the item for [session], positioned at the resume point the server reported.
     *
     * Excluded tracks are dropped rather than skipped at playback time (PLAY-003): a source that does not
     * contain a file cannot play it by accident, from any control surface.
     *
     * The start position needs no conversion any more — it is a book position and the player's timeline is the
     * book, which is the whole point of ADR-0016.
     */
    fun queueFor(session: PlaybackSession): Queue {
        val tracks = session.playableTracks
        val extras = Bundle().apply {
            putStringArray(KEY_TRACK_URLS, tracks.map { it.url }.toTypedArray())
            putLongArray(KEY_TRACK_DURATIONS_MS, tracks.map { it.duration.inWholeMilliseconds }.toLongArray())
            putStringArray(KEY_TRACK_MIME_TYPES, tracks.map { it.mimeType.orEmpty() }.toTypedArray())
        }
        val item = MediaItem.Builder()
            .setMediaId(session.bookId.value)
            // The first track's URI, so an item that somehow reaches a plain media source factory still plays
            // *something* of the right book rather than failing to resolve. The concatenation replaces it.
            .setUri(tracks.firstOrNull()?.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
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
        return Queue(item, session.startAt.inWholeMilliseconds.coerceAtLeast(0))
    }

    /** The book this item belongs to. */
    fun bookIdOf(item: MediaItem): LibraryItemId = LibraryItemId(item.mediaId)

    /**
     * The tracks an item describes, or an empty list when it is not one of ours.
     *
     * Read by [BookMediaSourceFactory]. The three arrays are validated against each other rather than trusted:
     * a truncated `Bundle` would otherwise index out of bounds inside the factory, which runs on the player's
     * thread where an exception stops playback rather than surfacing.
     */
    fun tracksOf(item: MediaItem): List<Track> {
        val extras = item.mediaMetadata.extras ?: return emptyList()
        val urls = extras.getStringArray(KEY_TRACK_URLS).orEmpty()
        val durations = extras.getLongArray(KEY_TRACK_DURATIONS_MS) ?: LongArray(0)
        val mimeTypes = extras.getStringArray(KEY_TRACK_MIME_TYPES).orEmpty()
        if (urls.isEmpty() || urls.size != durations.size || urls.size != mimeTypes.size) return emptyList()
        return urls.indices.map { index ->
            Track(
                url = urls[index],
                duration = durations[index].coerceAtLeast(0).milliseconds,
                mimeType = mimeTypes[index].takeIf(String::isNotBlank),
            )
        }
    }

    /**
     * The book's duration, summed from its tracks.
     *
     * Only used before the player has prepared — once it has, `player.duration` is the same number and is the
     * one every caller should read. This exists so a UI can show a length for a book that is still loading.
     */
    fun bookDurationOf(item: MediaItem): Duration =
        tracksOf(item).fold(Duration.ZERO) { total, track -> total + track.duration }

    /** One audio file, as an item's extras describe it. */
    data class Track(val url: String, val duration: Duration, val mimeType: String?)
}
