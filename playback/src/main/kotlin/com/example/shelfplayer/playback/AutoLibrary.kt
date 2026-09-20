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
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.rememberedBook
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PD-001 / PRODUCT_SPEC PLAY-001 — the audiobook-first tree Android Auto sees.
 *
 * The root has exactly four stable destinations: Continue, Series, Authors and Profiles. Library, History,
 * Chapters and Audio output are deliberately not alternate paths. Old ids remain understood only as stale ids
 * that resolve to no children, so a host caching a pre-PD-001 tree cannot resurrect the retired hierarchy.
 */
@OptIn(UnstableApi::class)
@Singleton
class AutoLibrary @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val rememberedBooks: RememberedBookRepository,
    private val homeShelves: ObserveHomeShelvesUseCase,
    private val activation: ProfileActivationGuard,
    private val artwork: AutoArtwork,
) {
    fun root(): MediaItem = browsableNode(
        id = ROOT,
        title = string(R.string.car_app_name),
        extras = contentStyle(
            browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
            playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        ),
    )

    fun recentRoot(): MediaItem = browsableNode(
        id = RECENT_ROOT,
        title = string(R.string.car_tab_continue),
        extras = contentStyle(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
    )

    private val emittedNodes = ConcurrentHashMap.newKeySet<String>()

    private fun remember(id: String): String = id.also(emittedNodes::add)

    /** #10 — one library snapshot plus profile presentation facts feed one invalidation stream. */
    internal fun browseSnapshots(): Flow<AutoBrowseSnapshot> = AutoBrowseSnapshotSource(
        activeProfiles = profiles.observeActiveProfile().map { profile -> profile?.id },
        savedProfiles = profiles.observeProfiles(),
        savedServers = profiles.observeServers(),
        accessibleBooks = library::observeAccessibleBooks,
        build = AutoBrowseSnapshotBuilder::build,
    ).snapshots()

    internal fun emittedDynamicParents(): Set<String> = emittedNodes.toSet()

    suspend fun children(parentId: String, @Suppress("UNUSED_PARAMETER") now: NowPlaying?): List<MediaItem> =
        when (parentId) {
            ROOT -> rootTabs()
            RECENT_ROOT -> resumeRow()
            TAB_CONTINUE -> continueRows()
            TAB_SERIES -> seriesRows()
            TAB_AUTHORS -> authorRows()
            TAB_PROFILES -> profileRows()
            else -> idFamily(parentId)
        }

    private suspend fun idFamily(parentId: String): List<MediaItem> = when {
        parentId.startsWith(SERIES_PREFIX) -> booksForSeries(parentId.removePrefix(SERIES_PREFIX))
        parentId.startsWith(AUTHOR_PREFIX) -> booksForAuthor(parentId.removePrefix(AUTHOR_PREFIX))
        else -> emptyList()
    }

    /** PD-001 — exact order, even for an empty library so Profiles is never hidden behind an empty notice. */
    private fun rootTabs(): List<MediaItem> = listOf(
        tab(TAB_CONTINUE, R.string.car_tab_continue),
        tab(TAB_SERIES, R.string.car_tab_series),
        tab(TAB_AUTHORS, R.string.car_tab_authors),
        tab(TAB_PROFILES, R.string.car_tab_profiles),
    )

    private suspend fun continueRows(): List<MediaItem> {
        val sources = artworkSources()
        return shelves().continueListening.map { book -> bookItem(book, sources) }
    }

    private suspend fun seriesRows(): List<MediaItem> {
        val all = books()
        val sources = artworkSources()
        return autoSeriesNodes(all).map { node ->
            browsableNode(
                id = remember("$SERIES_PREFIX${node.membership.series.id.value}"),
                title = node.membership.series.name,
                artworkUri = node.representativeCover?.let { book ->
                    artwork.book(book, sources.serverBaseUrls, sources.offlineCover(book))
                },
                extras = contentStyle(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
            )
        }
    }

    private suspend fun booksForSeries(seriesId: String): List<MediaItem> {
        val all = books()
        val node = autoSeriesNodes(all).firstOrNull { candidate -> candidate.membership.series.id.value == seriesId }
            ?: return emptyList()
        val sources = artworkSources()
        return node.books.map { book -> bookItem(book, sources) }
    }

    /** LIB-002 — confirmed portrait when available, otherwise the representative cached-cover route. */
    private suspend fun authorRows(): List<MediaItem> {
        val all = books()
        val sources = artworkSources()
        return autoAuthorNodes(all).map { node ->
            browsableNode(
                id = remember("$AUTHOR_PREFIX${node.author.id.value}"),
                title = node.author.name,
                artworkUri = artwork.author(
                    node.author,
                    node.representativeCover,
                    sources.serverBaseUrls,
                    node.representativeCover?.let(sources::offlineCover),
                ),
                extras = contentStyle(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
            )
        }
    }

    private suspend fun booksForAuthor(authorId: String): List<MediaItem> {
        val all = books()
        val node = autoAuthorNodes(all).firstOrNull { candidate -> candidate.author.id.value == authorId }
            ?: return emptyList()
        val sources = artworkSources()
        return node.books.map { book -> bookItem(book, sources) }
    }

    /**
     * AUTH-002 / AUTH-005 — Profiles is navigation plus a media-item command, never a playable fake book.
     *
     * Lock eligibility is point-read here rather than cached in #10's snapshot. The switch command repeats
     * the authoritative check inside SwitchProfileUseCase, so a stale host can never turn presentation state
     * into a lock bypass.
     */
    private suspend fun profileRows(): List<MediaItem> {
        val saved = profiles.observeProfiles().first()
        val servers = profiles.observeServers().first().associateBy(Server::id)
        val active = profiles.activeProfileId()
        return saved.map { profile ->
            val isActive = profile.id == active
            val canActivate = isActive || activation.mayActivate(profile.id)
            profileItem(
                profile = profile,
                server = servers[profile.serverId],
                isActive = isActive,
                canActivate = canActivate,
            )
        }
    }

    private fun profileItem(profile: Profile, server: Server?, isActive: Boolean, canActivate: Boolean): MediaItem {
        val status = when {
            isActive -> string(R.string.car_profile_active)
            !canActivate -> string(R.string.car_profile_locked)
            profile.requiresReauthentication -> string(R.string.car_profile_sign_in)
            else -> string(R.string.car_profile_available)
        }
        val identity = listOfNotNull(
            profile.username.takeIf { username -> username.isNotBlank() },
            server?.displayName?.takeIf { name -> name.isNotBlank() },
            profile.role.name,
            status,
        ).joinToString(PART_SEPARATOR)

        return MediaItem.Builder()
            .setMediaId("$PROFILE_PREFIX${profile.id.value}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(profile.displayName)
                    .setSubtitle(identity)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .apply {
                        if (!isActive) setSupportedCommands(listOf(ACTION_SWITCH_PROFILE))
                    }
                    .build(),
            )
            .build()
    }

    private suspend fun resumeRow(): List<MediaItem> {
        val book = lastPlayed() ?: return emptyList()
        val sources = artworkSources()
        val progress = book.progress
        return listOf(
            playable(
                id = resumeId(book.id, progress?.position),
                title = book.title,
                subtitle = bookSubtitle(book),
                artworkUri = artwork.book(book, sources.serverBaseUrls, sources.offlineCover(book)),
                extras = progress?.let(::completionExtras),
            ),
        )
    }

    suspend fun resumeItem(): MediaItem? = resumeRow().firstOrNull()

    suspend fun item(mediaId: String, @Suppress("UNUSED_PARAMETER") now: NowPlaying?): MediaItem? = when {
        mediaId == ROOT -> root()

        mediaId == RECENT_ROOT -> recentRoot()

        mediaId.startsWith(TAB_PREFIX) -> rootTabs().firstOrNull { item -> item.mediaId == mediaId }

        mediaId.startsWith(SERIES_PREFIX) -> seriesRows().firstOrNull { item -> item.mediaId == mediaId }

        mediaId.startsWith(AUTHOR_PREFIX) -> authorRows().firstOrNull { item -> item.mediaId == mediaId }

        mediaId.startsWith(PROFILE_PREFIX) -> profileRows().firstOrNull { item -> item.mediaId == mediaId }

        else -> resolve(mediaId)?.let { target ->
            val book = books().firstOrNull { candidate -> candidate.id == target.bookId } ?: return@let null
            bookItem(book, artworkSources())
        }
    }

    /** Voice search remains book-only and profile-scoped; PD-001 changes browse IA, not spoken-play semantics. */
    suspend fun search(query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        val matches = if (needle.isEmpty()) {
            shelves().continueListening
        } else {
            books()
                .filter { book -> book.matches(needle) }
                .sortedWith(
                    compareByDescending<Book> { book -> book.progress?.updatedAt }
                        .thenBy { book -> book.title.lowercase() }
                        .thenBy { book -> book.id.value },
                )
        }
        val sources = artworkSources()
        return matches.map { book -> bookItem(book, sources) }
    }

    private fun Book.matches(needle: String): Boolean = title.lowercase().contains(needle) ||
        authors.any { author -> author.name.lowercase().contains(needle) } ||
        narrators.any { narrator -> narrator.lowercase().contains(needle) } ||
        seriesMemberships.any { membership -> membership.series.name.lowercase().contains(needle) }

    suspend fun lastPlayed(): Book? {
        val profileId = profiles.activeProfileId() ?: return null
        val rememberedId = rememberedBooks.rememberedBook(profileId) ?: return null
        return rememberedBook(library.observeAccessibleBooks(profileId).first(), rememberedId)
    }

    private suspend fun books(): List<Book> {
        val profileId = profiles.activeProfileId() ?: return emptyList()
        return library.observeAccessibleBooks(profileId).first()
    }

    private suspend fun shelves(): HomeShelves = homeShelves().first()

    private suspend fun artworkSources(): ArtworkSources {
        val bases = profiles.observeServers().first().associate { server -> server.id to server.baseUrl }
        val covers = downloads.observeAll().first()
            .mapNotNull { offline ->
                offline.coverUri?.let { uri -> (offline.serverId to offline.itemId) to uri }
            }
            .toMap()
        return ArtworkSources(serverBaseUrls = bases, offlineCovers = covers)
    }

    internal suspend fun profileBoundaryCounts(
        snapshot: AutoBrowseSnapshot,
        @Suppress("UNUSED_PARAMETER") now: NowPlaying?,
        parentIds: Set<String>,
    ): Map<String, Int> {
        if (RECENT_ROOT !in parentIds || RECENT_ROOT !in snapshot.deferredProfileCounts) return emptyMap()
        val profileId = snapshot.scope.profileId
        val rememberedId = profileId
            ?.let { id -> rememberedBooks.rememberedBook(id) }
            ?.takeIf(snapshot.accessibleBookIds::contains)
        return mapOf(RECENT_ROOT to if (rememberedId == null) 0 else 1)
    }

    private suspend fun bookItem(book: Book, sources: ArtworkSources): MediaItem {
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
            artworkUri = artwork.book(book, sources.serverBaseUrls, sources.offlineCover(book)),
            extras = completionExtras(fraction),
        )
    }

    /** Author first, then the primary/first series and its server-provided sequence. */
    private fun bookSubtitle(book: Book): String? = buildList {
        book.authors.joinToString { author -> author.name }.takeIf(String::isNotBlank)?.let(::add)
        book.seriesMemberships
            .firstOrNull(SeriesMembership::isPrimary)
            .let { membership -> membership ?: book.seriesMemberships.firstOrNull() }
            ?.let { membership ->
                val sequence = membership.sequence.raw.takeIf(String::isNotBlank)
                add(if (sequence == null) membership.series.name else "${membership.series.name} #$sequence")
            }
    }.joinToString(PART_SEPARATOR).takeIf(String::isNotBlank)

    private fun completionExtras(progress: MediaProgress): Bundle =
        completionExtras(if (progress.isFinished) FULLY_PLAYED else progress.fractionComplete.toDouble())

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

    private fun playable(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUri: Uri? = null,
        extras: Bundle? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(artworkUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .apply { extras?.let(::setExtras) }
                .build(),
        )
        .build()

    private fun string(@StringRes id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    private fun tab(id: String, @StringRes titleRes: Int): MediaItem {
        val extras = when (id) {
            TAB_CONTINUE -> contentStyle(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)

            TAB_SERIES, TAB_AUTHORS -> contentStyle(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)

            TAB_PROFILES -> contentStyle(
                browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )

            else -> null
        }
        return browsableNode(id, string(titleRes), extras = extras)
    }

    private fun contentStyle(browsable: Int? = null, playable: Int? = null): Bundle = Bundle().apply {
        browsable?.let { value -> putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, value) }
        playable?.let { value -> putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, value) }
    }

    private data class ArtworkSources(
        val serverBaseUrls: Map<ServerId, String>,
        val offlineCovers: Map<Pair<ServerId, LibraryItemId>, String>,
    ) {
        fun offlineCover(book: Book): String? = offlineCovers[book.serverId to book.id]
    }

    companion object {
        data class Target(val bookId: LibraryItemId, val startAt: Duration?)

        internal fun resumeId(bookId: LibraryItemId, position: Duration?): String =
            position?.let { "$AT_PREFIX${bookId.value}/${it.inWholeMilliseconds}" }
                ?: "$BOOK_PREFIX${bookId.value}"

        internal fun profileIdOf(mediaId: String?): ProfileId? = mediaId
            ?.takeIf { id -> id.startsWith(PROFILE_PREFIX) }
            ?.removePrefix(PROFILE_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let(::ProfileId)

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
            mediaId.startsWith(PROFILE_PREFIX) -> "profile"
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
        const val TAB_SERIES = "${TAB_PREFIX}series"
        const val TAB_AUTHORS = "${TAB_PREFIX}authors"
        const val TAB_PROFILES = "${TAB_PREFIX}profiles"

        // Retained only as stale protocol identities so #10 can evict a cached pre-PD-001 tree.
        const val TAB_CHAPTERS = "${TAB_PREFIX}chapters"
        const val TAB_HISTORY = "${TAB_PREFIX}history"
        const val TAB_LIBRARY = "${TAB_PREFIX}library"
        const val TAB_DOWNLOADS = "${TAB_PREFIX}downloads"
        const val TAB_RECENT = "${TAB_PREFIX}recent"
        const val TAB_DISCOVER = "${TAB_PREFIX}discover"
        const val TAB_AGAIN = "${TAB_PREFIX}again"
        const val TAB_OUTPUT = "${TAB_PREFIX}output"

        internal const val SERIES_PREFIX = "series/"
        internal const val AUTHOR_PREFIX = "author/"
        internal const val PROFILE_PREFIX = "profile/"
        internal const val ACTION_SWITCH_PROFILE = "com.example.shelfplayer.action.SWITCH_PROFILE"

        const val OUT_PREFIX = "out/"
        const val AUTOMATIC_OUTPUT = "automatic"

        private const val NOTICE_PREFIX = "notice/"
        const val NOTICE_EMPTY = "${NOTICE_PREFIX}empty"
        const val NOTICE_OUTPUT = "${NOTICE_PREFIX}output"

        private const val BOOK_PREFIX = "book/"
        private const val AT_PREFIX = "at/"

        private const val PART_SEPARATOR = " · "
        private const val FULLY_PLAYED = 1.0
    }
}

data class NowPlaying(val bookId: LibraryItemId, val position: Duration)

@OptIn(UnstableApi::class)
private fun browsableNode(id: String, title: String, artworkUri: Uri? = null, extras: Bundle? = null): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(artworkUri)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .apply { extras?.let(::setExtras) }
                .build(),
        )
        .build()
