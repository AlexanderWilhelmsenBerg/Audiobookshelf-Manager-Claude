package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

class OpenPlaybackSessionUseCaseTest {
    private val profile = ProfileId("profile-a")
    private val book = LibraryItemId("book-a")

    @Test
    fun `authentication failure renews once and retries once`() = runTest {
        val playback = FakePlaybackRepository(
            AppResult.Failure(authentication()),
            AppResult.Success(session()),
        )
        val auth = FakeAuthRepository(SessionStatus.Active)
        val opener = opener(playback, auth)

        val result = opener(book)

        assertIs<AppResult.Success<PlaybackSession>>(result)
        assertEquals(2, playback.openCalls)
        assertEquals(1, auth.renewCalls)
    }

    @Test
    fun `failed renewal leaves the original authentication failure`() = runTest {
        val original = AppResult.Failure(authentication())
        val playback = FakePlaybackRepository(original)
        val auth = FakeAuthRepository(SessionStatus.ReauthenticationRequired)
        val opener = opener(playback, auth)

        val result = opener(book)

        assertEquals(original, result)
        assertEquals(1, playback.openCalls)
        assertEquals(1, auth.renewCalls)
    }

    @Test
    fun `401 after successful renewal marks session without a second renewal`() = runTest {
        val rejected = AppResult.Failure(authentication())
        val playback = FakePlaybackRepository(rejected, rejected)
        val auth = FakeAuthRepository(SessionStatus.Active)
        val opener = opener(playback, auth)

        assertEquals(rejected, opener(book))
        assertEquals(2, playback.openCalls)
        assertEquals(1, auth.renewCalls)
        assertEquals(1, auth.requireReauthenticationCalls)
    }

    @Test
    fun `non authentication failure is never renewed`() = runTest {
        val original = AppResult.Failure(
            AppError.Playback(summary = "stream unavailable", isRetryable = true),
        )
        val playback = FakePlaybackRepository(original)
        val auth = FakeAuthRepository(SessionStatus.Active)
        val opener = opener(playback, auth)

        val result = opener(book)

        assertEquals(original, result)
        assertEquals(1, playback.openCalls)
        assertEquals(0, auth.renewCalls)
    }

    private fun opener(playback: PlaybackRepository, auth: FakeAuthRepository): OpenPlaybackSessionUseCase {
        val profiles = FakeProfileRepository(profile)
        return OpenPlaybackSessionUseCase(
            profiles = profiles,
            playback = playback,
            renewSession = RenewProfileSessionUseCase(auth),
            requireReauthentication = RequireProfileReauthenticationUseCase(auth),
        )
    }

    private fun authentication() = AppError.Authentication(
        summary = "Session expired.",
        requiresReauthentication = true,
    )

    private fun session() = PlaybackSession(
        id = "session-a",
        profileId = profile,
        bookId = book,
        title = "Book",
        author = null,
        coverUrl = null,
        startAt = Duration.ZERO,
        duration = Duration.ZERO,
        tracks = emptyList(),
        chapters = emptyList(),
    )

    private class FakePlaybackRepository(vararg results: AppResult<PlaybackSession>) : PlaybackRepository {
        private val answers = ArrayDeque(results.toList())
        var openCalls = 0
            private set

        override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> {
            openCalls += 1
            return answers.removeFirst()
        }

        override suspend fun recordPosition(
            bookId: LibraryItemId,
            position: Duration,
            duration: Duration,
            owner: ProfileId?,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setFinished(
            bookId: LibraryItemId,
            isFinished: Boolean,
            position: Duration,
        ): AppResult<Unit> = AppResult.Success(Unit)

        /** SYNC-002 — no server behind this fake, which is what `Unavailable` means. */
        override suspend fun checkServerPosition(
            bookId: LibraryItemId,
            baseline: AcknowledgedPause?,
        ): ExternalSessionCheck = ExternalSessionCheck.Unavailable
    }

    private class FakeProfileRepository(private var active: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())
        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())
        override fun observeActiveProfile(): Flow<Profile?> = flowOf(null)
        override suspend fun activeProfileId(): ProfileId? = active
        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            active = profileId
            return AppResult.Success(Unit)
        }
    }

    private class FakeAuthRepository(private val renewalStatus: SessionStatus) : AuthRepository {
        var renewCalls = 0
            private set
        var requireReauthenticationCalls = 0
            private set

        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> {
            renewCalls += 1
            return AppResult.Success(renewalStatus)
        }

        override suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit> {
            requireReauthenticationCalls += 1
            return AppResult.Success(Unit)
        }

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = unused()
        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
            unused()
        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = unused()
        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = unused()
        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = unused()
        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = unused()

        private fun <T> unused(): AppResult<T> = error("not used by this test")
    }
}
