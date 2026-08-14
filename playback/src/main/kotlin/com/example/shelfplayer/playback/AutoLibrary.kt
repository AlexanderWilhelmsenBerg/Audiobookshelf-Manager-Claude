package com.example.shelfplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-001 / 11.1 — the browse tree Android Auto sees.
 *
 * ### What a car can actually show, and what it cannot
 *
 * The owner asked for *"a view next to the cover which can be changed by tabs, so chapter, history or
 * equaliser"*. **An app cannot draw anything in a car.** Android Auto renders its own UI from a browse tree
 * and a media session; there is no surface to put a custom view on, and any app that appears to have one is
 * using the tree.
 *
 * What the tree *does* give is tabs: the browsable children of the root become the tab bar across the top of
 * the browse screen, and the playback screen is one swipe from it. The equaliser is not here because it is not
 * built, and because a list of media items is the wrong shape for a set of sliders; when it exists it belongs
 * on the phone.
 *
 * The tabs are **the phone's shelves** — Continue, Recently added, Listen again, Discover — plus **Chapters**
 * and **History**, which are about whatever is playing. The first build of this had Continue alone, and a car
 * showed "no books" to an owner whose library was full: see [rootTabs].
 *
 * ### Everything is keyed by a media id
 *
 * A car hands back the `mediaId` of whatever was tapped and nothing else, so the id has to carry the whole
 * instruction. Three forms, parsed by [resolve]:
 *
 *  - `tab/…` — a browsable node. Never played.
 *  - `book/<id>` — play this book from wherever its progress says.
 *  - `at/<id>/<millis>` — play this book **from this position**. What a chapter row and a history row are.
 *
 * ### The profile boundary applies to a head unit
 *
 * PRODUCT_SPEC 5.2 does not have an exception for cars. Everything here reads through
 * [LibraryRepository.observeAccessibleBooks], which is the same grant-filtered view the phone's shelf uses,
 * so a library this profile has lost cannot appear on a dashboard either.
 */
@OptIn(UnstableApi::class)
@Singleton
class AutoLibrary @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val history: PlaybackHistoryRepository,
    private val homeShelves: ObserveHomeShelvesUseCase,
) {

    /**
     * The root. Browsable, and a **grid** of category tiles.
     *
     * `EXTRAS_KEY_CONTENT_STYLE_BROWSABLE = CATEGORY_GRID_ITEM` is what turns the three children into the
     * tab row rather than a list of three words.
     */
    fun root(): MediaItem = Companion.root()

    /**
     * The children of a node.
     *
     * Empty rather than an error for an id this tree does not know. A car that asks for a stale node — it
     * cached the tree, the profile changed underneath — should show nothing, not an error dialog the driver
     * has to dismiss.
     */
    suspend fun children(parentId: String, currentBookId: LibraryItemId?): List<MediaItem> = when (parentId) {
        ROOT -> rootTabs()
        TAB_CONTINUE -> shelves().continueListening.map(::bookItem)
        TAB_RECENT -> shelves().recentlyAdded.map(::bookItem)
        TAB_DISCOVER -> shelves().discover.map(::bookItem)
        TAB_AGAIN -> shelves().listenAgain.map(::bookItem)
        TAB_CHAPTERS -> chaptersOf(currentBookId)
        TAB_HISTORY -> historyOf(currentBookId)
        else -> emptyList()
    }

    /**
     * PRODUCT_SPEC LIB-002 / PLAY-001 — the car's tabs are **the phone's shelves**.
     *
     * The owner's report was that the car opened on an empty *Continue* and said "no books", while a search
     * found them: *"I would like the same library setup as the app."*
     *
     * The cause was not a filter. It was that Continue was the **only** shelf in the car, and a library with
     * nothing in progress has nothing to put in it — which is every account on its first day and any account
     * whose progress has not synced yet. The phone has never had that problem because it shows four shelves and
     * omits the empty ones.
     *
     * So the tree now reads the same [ObserveHomeShelvesUseCase] the home screen reads. Not a copy of its
     * rules: the same function. A car and a phone disagreeing about what "continue listening" means would be a
     * defect nobody could see without owning both.
     *
     * ### Empty shelves are omitted, and *Chapters* and *History* are not
     *
     * An empty tab a driver has already tapped is worse than a tab that was never there, so a shelf with
     * nothing in it does not appear — the same rule `homeShelves` applies on the phone. The last two tabs stay
     * regardless, because they are about whatever is playing rather than about the library, and their emptiness
     * is a state ("nothing is playing") rather than an absence.
     *
     * When every shelf is empty the root would be two tabs about nothing, so it says so in one unplayable row
     * instead. A blank browse screen in a car is indistinguishable from a broken app.
     */
    private suspend fun rootTabs(): List<MediaItem> {
        val shelves = shelves()
        val tabs = buildList {
            if (shelves.continueListening.isNotEmpty()) add(browsableNode(TAB_CONTINUE, "Continue"))
            if (shelves.recentlyAdded.isNotEmpty()) add(browsableNode(TAB_RECENT, "Recently added"))
            if (shelves.listenAgain.isNotEmpty()) add(browsableNode(TAB_AGAIN, "Listen again"))
            if (shelves.discover.isNotEmpty()) add(browsableNode(TAB_DISCOVER, "Discover"))
        }
        if (tabs.isEmpty()) return listOf(emptyNotice())
        return tabs + browsableNode(TAB_CHAPTERS, "Chapters") + browsableNode(TAB_HISTORY, "History")
    }

    /**
     * The phone's shelves, for the profile the car is allowed to see.
     *
     * `first()` on the flow rather than a subscription: a browse tree is a snapshot answer to a question the
     * car asked, and Media3 re-asks by invalidating the node rather than by being pushed to.
     */
    private suspend fun shelves(): HomeShelves = homeShelves().first()

    /**
     * PRODUCT_SPEC 21 — a library with nothing in it says so rather than showing a blank screen.
     *
     * Unplayable, so tapping it does nothing rather than starting something arbitrary. The wording names the
     * two causes a driver can act on — no sign-in and nothing synced — because "no books" on its own is what
     * the owner saw and it explained nothing.
     */
    private fun emptyNotice(): MediaItem = MediaItem.Builder()
        .setMediaId(NOTICE_EMPTY)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("No books yet")
                .setSubtitle("Open ShelfPlayer on your phone to sign in or sync your library.")
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    /** One node, for a car that asks about an id directly. `null` for one this tree does not own. */
    suspend fun item(mediaId: String, currentBookId: LibraryItemId?): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId.startsWith(TAB_PREFIX) -> children(ROOT, currentBookId).firstOrNull { it.mediaId == mediaId }
        else -> resolve(mediaId)?.let { target -> books().firstOrNull { it.id == target.bookId }?.let(::bookItem) }
    }

    /**
     * PRODUCT_SPEC 11.1 — books matching a spoken query.
     *
     * Title, author or narrator, case-insensitively, on the grant-filtered list. Deliberately a `contains`
     * rather than anything cleverer: a voice assistant has already done the hard part badly, and a fuzzy
     * match on top of a mis-transcription produces confident wrong answers. A short list or none is the
     * honest response to "play tide watch".
     *
     * An empty query returns the continue-listening list, because "play a book" with nothing else said is
     * best answered with the one they were in the middle of.
     */
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
     * PRODUCT_SPEC ROUTE-001 — the book a media button, or a car, should resume.
     *
     * The most recently updated progress that is not finished. Finished books are excluded deliberately:
     * a headset press the morning after finishing something should not start it again from the end.
     */
    suspend fun lastPlayed(): Book? = books()
        .filter { book -> book.progress?.isFinished == false }
        .maxByOrNull { book -> book.progress?.updatedAt ?: Instant.MIN }

    // `lastPlayed` deliberately does **not** go through the Continue shelf, even though the two answer nearly
    // the same question. ROUTE-001 is "resume what was playing", so a book with no progress row has nothing to
    // resume and must not be offered to a headset press; the shelf is a browsing surface and may reasonably
    // show more. Sharing one list would mean a media button eventually starting a book at random.

    private suspend fun books(): List<Book> {
        val profileId = profiles.activeProfileId() ?: return emptyList()
        return library.observeAccessibleBooks(profileId).first()
    }

    private suspend fun bookFor(bookId: LibraryItemId?): Book? {
        val target = bookId ?: lastPlayed()?.id ?: return null
        return books().firstOrNull { it.id == target }
    }

    /**
     * PRODUCT_SPEC PLAY-003 — the chapters of whatever is playing, or of the last book if nothing is.
     *
     * Each one plays the book *from that chapter*, which is the whole point of the tab: chapter navigation
     * from a head unit with no seek bar worth using.
     */
    private suspend fun chaptersOf(currentBookId: LibraryItemId?): List<MediaItem> {
        val book = bookFor(currentBookId) ?: return emptyList()
        val profileId = profiles.activeProfileId() ?: return emptyList()
        return library.observeChapters(profileId, book.id).first().mapIndexed { index, chapter ->
            playable(
                id = "$AT_PREFIX${book.id.value}/${chapter.start.inWholeMilliseconds}",
                title = chapter.title.ifBlank { "Chapter ${index + 1}" },
                subtitle = chapter.start.asClock(),
                artworkUri = null,
            )
        }
    }

    /**
     * PRODUCT_SPEC PLAY-003 — the book's events, as somewhere to jump back to.
     *
     * Only the entries that have a position worth returning to, which in a car is a shorter list than on the
     * phone: a driver scrolling past forty rows is a driver not looking at the road. The phone's pane keeps
     * everything.
     */
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

    /**
     * A book, with its progress as a badge.
     *
     * `EXTRAS_KEY_COMPLETION_STATUS` and `_PERCENTAGE` are what draw the part-finished bar under the tile in
     * Android Auto. They are the only way this tree can say "you are two thirds through this", and a
     * continue-listening list without that is a list of identical tiles.
     */
    private fun bookItem(book: Book): MediaItem {
        val progress = book.progress
        val extras = Bundle().apply {
            when {
                progress == null -> putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED,
                )

                progress.isFinished -> putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
                )

                else -> {
                    putInt(
                        MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                        MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
                    )
                    putDouble(
                        MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                        progress.fractionComplete.toDouble(),
                    )
                }
            }
        }
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

    /** `4:03:12`. Short, because it is read at a glance and possibly at speed. */
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

    /**
     * Not localised, and that is a gap rather than a decision.
     *
     * `:playback` is a library module whose resources the *service* can reach, so these could be strings —
     * but the browse tree is built off the main thread from a repository, and threading a `Context` through
     * for six words is the kind of plumbing that gets copied. Recorded in `docs/phase-2-gaps.md`; the tree
     * ships in English until the Auto surface is tested in a car at all.
     */
    private fun PlaybackEvent.carLabel(): String = when (this) {
        PlaybackEvent.Seek -> "Seek"
        PlaybackEvent.Skip -> "Skip"
        PlaybackEvent.Chapter -> "Chapter"
        PlaybackEvent.AutoRewind -> "Rewound after a pause"
        PlaybackEvent.Resume -> "Started listening"
        PlaybackEvent.Play -> "Played"
        PlaybackEvent.Pause -> "Paused"
        PlaybackEvent.SleepTimerStarted -> "Sleep timer set"
        PlaybackEvent.SleepTimerExtended -> "Sleep timer extended"
        PlaybackEvent.SleepTimerExpired -> "Sleep timer ended"
        PlaybackEvent.SleepTimerRewind -> "Rewound after the sleep timer"
        PlaybackEvent.RemoteProgress -> "Moved on another device"
        PlaybackEvent.RemoteFinished -> "Finished on another device"
    }

    companion object {
        /** What the car asked to play, resolved from the id it handed back. */
        data class Target(val bookId: LibraryItemId, val startAt: Duration?)

        /**
         * What to play for a tapped id.
         *
         * `null` for anything browsable, which is how the caller tells "the driver opened a tab" from "the
         * driver chose something".
         *
         * On the companion because it is pure: the id protocol is the one part of this class that has no
         * repository behind it, and it is also the part most worth testing — a car returns nothing but an
         * id, so every way the id can be misread is a defect that only appears in a car.
         */
        fun resolve(mediaId: String): Target? = when {
            mediaId.startsWith(BOOK_PREFIX) -> Target(LibraryItemId(mediaId.removePrefix(BOOK_PREFIX)), null)
            mediaId.startsWith(AT_PREFIX) -> {
                val rest = mediaId.removePrefix(AT_PREFIX)
                // The book id is everything before the *last* slash: an Audiobookshelf item id is opaque
                // and this code does not get to assume it has no slash in it.
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

        /** The root node, which is stateless and therefore lives beside [resolve]. */
        fun root(): MediaItem = browsableNode(
            id = ROOT,
            title = "ShelfPlayer",
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

        const val ROOT = "root"

        private const val TAB_PREFIX = "tab/"
        const val TAB_CONTINUE = "${TAB_PREFIX}continue"
        const val TAB_RECENT = "${TAB_PREFIX}recent"
        const val TAB_DISCOVER = "${TAB_PREFIX}discover"
        const val TAB_AGAIN = "${TAB_PREFIX}again"
        const val TAB_CHAPTERS = "${TAB_PREFIX}chapters"
        const val TAB_HISTORY = "${TAB_PREFIX}history"

        /** The one row shown when no shelf has anything. Unplayable, so it is never resolved to a book. */
        const val NOTICE_EMPTY = "notice/empty"

        private const val BOOK_PREFIX = "book/"
        private const val AT_PREFIX = "at/"

        /** Short enough to scroll at a traffic light. The phone's pane is not capped this hard. */
        private const val CAR_HISTORY_LIMIT = 15

        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_MINUTE = 60L
    }
}

/**
 * A browsable node: the root, or one of its tabs.
 *
 * File-private rather than a member, so both [AutoLibrary] and its companion can build one. The companion
 * needs it for the root, which is stateless and therefore testable without a repository in sight.
 */
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
