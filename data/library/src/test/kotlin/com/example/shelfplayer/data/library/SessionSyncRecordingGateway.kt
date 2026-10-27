package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.ListeningSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.BookmarkApi
import com.example.shelfplayer.core.network.gateway.CachedLibrary
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ManagementApi
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import org.junit.Test
import java.io.File
import java.io.OutputStream
import kotlin.time.Duration

internal class SessionSyncRecordingGateway :
    AudiobookshelfGateway,
    PlaybackApi {
    /**
     * PRODUCT_SPEC PLAY-003 — not part of this test, and refusing rather than pretending.
     *
     * The session *history* read is `ServerSessionHistoryTest`'s subject. A fake that answered with an
     * empty list here would let a future change to this file's subject silently depend on it.
     */
    override suspend fun listeningSessions(
        profileId: ProfileId,
        page: Int,
        itemsPerPage: Int,
    ): AppResult<List<ListeningSession>> = error("the session history read is not exercised here")

    var syncResult: AppResult<Unit> = AppResult.Success(Unit)
    val syncResponses = mutableListOf<AppResult<Unit>>()
    val syncProfiles = mutableListOf<ProfileId>()
    val closeProfiles = mutableListOf<ProfileId>()
    val syncSessionIds = mutableListOf<String>()
    val syncProgressRequests = mutableListOf<SessionProgress>()
    val closeSessionIds = mutableListOf<String>()
    val offlineProfiles = mutableListOf<ProfileId>()
    val offlineResponses = mutableListOf<AppResult<List<OfflineSessionResult>>>()
    var offlineUpload: AppResult<List<OfflineSessionResult>>? = null
    var offlineResults: (List<OfflineSession>) -> List<OfflineSessionResult> = { emptyList() }

    val syncedProgress = mutableListOf<SessionProgress>()
    val uploaded = mutableListOf<List<OfflineSession>>()

    /**
     * How many times the *request* was made, whether or not it succeeded.
     *
     * `syncedProgress` counts accepted writes; these count attempts, which is the question SYNC-002's
     * redundant-sync rule turns on. A sync that was skipped never reaches the gateway at all, and a
     * fake that only recorded successes could not tell that apart from one that failed.
     */
    var syncCount: Int = 0
        private set

    var closeCount: Int = 0
        private set

    override val playback: PlaybackApi get() = this

    /* PRODUCT_SPEC 11.1 — not part of these tests; every method reports so rather than pretending. */

    /**
     * PRODUCT_SPEC DL-001 — not exercised by this test, and refusing rather than pretending.
     *
     * A fake that produced bytes would let a test believe a download had happened, which is precisely the
     * belief the download layer exists to make impossible.
     */
    override val downloads: DownloadApi = object : DownloadApi {
        override suspend fun fetchFile(
            profileId: ProfileId,
            bookId: LibraryItemId,
            fileId: String,
            sink: (Boolean) -> OutputStream,
            resumeFrom: Long,
            validator: String?,
            onProgress: (Long) -> Unit,
        ): AppResult<FileTransfer> = AppResult.Failure(
            AppError.ApiCompatibility(summary = "This fake serves no audio files."),
        )

        override suspend fun fetchCover(
            profileId: ProfileId,
            bookId: LibraryItemId,
            sink: () -> OutputStream,
        ): AppResult<String?> = AppResult.Failure(
            AppError.ApiCompatibility(summary = "This fake serves no cover art."),
        )
    }

    override val bookmarks: BookmarkApi = object : BookmarkApi {
        override suspend fun create(
            profileId: ProfileId,
            bookId: LibraryItemId,
            at: Duration,
            title: String,
        ): AppResult<Bookmark> = unsupported()

        override suspend fun rename(
            profileId: ProfileId,
            bookId: LibraryItemId,
            at: Duration,
            title: String,
        ): AppResult<Bookmark> = unsupported()

        override suspend fun remove(profileId: ProfileId, bookId: LibraryItemId, at: Duration): AppResult<Unit> =
            unsupported()
    }

    override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> =
        unsupported()

    override suspend fun serverProgress(profileId: ProfileId, bookId: LibraryItemId): AppResult<ServerProgress> =
        unsupported()

    override suspend fun syncSession(
        profileId: ProfileId,
        sessionId: String,
        progress: SessionProgress,
    ): AppResult<Unit> {
        syncProfiles += profileId
        syncSessionIds += sessionId
        syncProgressRequests += progress
        syncCount += 1
        if (syncResponses.isNotEmpty()) return syncResponses.removeAt(0)
        if (syncResult is AppResult.Success) syncedProgress += progress
        return syncResult
    }

    override suspend fun closeSession(
        profileId: ProfileId,
        sessionId: String,
        progress: SessionProgress,
    ): AppResult<Unit> {
        closeProfiles += profileId
        closeSessionIds += sessionId
        closeCount += 1
        if (syncResponses.isNotEmpty()) return syncResponses.removeAt(0)
        if (syncResult is AppResult.Success) syncedProgress += progress
        return syncResult
    }

    override suspend fun syncOfflineSessions(
        profileId: ProfileId,
        sessions: List<OfflineSession>,
    ): AppResult<List<OfflineSessionResult>> {
        offlineProfiles += profileId
        uploaded += sessions
        if (offlineResponses.isNotEmpty()) return offlineResponses.removeAt(0)
        return offlineUpload ?: AppResult.Success(offlineResults(sessions))
    }

    override suspend fun setFinished(
        profileId: ProfileId,
        bookId: LibraryItemId,
        isFinished: Boolean,
        position: kotlin.time.Duration,
    ): AppResult<Unit> = unsupported()

    override val auth: AuthApi = object : AuthApi {
        override suspend fun probe(serverUrl: String): AppResult<ServerProbe> = unsupported()

        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<AuthSession> =
            unsupported()

        override suspend fun refresh(serverUrl: String, refreshToken: AuthToken): AppResult<AuthSession> = unsupported()

        override suspend fun currentAccount(serverUrl: String, accessToken: AuthToken): AppResult<AccountState> =
            unsupported()

        override suspend fun signOut(serverUrl: String, accessToken: AuthToken): AppResult<Unit> = unsupported()
    }

    override val capabilities: CapabilityResolver = object : CapabilityResolver {
        override suspend fun resolve(
            serverId: ServerId,
            serverUrl: String,
            accessToken: AuthToken?,
        ): AppResult<ServerCapabilities> = unsupported()
    }

    /** PRODUCT_SPEC EPIC MGR — not exercised here. A throwing property cannot fall behind the interface. */
    override val management: ManagementApi get() = error("management is not exercised here")

    override val library: LibraryApi = object : LibraryApi {
        override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> = unsupported()

        override suspend fun listAuthors(profileId: ProfileId, libraryId: LibraryId): AppResult<List<Author>> =
            unsupported()

        override suspend fun listBooks(
            profileId: ProfileId,
            libraryId: LibraryId,
            onBatch: suspend (List<BookSnapshot>) -> Unit,
            cached: CachedLibrary,
            onCatalogueBatch: suspend (List<BookSnapshot>) -> Unit,
        ): AppResult<LibrarySnapshot> = unsupported()

        override suspend fun searchBooks(
            profileId: ProfileId,
            libraryId: LibraryId,
            query: String,
        ): AppResult<List<BookSnapshot>> = unsupported()
        override suspend fun fetchBook(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> =
            unsupported()
    }

    private companion object {
        fun <T> unsupported(): AppResult<T> =
            AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
    }
}
