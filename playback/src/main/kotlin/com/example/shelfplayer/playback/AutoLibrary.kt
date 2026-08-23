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
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.domain.library.HomeShelves
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
    /**
     * PRODUCT_SPEC SET-002 / 2.10 — so the car speaks the language the app is set to.
     *
     * Every word in this tree was a Kotlin literal until 2026-08-20, on the reasoning that threading a
     * `Context` in for six words was more plumbing than it was worth. That was wrong twice over. The words
     * grew past six, and — the part that actually mattered — two of them said "ShelfPlayer" for three
     * phases after the app was renamed, because a literal is invisible to `MissingTranslation` and to
     * every check that keeps the two `strings.xml` files in step.
     *
     * The application context, not the service's: this is a `@Singleton` that outlives any one service
     * instance, and holding a `Service` here would leak it. Resource lookups are the only use.
     */
    @param:ApplicationContext private val context: Context,
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

    /** The recent root's node. One playable child, or none — see [resumeRow]. */
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
     * The children of a node.
     *
     * Empty rather than an error for an id this tree does not know. A car that asks for a stale node — it
     * cached the tree, the profile changed underneath — should show nothing, not an error dialog the driver
     * has to dismiss.
     */
    /**
     * Emits whenever a connected browser's copy of this tree has gone stale.
     *
     * ### Why this exists
     *
     * `onGetChildren` builds the tree on demand, which made it look self-updating. It is not: a browser
     * fetches once and caches, and Media3 only re-asks after `notifyChildrenChanged`. Nothing called that,
     * so a head unit kept whatever it had first loaded — **including the previous profile's book titles
     * after a switch**, in a car with other people in it. That is product priority 4 rather than a stale-UI
     * annoyance, which is why the signal is the *profile* rather than every library write.
     *
     * `drop(1)` because the first emission is the current profile, not a change to it: the browser has not
     * fetched anything yet when the service starts, and notifying it then would be a needless round trip
     * over the car's binder.
     */
    fun invalidations(): Flow<Unit> = profiles.observeActiveProfile()
        .map { profile -> profile?.id }
        .distinctUntilChanged()
        .drop(1)
        .map { }

    /**
     * The parents a browser can be subscribed to, which is what `notifyChildrenChanged` has to name.
     *
     * Listed here rather than in the service so the ids stay with the tree that defines them: a tab added
     * to [rootTabs] without being added here would silently keep serving the old profile's contents.
     */
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
        else -> emptyList()
    }

    /**
     * PRODUCT_SPEC ROUTE-001 / PLAY-001 — the one row behind the car's *resume* tile.
     *
     * ### What asks for this, and why it is a separate root
     *
     * Android Auto asks a media app for its **recent root** when the car starts, before anything is playing
     * and before the driver has browsed anywhere. Whatever comes back becomes the tile on the car's media
     * home screen — the one a driver taps to carry on with what they were listening to. Media3 surfaces the
     * request as [androidx.media3.session.MediaLibraryService.LibraryParams.isRecent] on `onGetLibraryRoot`.
     *
     * It has to be a *different* root from [ROOT], because the answer is a different shape: the browse root
     * is a grid of categories, and this is exactly one playable item. Returning the browse tree here gets a
     * tile that opens a menu, which is not what the driver is being offered.
     *
     * ### One row, and no fallback
     *
     * Empty when there is nothing to resume — no profile, nothing started, or everything finished. An empty
     * list is how a media app says "I have no resume tile", and the car then shows none. The tempting
     * fallback of offering *some* book instead is wrong in a way that only shows up in a car: the tile is
     * one tap and the driver's eyes are on the road, so an arbitrary book would start playing on a tap that
     * meant "carry on where I was".
     *
     * The row resumes at the stored position rather than at the book's start, so the tap does the thing the
     * tile promises. That is [AT_PREFIX], the same id form a chapter row uses.
     */
    private suspend fun resumeRow(): List<MediaItem> {
        val book = lastPlayed() ?: return emptyList()
        val progress = book.progress
        return listOf(
            playable(
                id = "$AT_PREFIX${book.id.value}/${progress?.position?.inWholeMilliseconds ?: 0}",
                title = book.title,
                subtitle = book.authors.joinToString { it.name }.takeIf(String::isNotBlank),
                extras = completionExtras(progress?.fractionComplete?.toDouble()),
            ),
        )
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
            if (shelves.continueListening.isNotEmpty()) add(tab(TAB_CONTINUE, R.string.car_tab_continue))
            if (shelves.recentlyAdded.isNotEmpty()) add(tab(TAB_RECENT, R.string.car_tab_recent))
            if (shelves.listenAgain.isNotEmpty()) add(tab(TAB_AGAIN, R.string.car_tab_again))
            if (shelves.discover.isNotEmpty()) add(tab(TAB_DISCOVER, R.string.car_tab_discover))
        }
        if (tabs.isEmpty()) return listOf(emptyNotice())
        return tabs + tab(TAB_CHAPTERS, R.string.car_tab_chapters) + tab(TAB_HISTORY, R.string.car_tab_history)
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
                .setTitle(string(R.string.car_empty_title))
                .setSubtitle(string(R.string.car_empty_subtitle, string(R.string.car_app_name)))
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    /** One node, for a car that asks about an id directly. `null` for one this tree does not own. */
    suspend fun item(mediaId: String, now: NowPlaying?): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId == RECENT_ROOT -> recentRoot()
        mediaId.startsWith(TAB_PREFIX) -> children(ROOT, now).firstOrNull { it.mediaId == mediaId }
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
     * PRODUCT_SPEC PLAY-003 — the chapters of whatever is playing, or of the last book if nothing is, each
     * one showing how far through it the listener is.
     *
     * ### The question this tab answers
     *
     * "How far into this chapter am I, and how far into the book?" A car's seek bar cannot answer either.
     * The book is one timeline (ADR-0016), so the bar under the now-playing screen is the *book's* progress
     * — which is the right thing for it to show and tells a driver nothing about the chapter they are in.
     *
     * So every row carries a completion badge, and the three states are genuinely different information:
     *
     *  - a chapter that **ends before** the position is finished, and the car fills its bar;
     *  - the chapter the position is **inside** is part-filled, in proportion to how far in;
     *  - a chapter that **starts after** it is unplayed, and the car leaves the bar empty.
     *
     * Reading down the list therefore shows the shape of the book — how much is behind, where "here" is,
     * how much is left — which is the thing a progress percentage flattens into one number.
     *
     * ### The header row, which is the comparison
     *
     * The chapter bars are all relative to their own chapter, so on their own they cannot say how far
     * through the *book* the listener is: a half-filled bar means half of eight minutes or half of fifty.
     * [bookProgressRow] is the row that makes them comparable, and it is first so it is read first.
     *
     * ### Where the position comes from
     *
     * From the player when the car is asking about the book that is playing, and from stored progress
     * otherwise. The distinction matters: a driver who has been listening for twenty minutes without the
     * outbox syncing would otherwise see chapter bars twenty minutes stale, which is worse than no bars —
     * it is a wrong answer to the question the tab exists for.
     */
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

    /**
     * How far into [book] the listener is, in the book's own timeline.
     *
     * The player's position when the car is asking about the book the player holds; the stored progress
     * otherwise. `now.bookId == book.id` is the whole of that test and it has to be made: [bookFor] falls
     * back to the last-played book when nothing is playing, so without the comparison a car browsing book B
     * while book A plays would draw B's chapters against A's position.
     */
    private fun positionIn(book: Book, now: NowPlaying?): Duration = when (book.id) {
        now?.bookId -> now.position
        else -> book.progress?.position ?: Duration.ZERO
    }

    /**
     * One chapter, with how far through it the listener is.
     *
     * A zero-length chapter — which a mis-tagged file can produce — is treated as unplayed rather than
     * divided by. It is the only division in this file and the only value that can make it undefined.
     */
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
            // The current chapter says how far in; every other row says how long it is. A driver choosing
            // where to jump wants the length, and the one they are in is the one where "where am I" is the
            // question instead.
            subtitle = if (fraction != null && fraction < FULLY_PLAYED) {
                string(R.string.car_chapter_elapsed, elapsed.asClock(), length.asClock())
            } else {
                length.asClock()
            },
            extras = completionExtras(fraction),
        )
    }

    /**
     * The book itself, above its chapters, as the thing the chapter bars are measured against.
     *
     * Playable rather than a caption, and it resumes where the listener is rather than restarting. A row
     * that cannot be tapped is a row a driver taps anyway; making it the *resume* control means the
     * reflex is right.
     */
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

    /**
     * The completion badge Android Auto draws under a row.
     *
     * `null` is "never started" rather than "zero per cent", and the two are drawn differently: an empty bar
     * says *you stopped at the very beginning*, and no bar says *you have not opened this*. Passing 0.0 for
     * both would lose that, and on the chapter list it is most of the list.
     */
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
        // `isFinished` is stored rather than derived (see `MediaProgress`), so a book marked finished at 94%
        // is *finished* and the badge has to say so. That is why this cannot simply pass the fraction.
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
     * What a history row says happened.
     *
     * An exhaustive `when` over the enum rather than a map, so adding a `PlaybackEvent` fails to compile
     * here instead of silently reaching a car as a blank subtitle.
     */
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
        },
    )

    /** A resource, in whatever language the app is set to. */
    private fun string(@StringRes id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    /** A browsable tab whose title comes from resources. */
    private fun tab(id: String, @StringRes titleRes: Int): MediaItem = browsableNode(id, string(titleRes))

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

        const val ROOT = "root"

        /**
         * The root Android Auto asks for when it wants a resume tile rather than a browse tree.
         *
         * A distinct id, not a flag on [ROOT]: Media3 caches a browse node by its id, so one id answering
         * two different questions would serve whichever answer was asked for first.
         */
        const val RECENT_ROOT = "root/recent"

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

        /**
         * Between the parts of the header row's subtitle.
         *
         * A Kotlin constant and not a string resource, for two reasons that point the same way. It is
         * punctuation rather than language, so there would be nothing for a translator to do with it — and
         * Android strips leading and trailing whitespace from a resource, so ` · ` comes back as `·` and the
         * parts run together. Quoting it in the XML would work and would look like a mistake waiting to be
         * tidied away.
         */
        private const val PART_SEPARATOR = " · "

        /** A fraction, not a percentage: `EXTRAS_KEY_COMPLETION_PERCENTAGE` is documented as 0.0 to 1.0. */
        private const val FULLY_PLAYED = 1.0
        private const val PERCENT = 100
    }
}

/**
 * Where the player is, for the browse tree to draw chapter progress against.
 *
 * Passed in rather than read here, because [AutoLibrary] has no player and should not acquire one: it is a
 * pure function of the library and this argument, which is what lets every rule in it be tested on the JVM.
 *
 * `null` means nothing is playing, and the tree then falls back to the last-played book at its stored
 * position — the same book the resume tile offers.
 */
data class NowPlaying(val bookId: LibraryItemId, val position: Duration)

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
