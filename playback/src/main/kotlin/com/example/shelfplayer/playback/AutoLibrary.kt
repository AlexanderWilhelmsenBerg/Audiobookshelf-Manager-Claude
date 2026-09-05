package com.example.shelfplayer.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.booksInSeriesOrder
import com.example.shelfplayer.domain.library.lastPlayedBook
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-001 / 11.1 — the audiobook-first tree Android Auto sees.
 *
 * Android Auto owns the drawing. BookWave owns the information architecture and media metadata. The root is
 * intentionally limited to four stable destinations a driver can learn: Continue, Chapters, History and
 * Library. Broader discovery lives one level below Library instead of competing with the three things used
 * while a book is already playing.
 */
@OptIn(UnstableApi::class)
@Singleton
class AutoLibrary @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val history: PlaybackHistoryRepository,
    private val homeShelves: ObserveHomeShelvesUseCase,
    private val audioOutputs: Outputs,
) {

    /** Narrow seam around the live router so the entire car tree remains JVM-testable. */
    interface Outputs {
        fun available(): List<AudioOutput>
        fun selected(): String?
        fun select(id: String?)
    }

    fun root(): MediaItem = browsableNode(
        id = ROOT,
        title = string(R.string.car_app_name),
        extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        },
    )

    fun recentRoot(): MediaItem = browsableNode(
        id = RECENT_ROOT,
        title = string(R.string.car_tab_continue),
        extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        },
    )

    /**
     * The `series/` and `author/` ids handed to a head unit, so a profile switch can invalidate them.
     *
     * Never cleared: an id dropped here is a car still showing another account's books, while a stale entry
     * only costs one no-op `notifyChildrenChanged`. Concurrent because the browse tree is built off the
     * service's scope while the invalidation loop reads it.
     */
    private val emittedNodes = ConcurrentHashMap.newKeySet<String>()

    private fun remember(id: String): String = id.also(emittedNodes::add)

    fun invalidations(): Flow<Unit> = profiles.observeActiveProfile()
        .map { profile -> profile?.id }
        .distinctUntilChanged()
        .drop(1)
        .map { }

    /**
     * Every parent a head unit may be subscribed to, static rows plus the `series/` and `author/` nodes
     * this process has actually handed out.
     *
     * Media3 does not invalidate descendants when a parent changes, so notifying only the fixed tabs left a
     * car that had drilled into a series or an author still showing the **previous profile's** books
     * (product priority 4). The dynamic ids are remembered as they are emitted because there is no API to
     * ask the session what it is subscribed to.
     */
    fun browsableParents(): List<String> = staticParents() + emittedNodes.toList()

    private fun staticParents(): List<String> = listOf(
        ROOT,
        RECENT_ROOT,
        TAB_CONTINUE,
        TAB_CHAPTERS,
        TAB_HISTORY,
        TAB_LIBRARY,
        TAB_SERIES,
        TAB_AUTHORS,
        TAB_DOWNLOADS,
        TAB_RECENT,
        TAB_DISCOVER,
        TAB_AGAIN,
        TAB_OUTPUT,
    )

    /**
     * A fixed destination first, then the three id families, then nothing.
     *
     * Split in two because the whole tree in one `when` exceeds detekt's branch budget. The exact-id half
     * returns `null` for "not mine" rather than the two halves being reordered: a tab id and a prefixed id
     * are disjoint today, and a split that relied on that would break quietly the day one of them is not.
     */
    suspend fun children(parentId: String, now: NowPlaying?): List<MediaItem> =
        fixedDestination(parentId, now) ?: idFamily(parentId)

    private suspend fun fixedDestination(parentId: String, now: NowPlaying?): List<MediaItem>? = when (parentId) {
        ROOT -> rootTabs()
        RECENT_ROOT -> resumeRow()
        TAB_CONTINUE, TAB_RECENT, TAB_DISCOVER, TAB_AGAIN -> shelfBooks(parentId)
        TAB_CHAPTERS -> chaptersOf(now)
        TAB_HISTORY -> historyOf(now?.bookId)
        TAB_LIBRARY -> librarySections()
        TAB_SERIES -> seriesNodes()
        TAB_AUTHORS -> authorNodes()
        TAB_DOWNLOADS -> downloadedBooks()
        TAB_OUTPUT -> outputRows()
        else -> null
    }

    /**
     * The four rows that are a home shelf shown as a list.
     *
     * One branch in [fixedDestination] rather than four, because four identical shapes are what pushed it
     * over detekt's complexity budget, and because this reads the shelves once instead of once per tab.
     */
    private suspend fun shelfBooks(tabId: String): List<MediaItem> {
        val shelves = shelves()
        return when (tabId) {
            TAB_RECENT -> shelves.recentlyAdded
            TAB_DISCOVER -> shelves.discover
            TAB_AGAIN -> shelves.listenAgain
            else -> shelves.continueListening
        }.map(::bookItem)
    }

    /** Only what is playable with no network. Its own function to keep [fixedDestination] one call per row. */
    private suspend fun downloadedBooks(): List<MediaItem> =
        books().filter { it.localAvailability == LocalAvailability.Complete }.map(::bookItem)

    private suspend fun idFamily(parentId: String): List<MediaItem> = when {
        parentId.startsWith(SERIES_PREFIX) -> booksForSeries(parentId.removePrefix(SERIES_PREFIX))
        parentId.startsWith(AUTHOR_PREFIX) -> booksForAuthor(parentId.removePrefix(AUTHOR_PREFIX))
        parentId.startsWith(OUT_PREFIX) -> chooseOutput(parentId)
        else -> emptyList()
    }

    /** Four stable top-level destinations; empty libraries still explain themselves instead of showing shells. */
    private suspend fun rootTabs(): List<MediaItem> {
        if (books().isEmpty()) return listOf(emptyNotice())
        return listOf(
            tab(TAB_CONTINUE, R.string.car_tab_continue),
            tab(TAB_CHAPTERS, R.string.car_tab_chapters),
            tab(TAB_HISTORY, R.string.car_tab_history),
            tab(TAB_LIBRARY, R.string.car_tab_library),
        )
    }

    /** Broader discovery moves here so the root never exceeds the driver's four learned destinations. */
    private suspend fun librarySections(): List<MediaItem> {
        val all = books()
        val shelves = shelves()
        return buildList {
            if (all.any { it.seriesMemberships.isNotEmpty() }) add(tab(TAB_SERIES, R.string.car_tab_series))
            if (all.any { it.authors.isNotEmpty() }) add(tab(TAB_AUTHORS, R.string.car_tab_authors))
            if (all.any { it.localAvailability == LocalAvailability.Complete }) {
                add(tab(TAB_DOWNLOADS, R.string.car_tab_downloads))
            }
            if (shelves.recentlyAdded.isNotEmpty()) add(tab(TAB_RECENT, R.string.car_tab_recent))
            if (shelves.listenAgain.isNotEmpty()) add(tab(TAB_AGAIN, R.string.car_tab_again))
            if (shelves.discover.isNotEmpty()) add(tab(TAB_DISCOVER, R.string.car_tab_discover))
            // Manual output choice is retained for parked/browse use, but the quick path is the Car/Headset
            // actions on the player. The real router already removes the phone speaker from this list.
            add(tab(TAB_OUTPUT, R.string.car_tab_output))
        }
    }

    private suspend fun seriesNodes(): List<MediaItem> = books()
        .flatMap(Book::seriesMemberships)
        .distinctBy { it.series.id }
        .sortedBy { it.series.name.lowercase() }
        .map { membership ->
            browsableNode(remember("$SERIES_PREFIX${membership.series.id.value}"), membership.series.name)
        }

    private suspend fun booksForSeries(seriesId: String): List<MediaItem> {
        val all = books()
        val membership = all.asSequence()
            .flatMap { it.seriesMemberships.asSequence() }
            .firstOrNull { it.series.id.value == seriesId }
            ?: return emptyList()
        return booksInSeriesOrder(all, membership).map(::bookItem)
    }

    private suspend fun authorNodes(): List<MediaItem> = books()
        .flatMap(Book::authors)
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
        .map { author -> browsableNode(remember("$AUTHOR_PREFIX${author.id.value}"), author.name) }

    private suspend fun booksForAuthor(authorId: String): List<MediaItem> = books()
        .filter { book -> book.authors.any { it.id.value == authorId } }
        .sortedBy { it.title.lowercase() }
        .map(::bookItem)

    /** The browse output list is a second safety boundary: even a fake/stale router cannot surface a speaker. */
    private fun outputRows(): List<MediaItem> {
        val outputs = audioOutputs.available().filterNot(AudioOutput::isSpeaker)
        if (outputs.isEmpty()) return listOf(noticeRow(string(R.string.car_output_none)))
        val chosen = audioOutputs.selected()
        return listOf(
            browsableNode("$OUT_PREFIX$AUTOMATIC_OUTPUT", string(R.string.car_output_automatic)),
        ) + outputs.map { output ->
            browsableNode(
                id = "$OUT_PREFIX${output.id}",
                title = when {
                    output.isActive -> string(R.string.car_output_playing_here, output.displayName)
                    output.id == chosen -> string(R.string.car_output_chosen_unused, output.displayName)
                    else -> output.displayName
                },
            )
        }
    }

    /** A stale speaker row from an old cached tree is refused rather than becoming a hidden back door. */
    private fun chooseOutput(mediaId: String): List<MediaItem> {
        val id = mediaId.removePrefix(OUT_PREFIX)
        if (id == AUTOMATIC_OUTPUT) {
            audioOutputs.select(null)
            return listOf(noticeRow(string(R.string.car_output_automatic_now)))
        }
        val target = audioOutputs.available().firstOrNull { it.id == id && !it.isSpeaker }
            ?: return listOf(noticeRow(string(R.string.car_output_none)))
        audioOutputs.select(target.id)
        return listOf(noticeRow(string(R.string.car_output_chosen, target.displayName)))
    }

    private fun noticeRow(title: String): MediaItem = MediaItem.Builder()
        .setMediaId(NOTICE_OUTPUT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private suspend fun resumeRow(): List<MediaItem> {
        val book = lastPlayed() ?: return emptyList()
        val progress = book.progress ?: return emptyList()
        return listOf(
            playable(
                id = "$AT_PREFIX${book.id.value}/${progress.position.inWholeMilliseconds}",
                title = book.title,
                subtitle = bookSubtitle(book),
                extras = completionExtras(progress.fractionComplete.toDouble()),
            ),
        )
    }

    suspend fun resumeItem(): MediaItem? = resumeRow().firstOrNull()

    private suspend fun shelves(): HomeShelves = homeShelves().first()

    private fun emptyNotice(): MediaItem = MediaItem.Builder()
        .setMediaId(NOTICE_EMPTY)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(string(R.string.car_empty_title))
                .setSubtitle(string(R.string.car_empty_subtitle, string(R.string.car_app_name)))
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    suspend fun item(mediaId: String, now: NowPlaying?): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId == RECENT_ROOT -> recentRoot()
        mediaId.startsWith(TAB_PREFIX) ->
            (children(ROOT, now) + children(TAB_LIBRARY, now)).firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith(SERIES_PREFIX) -> seriesNodes().firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith(AUTHOR_PREFIX) -> authorNodes().firstOrNull { it.mediaId == mediaId }
        else -> resolve(mediaId)?.let { target -> books().firstOrNull { it.id == target.bookId }?.let(::bookItem) }
    }

    /** Voice search includes series in addition to title, author and narrator. */
    suspend fun search(query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return shelves().continueListening.map(::bookItem)
        return books()
            .filter { it.matches(needle) }
            .sortedByDescending { it.progress?.updatedAt }
            .map(::bookItem)
    }

    private fun Book.matches(needle: String): Boolean = title.lowercase().contains(needle) ||
        authors.any { it.name.lowercase().contains(needle) } ||
        narrators.any { it.lowercase().contains(needle) } ||
        seriesMemberships.any { it.series.name.lowercase().contains(needle) }

    suspend fun lastPlayed(): Book? = lastPlayedBook(books())

    private suspend fun books(): List<Book> {
        val profileId = profiles.activeProfileId() ?: return emptyList()
        return library.observeAccessibleBooks(profileId).first()
    }

    private suspend fun bookFor(bookId: LibraryItemId?): Book? {
        val target = bookId ?: lastPlayed()?.id ?: return null
        return books().firstOrNull { it.id == target }
    }

    private suspend fun chaptersOf(now: NowPlaying?): List<MediaItem> {
        val book = bookFor(now?.bookId) ?: return emptyList()
        val profileId = profiles.activeProfileId() ?: return emptyList()
        val chapters = library.observeChapters(profileId, book.id).first()
        val position = positionIn(book, now)
        return listOf(bookProgressRow(book, chapters, position)) +
            chapters.mapIndexed { index, chapter -> chapterRow(book.id, chapter, index, position) }
    }

    private fun positionIn(book: Book, now: NowPlaying?): Duration = when (book.id) {
        now?.bookId -> now.position
        else -> book.progress?.position ?: Duration.ZERO
    }

    private fun chapterRow(bookId: LibraryItemId, chapter: Chapter, index: Int, position: Duration): MediaItem {
        val length = chapter.end - chapter.start
        val elapsed = position - chapter.start
        val fraction = when {
            length <= Duration.ZERO -> null
            position >= chapter.end -> FULLY_PLAYED
            position > chapter.start -> elapsed / length
            else -> null
        }
        return playable(
            id = "$AT_PREFIX${bookId.value}/${chapter.start.inWholeMilliseconds}",
            title = chapter.title.ifBlank { string(R.string.car_chapter_untitled, index + 1) },
            subtitle = if (fraction != null && fraction < FULLY_PLAYED) {
                string(R.string.car_chapter_elapsed, elapsed.asClock(), length.asClock())
            } else {
                length.asClock()
            },
            extras = completionExtras(fraction),
        )
    }

    private fun bookProgressRow(book: Book, chapters: List<Chapter>, position: Duration): MediaItem {
        val duration = book.progress?.duration?.takeIf { it > Duration.ZERO } ?: chapters.lastOrNull()?.end
        val fraction = duration?.takeIf { it > Duration.ZERO }?.let { total -> position / total }
        val index = chapters.indexOfLast { chapter -> position >= chapter.start }
        val subtitle = buildList {
            if (fraction != null) add(string(R.string.car_progress_fraction, (fraction * PERCENT).toInt()))
            if (duration != null) add(string(R.string.car_progress_position, position.asClock(), duration.asClock()))
            if (index >= 0 && chapters.isNotEmpty()) {
                add(string(R.string.car_progress_chapter, index + 1, chapters.size))
            }
        }.joinToString(PART_SEPARATOR)
        return playable(
            id = "$AT_PREFIX${book.id.value}/${position.inWholeMilliseconds}",
            title = book.title,
            subtitle = subtitle.takeIf(String::isNotBlank),
            extras = completionExtras(fraction),
        )
    }

    private fun completionExtras(fraction: Double?): Bundle = Bundle().apply {
        when {
            fraction == null -> putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED,
            )
            fraction >= FULLY_PLAYED -> putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            )
            else -> {
                putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
                )
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, fraction)
            }
        }
    }

    /**
     * History is navigation in the car, not an audit log. Sleep-timer and server-check bookkeeping are
     * deliberately absent; meaningful position decisions and remote-device movement remain tappable.
     */
    private suspend fun historyOf(currentBookId: LibraryItemId?): List<MediaItem> {
        val book = bookFor(currentBookId) ?: return emptyList()
        val profileId = profiles.activeProfileId() ?: return emptyList()
        val chapters = library.observeChapters(profileId, book.id).first()
        // Read deeper than the row budget and cap afterwards. The DAO's limit is SQL, so it applies before
        // this filter, and the events the car drops — Play, the sleep-timer set, the server checks — are the
        // frequent ones. Fifteen rows of those left History empty while navigable seeks sat just below them.
        return history.observe(book.id, limit = CAR_HISTORY_READ).first()
            .filter { it.event.isUsefulInCarHistory }
            .distinctBy { it.event to it.returnTo.inWholeSeconds }
            .take(CAR_HISTORY_LIMIT)
            .map { entry ->
                val chapterIndex = chapters.indexOfLast { chapter -> entry.returnTo >= chapter.start }
                val chapter = chapters.getOrNull(chapterIndex)
                    ?.takeIf { entry.returnTo <= it.end || it.end <= it.start }
                val title = if (chapter == null) {
                    entry.returnTo.asClock()
                } else {
                    val name = chapter.title.ifBlank { string(R.string.car_chapter_untitled, chapterIndex + 1) }
                    "$name$PART_SEPARATOR${(entry.returnTo - chapter.start).asClock()}"
                }
                playable(
                    id = "$AT_PREFIX${book.id.value}/${entry.returnTo.inWholeMilliseconds}",
                    title = title,
                    subtitle = entry.event.carLabel(),
                )
            }
    }

    private val PlaybackEvent.isUsefulInCarHistory: Boolean
        get() = when (this) {
            PlaybackEvent.Play,
            PlaybackEvent.SleepTimerStarted,
            PlaybackEvent.SleepTimerExtended,
            PlaybackEvent.SleepTimerExpired,
            PlaybackEvent.SleepTimerRewind,
            PlaybackEvent.ServerCheckAhead,
            PlaybackEvent.ServerCheckCurrent,
            PlaybackEvent.ServerCheckUnavailable,
            -> false

            // Listed rather than an `else`, so an event added to the enum has to be classified here
            // instead of silently appearing in the car's history.
            PlaybackEvent.Seek,
            PlaybackEvent.Skip,
            PlaybackEvent.Chapter,
            PlaybackEvent.AutoRewind,
            PlaybackEvent.Resume,
            PlaybackEvent.Pause,
            PlaybackEvent.RemoteProgress,
            PlaybackEvent.RemoteFinished,
            PlaybackEvent.ServerSession,
            -> true
        }

    private fun bookItem(book: Book): MediaItem {
        val progress = book.progress
        val fraction = when {
            progress == null -> null
            progress.isFinished -> FULLY_PLAYED
            else -> progress.fractionComplete.toDouble()
        }
        return playable(
            id = "$BOOK_PREFIX${book.id.value}",
            title = book.title,
            subtitle = bookSubtitle(book),
            extras = completionExtras(fraction),
        )
    }

    /** Author first, then the primary/first series and its server-provided sequence. */
    private fun bookSubtitle(book: Book): String? = buildList {
        book.authors.joinToString { it.name }.takeIf(String::isNotBlank)?.let(::add)
        book.seriesMemberships
            .firstOrNull(SeriesMembership::isPrimary)
            .let { it ?: book.seriesMemberships.firstOrNull() }
            ?.let { membership ->
                // `SeriesSequence.Absent.raw` is empty, and "Foundation #" is worse than "Foundation".
                val sequence = membership.sequence.raw.takeIf(String::isNotBlank)
                add(if (sequence == null) membership.series.name else "${membership.series.name} #$sequence")
            }
    }.joinToString(PART_SEPARATOR).takeIf(String::isNotBlank)

    private fun playable(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUri: String? = null,
        extras: Bundle? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .apply { extras?.let(::setExtras) }
                .build(),
        )
        .build()

    private fun Duration.asClock(): String {
        val total = inWholeSeconds.coerceAtLeast(0)
        val hours = total / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun PlaybackEvent.carLabel(): String = string(
        when (this) {
            PlaybackEvent.Seek -> R.string.car_event_seek
            PlaybackEvent.Skip -> R.string.car_event_skip
            PlaybackEvent.Chapter -> R.string.car_event_chapter
            PlaybackEvent.AutoRewind -> R.string.car_event_auto_rewind
            PlaybackEvent.Resume -> R.string.car_event_resume
            PlaybackEvent.Play -> R.string.car_event_play
            PlaybackEvent.Pause -> R.string.car_event_pause
            PlaybackEvent.SleepTimerStarted -> R.string.car_event_timer_started
            PlaybackEvent.SleepTimerExtended -> R.string.car_event_timer_extended
            PlaybackEvent.SleepTimerExpired -> R.string.car_event_timer_expired
            PlaybackEvent.SleepTimerRewind -> R.string.car_event_timer_rewind
            PlaybackEvent.RemoteProgress -> R.string.car_event_remote_progress
            PlaybackEvent.RemoteFinished -> R.string.car_event_remote_finished
            PlaybackEvent.ServerSession -> R.string.car_event_server_session
            PlaybackEvent.ServerCheckAhead -> R.string.car_event_check_ahead
            PlaybackEvent.ServerCheckCurrent -> R.string.car_event_check_current
            PlaybackEvent.ServerCheckUnavailable -> R.string.car_event_check_unavailable
        },
    )

    private fun string(@StringRes id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)
    private fun tab(id: String, @StringRes titleRes: Int): MediaItem = browsableNode(id, string(titleRes))

    companion object {
        data class Target(val bookId: LibraryItemId, val startAt: Duration?)

        fun resolve(mediaId: String): Target? = when {
            mediaId.startsWith(BOOK_PREFIX) -> Target(LibraryItemId(mediaId.removePrefix(BOOK_PREFIX)), null)
            mediaId.startsWith(AT_PREFIX) -> {
                val rest = mediaId.removePrefix(AT_PREFIX)
                val cut = rest.lastIndexOf('/')
                val millis = rest.substring(cut + 1).toLongOrNull()
                if (cut <= 0 || millis == null) null else Target(LibraryItemId(rest.take(cut)), millis.milliseconds)
            }
            else -> null
        }

        fun kindOf(mediaId: String): String = when {
            mediaId.startsWith(BOOK_PREFIX) -> "book"
            mediaId.startsWith(AT_PREFIX) -> "at"
            mediaId.startsWith(TAB_PREFIX) -> "tab"
            mediaId == ROOT -> "root"
            mediaId.startsWith(OUT_PREFIX) -> "out"
            mediaId.startsWith(SERIES_PREFIX) -> "series"
            mediaId.startsWith(AUTHOR_PREFIX) -> "author"
            mediaId.startsWith(NOTICE_PREFIX) -> "notice"
            mediaId.isEmpty() -> "empty"
            else -> "other"
        }

        const val ROOT = "root"
        const val RECENT_ROOT = "root/recent"

        private const val TAB_PREFIX = "tab/"
        const val TAB_CONTINUE = "${TAB_PREFIX}continue"
        const val TAB_CHAPTERS = "${TAB_PREFIX}chapters"
        const val TAB_HISTORY = "${TAB_PREFIX}history"
        const val TAB_LIBRARY = "${TAB_PREFIX}library"
        const val TAB_SERIES = "${TAB_PREFIX}series"
        const val TAB_AUTHORS = "${TAB_PREFIX}authors"
        const val TAB_DOWNLOADS = "${TAB_PREFIX}downloads"
        const val TAB_RECENT = "${TAB_PREFIX}recent"
        const val TAB_DISCOVER = "${TAB_PREFIX}discover"
        const val TAB_AGAIN = "${TAB_PREFIX}again"
        const val TAB_OUTPUT = "${TAB_PREFIX}output"

        const val OUT_PREFIX = "out/"
        const val AUTOMATIC_OUTPUT = "automatic"
        private const val SERIES_PREFIX = "series/"
        private const val AUTHOR_PREFIX = "author/"

        private const val NOTICE_PREFIX = "notice/"
        const val NOTICE_EMPTY = "${NOTICE_PREFIX}empty"
        const val NOTICE_OUTPUT = "${NOTICE_PREFIX}output"

        private const val BOOK_PREFIX = "book/"
        private const val AT_PREFIX = "at/"

        private const val CAR_HISTORY_LIMIT = 15

        /** Rows read so that [CAR_HISTORY_LIMIT] survivable ones can exist below the frequent noise. */
        private const val CAR_HISTORY_READ = CAR_HISTORY_LIMIT * 8
        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_MINUTE = 60L
        private const val PART_SEPARATOR = " · "
        private const val FULLY_PLAYED = 1.0
        private const val PERCENT = 100
    }
}

data class NowPlaying(val bookId: LibraryItemId, val position: Duration)

@OptIn(UnstableApi::class)
private fun browsableNode(id: String, title: String, extras: Bundle? = null): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .apply { extras?.let(::setExtras) }
            .build(),
    )
    .build()
