package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003, ADR-0016 — turns a book item into one timeline window.
 *
 * ### What it does
 *
 * A book arrives as a single [MediaItem] carrying its track list in extras (see [MediaItems]). This builds a
 * `ConcatenatingMediaSource2` from those tracks: several sources presented to the player as **one period**
 * whose duration is the sum of theirs. The player then reports book-global positions and duration to every
 * controller — the notification, the lock screen, the app's own seek bar — with no conversion anywhere.
 *
 * ### The fallback, and why it is not dead code
 *
 * `ConcatenatingMediaSource2` requires every track's duration up front; that is what lets it present a single
 * window without having prepared anything. The server supplies them, but a book with a track reporting zero
 * cannot be built this way. Rather than fail to play it, such a book falls back to the plain factory — which
 * plays the first file only, and says so in the log.
 *
 * A book that plays its first file is better than a book that does not play, and product priority 1 puts "do
 * not interrupt playback" above "be internally consistent". `BookMediaSourceFactoryTest` covers the branch.
 * **What is not settled** is whether Audiobookshelf can even report a zero duration — no capture has produced
 * one — so the fallback is written defensively rather than to a known case (PRODUCT_SPEC 22.5).
 *
 * ### This KDoc used to call the fallback "honest". It was not (`docs/risks.md` R-61)
 *
 * Falling back changes the player's timeline from the book to one file, and nothing downstream was told.
 * `MediaItems.queueFor` still handed it a whole-book start position, and every position reader still treated
 * `currentPosition` as a book offset — so a 34-hour book resumed at 4 hours seeked past the end of a
 * 20-minute file, and each journal tick wrote that file offset into the book's stored progress. Playback was
 * preserved and progress was silently destroyed, which inverts priorities 1 and 2 rather than choosing
 * between them.
 *
 * It is honest now, in two steps that live elsewhere because that is where the information is:
 *
 *  - `TrackDurations.recovered` computes the missing length from the server's own total, so this branch is
 *    reached only when recovery is *also* impossible — more than one unknown track, or an excluded track
 *    whose contribution to the total no capture has settled (22.4);
 *  - `MediaItems.isSingleFileFallback` is this branch's own condition, exposed so the start position becomes
 *    zero and the two progress writers decline. The session plays and records nothing, which is a
 *    degradation the log names rather than a corruption nobody sees.
 */
@OptIn(UnstableApi::class)
internal class BookMediaSourceFactory(private val dataSourceFactory: DataSource.Factory, private val logger: Logger) :
    MediaSource.Factory {

    private val delegate = DefaultMediaSourceFactory(dataSourceFactory)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        delegate.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        delegate.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tracks = MediaItems.tracksOf(mediaItem)
        if (tracks.isEmpty()) return delegate.createMediaSource(mediaItem)
        if (MediaItems.isSingleFileFallback(tracks.map { it.duration })) {
            /*
             * Named as a warning rather than swallowed: a book playing only its first file is a defect worth
             * seeing in a support report, and the count is safe to log where the URLs are not (14.5).
             *
             * The message says what is *lost*, not just what happened, because this is now the only visible
             * signal for it. `MediaItems.queueFor` has already tried to recover the length from the server's
             * own total (`TrackDurations`), so reaching this line means recovery was refused too; and
             * `MediaItems.isSingleFileFallback` stops the journal and the remote sync writing, so the
             * session plays and records nothing. Somebody reading the log has to be able to tell that from a
             * sync outage.
             */
            logger.warn(
                LogCategory.Playback,
                "A track's length is unknown, so this book plays its first file only and will not save progress",
                LogField.Count("tracks", tracks.size),
            )
            return delegate.createMediaSource(mediaItem)
        }
        return concatenate(mediaItem, tracks)
    }

    /**
     * One source per track, combined into one window.
     *
     * Each part keeps the *book's* [MediaItem] metadata rather than getting its own, so anything that reads
     * the current item — the notification's title, the journal's book id — sees the book. The parts differ
     * only in their URI.
     */
    private fun concatenate(book: MediaItem, tracks: List<MediaItems.Track>): MediaSource {
        val builder = ConcatenatingMediaSource2.Builder()
            .setMediaSourceFactory(delegate)
            // The single item the concatenation presents itself as. Without this the combined source has no
            // media id, and the id is what every position reader in the app uses to name the book.
            .setMediaItem(book)
        tracks.forEach { track ->
            val part = book.buildUpon()
                .setUri(track.url)
                .setMimeType(track.mimeType)
                .build()
            // Milliseconds. `ConcatenatingMediaSource2.Builder.add` converts with `Util.msToUs` itself, and
            // the first build of this passed microseconds — which made every book a thousand times longer
            // than it is and rendered the notification's bar and clocks meaningless. `BookMediaSourceFactoryTest`
            // reads the built timeline back so the unit cannot drift again.
            builder.add(part, track.duration.inWholeMilliseconds)
        }
        logger.info(
            LogCategory.Playback,
            "Built a book as one timeline window",
            LogField.Count("tracks", tracks.size),
            LogField.Millis("duration", tracks.sumOf { it.duration.inWholeMilliseconds }),
        )
        return builder.build()
    }
}
