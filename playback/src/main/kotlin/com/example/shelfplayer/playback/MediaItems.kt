package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
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

    /**
     * PRODUCT_SPEC 6.5 — the profile whose session produced this item.
     *
     * ### Why the owner travels with the book rather than being looked up
     *
     * Everything the player writes about a loaded book — a journaled position, a play, a pause — belongs to
     * the account that opened it. The writers cannot ask who that was: a journal tick, a headset press and a
     * car's transport control all arrive with a `MediaItem` and nothing else, and the obvious answer, "the
     * profile that is active right now", is wrong for the few hundred milliseconds either side of a switch.
     * A five-second journal against a microsecond switch is a race that loses somebody's position onto
     * somebody else's row, which is product priority 4 and not a cosmetic one.
     *
     * Putting it in the extras makes the item a complete description of *whose* book it is, for the same
     * reason [KEY_TRACK_URLS] makes it a complete description of what it plays: it survives process death,
     * a media button, and a controller that has never heard of this app's session types.
     */
    const val KEY_OWNER_PROFILE_ID = "com.example.shelfplayer.playback.OWNER_PROFILE_ID"

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
            putString(KEY_OWNER_PROFILE_ID, session.profileId.value)
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
     * PRODUCT_SPEC 6.5 — the profile that opened this item, or `null` when the item did not come from
     * [queueFor].
     *
     * `null` is not a failure and must not stop a write. It means the caller has no better answer than the
     * active profile, which is what every write did before the owner existed — so the fallback is exactly
     * today's behaviour rather than a new way to lose a position (product priority 2).
     */
    fun ownerOf(item: MediaItem): ProfileId? =
        item.mediaMetadata.extras?.getString(KEY_OWNER_PROFILE_ID)?.takeIf(String::isNotBlank)?.let(::ProfileId)

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
     * Whether [item] can be handed to the player as it stands, with nothing left to look up.
     *
     * ### The guard whose absence broke book switching
     *
     * A `MediaSession` callback sees two kinds of caller. A **browser** — Android Auto, an assistant — hands
     * back only the media id the app put in its browse tree, and that id has to be resolved into an open
     * session before anything can play. The **app itself** hands back an item [queueFor] built from a session
     * it has already opened, and the only correct thing to do with that is to give it straight back.
     *
     * Wave 5 added a callback for the first case that answered the second with `null`: the browse tree's
     * `resolve` knows `book/…` and `at/…`, an app item's id is the bare book id, so every app-initiated play
     * resolved to nothing, the player was handed an empty list, and it kept playing what it already had. The
     * device report was *"I press book b, but book A continues"*.
     *
     * ### Why these two conditions
     *
     * `localConfiguration != null` is Media3's own test, not an invention: the default
     * `MediaSession.Callback.onAddMediaItems` passes a list through unchanged exactly when every item has one,
     * and fails otherwise. That default is what carried this app until an override replaced it, so matching it
     * restores the behaviour that worked. The track list is checked as well because it is what
     * [BookMediaSourceFactory] actually reads — a book whose extras describe its tracks is playable whether or
     * not anything kept the URI.
     */
    fun isReadyToPlay(item: MediaItem): Boolean = item.localConfiguration != null || tracksOf(item).isNotEmpty()

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
