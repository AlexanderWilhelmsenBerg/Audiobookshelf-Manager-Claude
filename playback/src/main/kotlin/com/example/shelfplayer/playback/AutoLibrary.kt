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
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.ResumeTarget
import com.example.shelfplayer.domain.library.reconciledResumeTarget
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Android Auto browse tree and the shared resume lookup used by media controls. */
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

    fun invalidations(): Flow<Unit> = profiles.observeActiveProfile()
        .map { profile -> profile?.id }
        .distinctUntilChanged()
        .drop(1)
        .map { }

    fun browsableParents(): List<String> = listOf(
        ROOT,
        RECENT_ROOT,
        TAB_CONTINUE,
        TAB_RECENT,
        TAB_DISCOVER,
        TAB_AGAIN,
        TAB_CHAPTERS,
        TAB_HISTORY,
    )

    suspend fun children(parentId: String, now: NowPlaying?): List<MediaItem> = when (parentId) {
        ROOT -> rootTabs()
        RECENT_ROOT -> resumeRow()
        TAB_CONTINUE -> shelves().continueListening.map(::bookItem)
        TAB_RECENT -> shelves().recentlyAdded.map(::bookItem)
        TAB_DISCOVER -> shelves().discover.map(::bookItem)
        TAB_AGAIN -> shelves().listenAgain.map(::bookItem)
        TAB_CHAPTERS -> chaptersOf(now)
        TAB_HISTORY -> historyOf(now?.bookId)
        TAB_OUTPUT -> outputRows()
        else -> if (parentId.startsWith(OUT_PREFIX)) chooseOutput(parentId) else emptyList()
    }

    private fun outputRows(): List<MediaItem> {
        val outputs = audioOutputs.available()
        if (outputs.isEmpty()) return listOf(noticeRow(string(R.string.car_output_none)))
        val chosen = audioOutputs.selected()
        return listOf(
            browsableNode(
                id = "$OUT_PREFIX$AUTOMATIC_OUTPUT",
                title = string(R.string.car_output_automatic),
            ),
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

    private fun chooseOutput(mediaId: String): List<MediaItem> {
        val id = mediaId.removePrefix(OUT_PREFIX)
        audioOutputs.select(id.takeIf { it != AUTOMATIC_OUTPUT })
        val chosen = audioOutputs.selected()
        val name = audioOutputs.available().firstOrNull { it.id == chosen }?.displayName
        return listOf(
            noticeRow(
                if (name == null) {
                    string(R.string.car_output_automatic_now)
                } else {
                    string(R.string.car_output_chosen, name)
                },
            ),
        )
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

    /**
     * The recent/resumption row. It must not open a server playback session just to draw metadata, so a
     * newer server session contributes its reached position directly.
     */
    private suspend fun resumeRow(): List<MediaItem> {
        val target = resumeTarget() ?: return emptyList()
        val book = target.book
        val total = book.progress?.duration?.takeIf { it > Duration.ZERO }
            ?: book.duration.takeIf { it > Duration.ZERO }
        val fraction = total?.let { duration -> (target.position / duration).coerceIn(0.0, 1.0) }
        return listOf(
            playable(
                id = "$AT_PREFIX${book.id.value}/${target.position.inWholeMilliseconds}",
                title = book.title,
                subtitle = book.authors.joinToString { it.name }.takeIf(String::isNotBlank),
                extras = completionExtras(fraction),
            ),
        )
    }

    suspend fun resumeItem(): MediaItem? = resumeRow().firstOrNull()

    private suspend fun rootTabs(): List<MediaItem> {
        val shelves = shelves()
        val tabs = buildList {
            if (shelves.continueListening.isNotEmpty()) add(tab(TAB_CONTINUE, R.string.car_tab_continue))
            if (shelves.recentlyAdded.isNotEmpty()) add(tab(TAB_RECENT, R.string.car_tab_recent))
            if (shelves.listenAgain.isNotEmpty()) add(tab(TAB_AGAIN, R.string.car_tab_again))
            if (shelves.discover.isNotEmpty()) add(tab(TAB_DISCOVER, R.string.car_tab_discover))
        }
        if (tabs.isEmpty()) return listOf(emptyNotice())
        return tabs + tab(TAB_CHAPTERS, R.string.car_tab_chapters) +
            tab(TAB_HISTORY, R.string.car_tab_history) + tab(TAB_OUTPUT, R.string.car_tab_output)
    }

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
        mediaId.startsWith(TAB_PREFIX) -> children(ROOT, now).firstOrNull { it.mediaId == mediaId }
        else -> resolve(mediaId)?.let { target -> books().firstOrNull { it.id == target.bookId }?.let(::bookItem) }
    }

    suspend fun search(query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return shelves().continueListening.map(::bookItem)
        return books()
            .filter { book -> book.matches(needle) }
            .sortedByDescending { book -> book.progress?.updatedAt }
            .map(::bookItem)
    }

    private fun Book.matches(needle: String): Boolean = title.lowercase().contains(needle) ||
        authors.any { it.name.lowercase().contains(needle) } ||
        narrators.any { it.lowercase().contains(needle) }

    /**
     * Shared by headset/media-button resumption and device auto-start. The network read is bounded so a
     * disconnected server cannot turn Play into a long wait. A successful newer server session chooses the
     * book; opening the playback session later asks ABS for the authoritative final position.
     */
    suspend fun lastPlayed(): Book? = resumeTarget()?.book

    private suspend fun resumeTarget(): ResumeTarget? {
        val books = books()
        if (books.isEmpty()) return null
        val server = withTimeoutOrNull(SERVER_RESUME_TIMEOUT_MS) { history.latestServerSession() }
        return reconciledResumeTarget(books, server)
    }

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
        val rows = chapters.mapIndexed { index, chapter ->
            chapterRow(book.id, chapter, index, position)
        }
        return listOf(bookProgressRow(book, chapters, position)) + rows
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
        val duration = book.progress?.duration?.takeIf { it > Duration.ZERO }
            ?: chapters.lastOrNull()?.end
        val fraction = duration?.takeIf { it > Duration.ZERO }?.let { total -> position / total }
        val index = chapters.indexOfLast { chapter -> position >= chapter.start }
        val subtitle = buildList {
            if (fraction != null) add(string(R.string.car_progress_fraction, (fraction * PERCENT).toInt()))
            if (duration != null) {
                add(string(R.string.car_progress_position, position.asClock(), duration.asClock()))
            }
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

    private suspend fun historyOf(currentBookId: LibraryItemId?): List<MediaItem> {
        val book = bookFor(currentBookId) ?: return emptyList()
        return history.observe(book.id, limit = CAR_HISTORY_LIMIT).first()
            .filter { entry -> entry.event != PlaybackEvent.Play }
            .map { entry ->
                playable(
                    id = "$AT_PREFIX${book.id.value}/${entry.returnTo.inWholeMilliseconds}",
                    title = entry.returnTo.asClock(),
                    subtitle = entry.event.carLabel(),
                    artworkUri = null,
                )
            }
    }

    private fun bookItem(book: Book): MediaItem {
        val progress = book.progress
        val extras = completionExtras(
            when {
                progress == null -> null
                progress.isFinished -> FULLY_PLAYED
                else -> progress.fractionComplete.toDouble()
            },
        )
        return playable(
            id = "$BOOK_PREFIX${book.id.value}",
            title = book.title,
            subtitle = book.authors.joinToString { it.name }.takeIf(String::isNotBlank),
            extras = extras,
        )
    }

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
                if (cut <= 0 || millis == null) {
                    null
                } else {
                    Target(LibraryItemId(rest.take(cut)), millis.milliseconds)
                }
            }

            else -> null
        }

        fun kindOf(mediaId: String): String = when {
            mediaId.startsWith(BOOK_PREFIX) -> "book"
            mediaId.startsWith(AT_PREFIX) -> "at"
            mediaId.startsWith(TAB_PREFIX) -> "tab"
            mediaId == ROOT -> "root"
            mediaId.startsWith(OUT_PREFIX) -> "out"
            mediaId.startsWith(NOTICE_PREFIX) -> "notice"
            mediaId.isEmpty() -> "empty"
            else -> "other"
        }

        const val ROOT = "root"
        const val RECENT_ROOT = "root/recent"
        private const val TAB_PREFIX = "tab/"
        const val TAB_CONTINUE = "${TAB_PREFIX}continue"
        const val TAB_RECENT = "${TAB_PREFIX}recent"
        const val TAB_DISCOVER = "${TAB_PREFIX}discover"
        const val TAB_AGAIN = "${TAB_PREFIX}again"
        const val TAB_CHAPTERS = "${TAB_PREFIX}chapters"
        const val TAB_HISTORY = "${TAB_PREFIX}history"
        const val TAB_OUTPUT = "${TAB_PREFIX}output"
        const val OUT_PREFIX = "out/"
        const val AUTOMATIC_OUTPUT = "automatic"
        private const val NOTICE_PREFIX = "notice/"
        const val NOTICE_EMPTY = "${NOTICE_PREFIX}empty"
        const val NOTICE_OUTPUT = "${NOTICE_PREFIX}output"
        private const val BOOK_PREFIX = "book/"
        private const val AT_PREFIX = "at/"
        private const val CAR_HISTORY_LIMIT = 15
        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_MINUTE = 60L
        private const val PART_SEPARATOR = " · "
        private const val FULLY_PLAYED = 1.0
        private const val PERCENT = 100
        private const val SERVER_RESUME_TIMEOUT_MS = 600L
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
