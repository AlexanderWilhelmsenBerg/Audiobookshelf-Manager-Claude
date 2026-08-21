package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
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
import java.io.OutputStream

/**
 * A gateway whose answers the test decides, with real state rather than recorded expectations
 * (PRODUCT_SPEC 17.1 prefers a hand-written fake over a mock).
 *
 * It records the arguments it was called with, because several of the requirements under test are about
 * *what was sent*: a sign-out has to carry the credential of the profile being signed out, not whichever
 * one happens to be active (PRODUCT_SPEC 5.2).
 */
internal class FakeAuthGateway :
    AudiobookshelfGateway,
    AuthApi {

    var probeResult: AppResult<ServerProbe> = AppResult.Success(
        ServerProbe(
            isAudiobookshelf = true,
            serverVersion = "2.36.0",
            isInitialized = true,
            authMethods = listOf("local"),
        ),
    )

    var signInResult: AppResult<AuthSession> = AppResult.Success(session())

    var signOutResult: AppResult<Unit> = AppResult.Success(Unit)

    var refreshResult: AppResult<AuthSession> = AppResult.Success(session())

    var currentAccountResult: AppResult<AccountState> = AppResult.Success(account())

    val probedUrls = mutableListOf<String>()
    val signInCalls = mutableListOf<SignIn>()
    val signOutCalls = mutableListOf<SignOut>()
    val refreshCalls = mutableListOf<Refresh>()
    val currentAccountCalls = mutableListOf<CurrentAccount>()

    override val auth: AuthApi get() = this

    override suspend fun probe(serverUrl: String): AppResult<ServerProbe> {
        probedUrls += serverUrl
        return probeResult
    }

    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<AuthSession> {
        signInCalls += SignIn(serverUrl, username, password)
        return signInResult
    }

    override suspend fun refresh(serverUrl: String, refreshToken: AuthToken): AppResult<AuthSession> {
        refreshCalls += Refresh(serverUrl, refreshToken.value)
        return refreshResult
    }

    override suspend fun currentAccount(serverUrl: String, accessToken: AuthToken): AppResult<AccountState> {
        currentAccountCalls += CurrentAccount(serverUrl, accessToken.value)
        return currentAccountResult
    }

    override suspend fun signOut(serverUrl: String, accessToken: AuthToken): AppResult<Unit> {
        signOutCalls += SignOut(serverUrl, accessToken.value)
        return signOutResult
    }

    // The remaining sub-APIs are not part of what these tests exercise. They fail loudly rather than
    // returning empty values, so a test that starts depending on them cannot pass by accident.
    var capabilitiesResult: AppResult<ServerCapabilities>? = null
    val handshakes = mutableListOf<Handshake>()

    override val capabilities: CapabilityResolver = object : CapabilityResolver {
        override suspend fun resolve(
            serverId: ServerId,
            serverUrl: String,
            accessToken: AuthToken?,
        ): AppResult<ServerCapabilities> {
            handshakes += Handshake(serverId, serverUrl, accessToken)
            return capabilitiesResult
                ?: AppResult.Success(
                    ServerCapabilities(
                        serverId = serverId,
                        serverVersion = "2.36.0",
                        supported = emptySet(),
                        authMethods = listOf("local"),
                    ),
                )
        }
    }

    /**
     * PRODUCT_SPEC EPIC MGR — not part of the auth tests, and refusing rather than pretending.
     *
     * `get() = unsupported()` rather than an anonymous object with a stub per method. The object form has to
     * grow every time `ManagementApi` does, and it silently failed to: CI caught nine unimplemented members
     * here that a local build had reported as fine. A property that throws cannot fall behind an interface.
     */
    override val management: ManagementApi get() = error("management is not part of these tests")

    override val library: LibraryApi = object : LibraryApi {
        override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> = unsupported()

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

    override val playback: PlaybackApi = object : PlaybackApi {
        override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> =
            unsupported()

        override suspend fun syncSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> = unsupported()

        override suspend fun closeSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> = unsupported()

        override suspend fun syncOfflineSessions(
            profileId: ProfileId,
            sessions: List<OfflineSession>,
        ): AppResult<List<OfflineSessionResult>> = unsupported()

        override suspend fun setFinished(
            profileId: ProfileId,
            bookId: LibraryItemId,
            isFinished: Boolean,
            position: kotlin.time.Duration,
        ): AppResult<Unit> = unsupported()
    }

    /** PRODUCT_SPEC 11.1 — not part of the auth tests; every method says so rather than pretending. */
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
            at: kotlin.time.Duration,
            title: String,
        ): AppResult<Bookmark> = unsupported()

        override suspend fun rename(
            profileId: ProfileId,
            bookId: LibraryItemId,
            at: kotlin.time.Duration,
            title: String,
        ): AppResult<Bookmark> = unsupported()

        override suspend fun remove(
            profileId: ProfileId,
            bookId: LibraryItemId,
            at: kotlin.time.Duration,
        ): AppResult<Unit> = unsupported()
    }

    internal data class SignIn(val serverUrl: String, val username: String, val password: String)

    internal data class SignOut(val serverUrl: String, val accessToken: String)

    internal data class Refresh(val serverUrl: String, val refreshToken: String)

    internal data class CurrentAccount(val serverUrl: String, val accessToken: String)

    internal data class Handshake(val serverId: ServerId, val serverUrl: String, val accessToken: AuthToken? = null)

    internal companion object {
        fun session(
            accessToken: String = "access-1",
            refreshToken: String? = "refresh-1",
            userId: String? = "remote-user-1",
            username: String = "ada",
            role: ProfileRole = ProfileRole.Listener,
            accessibleLibraryIds: List<LibraryId> = emptyList(),
            hasAllLibraryAccess: Boolean = true,
            canDownload: Boolean = false,
        ) = AuthSession(
            accessToken = AuthToken(accessToken),
            refreshToken = refreshToken?.let(::AuthToken),
            userId = userId,
            username = username,
            role = role,
            access = LibraryAccess(
                hasAllLibraryAccess = hasAllLibraryAccess,
                accessibleLibraryIds = accessibleLibraryIds,
                canDownload = canDownload,
            ),
        )

        fun account(
            userId: String? = "remote-user-1",
            username: String = "ada",
            role: ProfileRole = ProfileRole.Listener,
            accessibleLibraryIds: List<LibraryId> = emptyList(),
            hasAllLibraryAccess: Boolean = true,
            hasAllTagAccess: Boolean = true,
            canDownload: Boolean = false,
        ) = AccountState(
            userId = userId,
            username = username,
            role = role,
            access = LibraryAccess(
                hasAllLibraryAccess = hasAllLibraryAccess,
                accessibleLibraryIds = accessibleLibraryIds,
                hasAllTagAccess = hasAllTagAccess,
                canDownload = canDownload,
            ),
        )

        private fun <T> unsupported(): AppResult<T> =
            AppError.ApiCompatibility(summary = "not part of this fake").asFailure()
    }
}
