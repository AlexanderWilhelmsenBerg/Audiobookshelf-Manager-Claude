package com.example.shelfplayer.core.network.fake

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.NewServerUser
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.ServerUser
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.EmbedRequest
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.library.MatchCandidate
import com.example.shelfplayer.core.model.library.MetadataProvider
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.map
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.fixture.FixtureMapper
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.BookmarkApi
import com.example.shelfplayer.core.network.gateway.CachedLibrary
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.CoverUpload
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.network.gateway.ItemScanOutcome
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ManagementApi
import com.example.shelfplayer.core.network.gateway.MetadataSaveOutcome
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 20, Phase 0 — "the app opens a fake library, no real credentials needed".
 *
 * A gateway backed entirely by the bundled fixture document. It exists so the whole vertical slice —
 * gateway, repository, Room, UI — can be built and tested before a single server endpoint is
 * contract-tested, and so that CI never needs a server.
 *
 * It behaves like a real gateway in the ways that matter:
 *  - it returns [AppResult], including typed failures for a malformed fixture;
 *  - it enforces the profile boundary rather than ignoring it (PRODUCT_SPEC 5.2);
 *  - it does its work off the main thread through an injected dispatcher.
 *
 * It is replaced, not extended, by the Retrofit-backed gateway in Phase 1.
 */
@Singleton
class FakeAudiobookshelfGateway @Inject constructor(
    private val loader: FixtureLibraryLoader,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : AudiobookshelfGateway,
    AuthApi,
    CapabilityResolver,
    LibraryApi,
    PlaybackApi,
    BookmarkApi,
    DownloadApi,
    ManagementApi {
    override val auth: AuthApi get() = this
    override val capabilities: CapabilityResolver get() = this
    override val library: LibraryApi get() = this
    override val playback: PlaybackApi get() = this
    override val bookmarks: BookmarkApi get() = this
    override val downloads: DownloadApi get() = this
    override val management: ManagementApi get() = this

    /**
     * PRODUCT_SPEC MGR-001 — the fixture library is read-only, and says so rather than pretending.
     *
     * Same refusal as [signIn] and [fetchFile], for the same reason. A fake that accepted an edit would
     * let a test believe a save had reached a server, and the whole point of this layer is that nothing
     * can believe that by accident.
     */
    override suspend fun updateMetadata(
        profileId: ProfileId,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
        changed: Set<BookMetadataField>,
    ): AppResult<MetadataSaveOutcome> = AppError.ApiCompatibility(
        summary = "The demo library cannot be edited.",
        missingField = "media",
    ).asFailure()

    /** PRODUCT_SPEC MGR-002 — the fixture library's covers are bundled assets, not files a server holds. */
    override suspend fun uploadCover(
        profileId: ProfileId,
        bookId: LibraryItemId,
        image: CoverUpload,
    ): AppResult<BookSnapshot> = readOnly()

    override suspend fun removeCover(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> = readOnly()

    /** PRODUCT_SPEC MGR-003 — the demo library reaches no metadata provider, and says so. */
    override suspend fun findCandidates(
        profileId: ProfileId,
        provider: String,
        title: String,
        author: String,
    ): AppResult<List<MatchCandidate>> = AppError.ApiCompatibility(
        summary = "The demo library cannot search a metadata provider.",
    ).asFailure()

    override suspend fun metadataProviders(profileId: ProfileId): AppResult<List<MetadataProvider>> =
        AppError.ApiCompatibility(summary = "The demo library has no metadata providers.").asFailure()

    override suspend fun scanItem(profileId: ProfileId, bookId: LibraryItemId): AppResult<ItemScanOutcome> =
        AppError.ApiCompatibility(summary = "The demo library has no files to scan.").asFailure()

    /** PRODUCT_SPEC MGR-007 — there are no audio files behind the demo document to write metadata into. */
    override suspend fun embedMetadata(profileId: ProfileId, bookId: LibraryItemId): AppResult<EmbedRequest> =
        AppError.ApiCompatibility(summary = "The demo library has no audio files to embed metadata into.")
            .asFailure()

    override suspend fun removeFromDatabase(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
        AppError.ApiCompatibility(summary = "The demo library cannot be edited.").asFailure()

    /** PRODUCT_SPEC EPIC USER — the demo document describes one fixture profile, not a server's accounts. */
    override suspend fun listUsers(profileId: ProfileId): AppResult<List<ServerUser>> =
        AppError.ApiCompatibility(summary = "The demo library has no server accounts to manage.").asFailure()

    override suspend fun createUser(profileId: ProfileId, user: NewServerUser): AppResult<ServerUser> =
        AppError.ApiCompatibility(summary = "The demo library cannot create accounts.").asFailure()

    override suspend fun setUserActive(profileId: ProfileId, userId: String, isActive: Boolean): AppResult<Unit> =
        AppError.ApiCompatibility(summary = "The demo library cannot change accounts.").asFailure()

    private fun readOnly(): AppResult<BookSnapshot> = AppError.ApiCompatibility(
        summary = "The demo library cannot be edited.",
        missingField = "media",
    ).asFailure()

    /** PRODUCT_SPEC MGR-001 — one fixture book, re-read. The fixtures never change, so this is a lookup. */
    override suspend fun fetchBook(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> =
        AppError.ApiCompatibility(
            summary = "The demo library cannot be refreshed one book at a time.",
            missingField = "media",
        ).asFailure()

    /**
     * PRODUCT_SPEC DL-001 — the demo library has no files to fetch, and says so.
     *
     * The fixture document describes books; there are no bytes behind them. A fake that invented some
     * would let a test believe a download had happened, which is the failure this whole layer is built to
     * make impossible. Same reasoning as [signIn]'s deliberate refusal.
     */
    override suspend fun fetchFile(
        profileId: ProfileId,
        bookId: LibraryItemId,
        fileId: String,
        sink: (Boolean) -> java.io.OutputStream,
        resumeFrom: Long,
        validator: String?,
        onProgress: (Long) -> Unit,
    ): AppResult<FileTransfer> = AppResult.Failure(
        AppError.ApiCompatibility(summary = "The bundled demo library has no audio files to download."),
    )

    override suspend fun fetchCover(
        profileId: ProfileId,
        bookId: LibraryItemId,
        sink: () -> java.io.OutputStream,
    ): AppResult<String?> = AppResult.Failure(
        AppError.ApiCompatibility(summary = "The bundled demo library has no cover art to download."),
    )

    /**
     * The fixture server and profile, for a test that needs the identities the demo document declares.
     *
     * These were `AccountApi` in Phase 0. That interface is gone — its parameterless shape could not
     * serve a multi-profile client — and the two values remain as plain accessors because the fixture
     * document is still the source for the repository tests.
     */
    suspend fun fixtureServer(): AppResult<Server> = withMapper { mapper -> mapper.server() }

    suspend fun fixtureProfile(): AppResult<Profile> = withMapper { mapper -> mapper.profile(clock.now()) }

    /**
     * PRODUCT_SPEC 20 Phase 0 — the demo library needs no credentials, so the fake reports a server
     * that is reachable and already initialized.
     */
    override suspend fun probe(serverUrl: String): AppResult<ServerProbe> = AppResult.Success(
        ServerProbe(
            isAudiobookshelf = true,
            // Deliberately not a real version string: nothing may mistake the fake for a server.
            serverVersion = null,
            isInitialized = true,
            authMethods = listOf("local"),
        ),
    )

    /**
     * The fake has no credential store and must never look like one.
     *
     * Returning a working session here would make a sign-in screen appear to succeed against fixture
     * data, which is exactly the false confidence PRODUCT_SPEC 22.4 is guarding against. Sign-in is
     * answered by the Retrofit-backed gateway or not at all.
     */
    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<AuthSession> =
        unsupported()

    override suspend fun refresh(serverUrl: String, refreshToken: AuthToken): AppResult<AuthSession> = unsupported()

    override suspend fun currentAccount(serverUrl: String, accessToken: AuthToken): AppResult<AccountState> =
        unsupported()

    override suspend fun signOut(serverUrl: String, accessToken: AuthToken): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * PRODUCT_SPEC PLAY-001 — the demo document has no audio behind it, so no session can be opened.
     *
     * A typed failure rather than a fabricated session. A fake that returned track URLs pointing at
     * nothing would make the player's error path unreachable in tests and would fail on a device with
     * an ExoPlayer source error instead of a message.
     */
    override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> =
        AppResult.Failure(
            AppError.ApiCompatibility(
                summary = "This build is showing the bundled demo library and has no audio to play.",
                missingCapability = "playback",
            ),
        )

    /**
     * PRODUCT_SPEC PLAY-004 / PLAY-005 — the demo document has no server to sync to.
     *
     * Typed failures rather than a silent success. A fake that pretended to have uploaded progress would
     * make the outbox's retry path unreachable in tests, which is the one path that must not be
     * accidentally correct.
     */
    override suspend fun syncSession(
        profileId: ProfileId,
        sessionId: String,
        progress: SessionProgress,
    ): AppResult<Unit> = noServer()

    override suspend fun closeSession(
        profileId: ProfileId,
        sessionId: String,
        progress: SessionProgress,
    ): AppResult<Unit> = noServer()

    override suspend fun syncOfflineSessions(
        profileId: ProfileId,
        sessions: List<OfflineSession>,
    ): AppResult<List<OfflineSessionResult>> = noServer()

    override suspend fun setFinished(
        profileId: ProfileId,
        bookId: LibraryItemId,
        isFinished: Boolean,
        position: Duration,
    ): AppResult<Unit> = noServer()

    /**
     * PRODUCT_SPEC 11.1 — the demo document has no server, so a bookmark cannot be written to one.
     *
     * [noServer] rather than a fixture bookmark, and the distinction matters: the bundled library is a
     * *read* fixture. Pretending a write succeeded would let a demo build show a bookmark that vanishes
     * on the next refresh, which is worse than a build that says plainly it has nowhere to put it.
     */
    override suspend fun create(
        profileId: ProfileId,
        bookId: LibraryItemId,
        at: Duration,
        title: String,
    ): AppResult<Bookmark> = noServer()

    override suspend fun rename(
        profileId: ProfileId,
        bookId: LibraryItemId,
        at: Duration,
        title: String,
    ): AppResult<Bookmark> = noServer()

    override suspend fun remove(profileId: ProfileId, bookId: LibraryItemId, at: Duration): AppResult<Unit> = noServer()

    private fun <T> noServer(): AppResult<T> = AppResult.Failure(
        AppError.ApiCompatibility(
            summary = "This build is showing the bundled demo library and has no server to sync with.",
            missingCapability = "playback",
        ),
    )

    private fun <T> unsupported(): AppResult<T> = AppResult.Failure(
        AppError.ApiCompatibility(
            summary = "This build is showing the bundled demo library and cannot sign in to a server.",
            missingCapability = "authentication",
        ),
    )

    /**
     * The fixture document names its own capabilities, so the requested server is ignored.
     *
     * Ignoring it is safe here for the reason the whole class is safe: there is exactly one fixture
     * server, and it is not a real one. A real resolver must probe the server it was given.
     */
    override suspend fun resolve(
        serverId: ServerId,
        serverUrl: String,
        accessToken: AuthToken?,
    ): AppResult<ServerCapabilities> = withMapper { mapper -> mapper.capabilities() }

    override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> =
        requireProfile(profileId).flatMap {
            withMapper { mapper -> mapper.libraries(clock.now()) }
        }

    override suspend fun listAuthors(profileId: ProfileId, libraryId: LibraryId): AppResult<List<Author>> =
        requireProfile(profileId).flatMap {
            withMapper { mapper ->
                mapper.books(libraryId, clock.now())
                    .flatMap { snapshot -> snapshot.book.authors }
                    .distinctBy { author -> author.id }
            }
        }

    override suspend fun listBooks(
        profileId: ProfileId,
        libraryId: LibraryId,
        onBatch: suspend (List<BookSnapshot>) -> Unit,
        cached: CachedLibrary,
        onCatalogueBatch: suspend (List<BookSnapshot>) -> Unit,
    ): AppResult<LibrarySnapshot> = requireProfile(profileId).flatMap {
        withMapper { mapper -> mapper.books(libraryId, clock.now()) }.map { books ->
            // PRODUCT_SPEC LIB-001 — the fake honours `cached` because the real gateway does, and the
            // difference used to hide a defect that emptied whole libraries.
            //
            // `AbsLibraryApi` skips any item the server reports unchanged, so on a second refresh its
            // snapshot carries **no books at all** while `visibleIds` still names the whole catalogue.
            // This fake returned every book every time, so `books` was never empty, and
            // `refresh is idempotent and does not duplicate rows` passed against a production path that
            // marked the entire library deleted. A fake that does not reproduce the shape of the thing it
            // stands in for is not a test double; it is a second implementation that agrees with nothing.
            val changed = books.filterNot { snapshot ->
                cached.isUpToDate(snapshot.book.id, snapshot.book.remoteUpdatedAt?.toEpochMilli())
            }
            // Previews first, then the expanded rows, in the order the real gateway emits them.
            onCatalogueBatch(books)
            // The fixture library is small enough to arrive at once, so one batch is the honest report.
            // It is still emitted, because a caller that never sees a batch from the fake would never
            // exercise the incremental path at all.
            if (changed.isNotEmpty()) onBatch(changed)
            LibrarySnapshot(books = changed, visibleIds = books.map { it.book.id })
        }
    }

    /**
     * The fixture library, filtered by the same predicate a server would apply loosely.
     *
     * A fake that returned nothing would make the demo profile's search look broken; a fake that
     * returned everything would hide the distinction between a cached hit and a server hit. Matching on
     * the title is the smallest thing that behaves like a search.
     */
    override suspend fun searchBooks(
        profileId: ProfileId,
        libraryId: LibraryId,
        query: String,
    ): AppResult<List<BookSnapshot>> = requireProfile(profileId).flatMap {
        withMapper { mapper -> mapper.books(libraryId, clock.now()) }
            .map { books -> books.filter { it.book.title.contains(query, ignoreCase = true) } }
    }

    /**
     * PRODUCT_SPEC 5.2 — a gateway call for a profile this connection does not serve is a failure,
     * not an empty list. Returning empty would render as "this library is empty" instead of "you are
     * looking at the wrong account".
     */
    private suspend fun requireProfile(profileId: ProfileId): AppResult<Unit> =
        withMapper { mapper -> mapper.profileId }.flatMap { fixtureProfileId ->
            if (fixtureProfileId == profileId) {
                AppResult.Success(Unit)
            } else {
                logger.info(
                    LogCategory.Network,
                    "Rejected fixture gateway call for a non-fixture profile",
                    LogField.Identifier("requestedProfile", profileId.value),
                )
                AppResult.Failure(
                    AppError.Authorization(
                        summary = "This profile is not connected to the demo library.",
                        missingPermission = "fixture.profile",
                    ),
                )
            }
        }

    private suspend fun <T> withMapper(block: (FixtureMapper) -> T): AppResult<T> = withContext(ioDispatcher) {
        loader.load().map { document -> block(FixtureMapper(document)) }
    }
}
