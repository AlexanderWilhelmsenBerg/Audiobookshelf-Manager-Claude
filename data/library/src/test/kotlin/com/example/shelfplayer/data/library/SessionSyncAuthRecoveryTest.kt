package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.database.entity.SessionOutboxState
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class SessionSyncAuthRecoveryTest : SessionSyncRepositoryFixture() {
    @Test
    fun `401 renews the captured profile once and retries the same sync`() = runTest {
        val sessionId = openId()
        val progress = SessionProgress(10.minutes, 6.hours, 90.seconds)
        gateway.syncResponses += AppResult.Failure(AppError.Authentication())
        gateway.syncResponses += AppResult.Success(Unit)
        auth.onRenew = { settings.setActiveProfile(ProfileId("profile-2")) }

        val result = repository.syncOpenSession(sessionId, progress, clock.now(), SyncTrigger.Paused)

        assertIs<AppResult.Success<*>>(result)
        assertEquals(profileId, auth.renewedProfile)
        assertEquals(listOf(profileId, profileId), gateway.syncProfiles)
        assertEquals(listOf("remote-1", "remote-1"), gateway.syncSessionIds)
        assertEquals(listOf(progress, progress), gateway.syncProgressRequests)
        assertEquals(SessionOutboxState.SYNCED, assertNotNull(database.sessionOutboxDao().find(sessionId)).state)
    }

    @Test
    fun `renewal denied keeps the failure queued without replay`() = runTest {
        val sessionId = openId()
        gateway.syncResult = AppResult.Failure(AppError.Authentication())
        auth.renewal = AppResult.Success(SessionStatus.ReauthenticationRequired)

        assertIs<AppResult.Failure>(
            repository.syncOpenSession(
                sessionId,
                SessionProgress(10.minutes, 6.hours, 90.seconds),
                clock.now(),
                SyncTrigger.Paused,
            ),
        )
        assertEquals(1, gateway.syncCount)
        assertEquals(1, assertNotNull(database.sessionOutboxDao().find(sessionId)).attempts)
        assertNull(database.sessionOutboxDao().find(sessionId)?.syncedAt)
    }

    @Test
    fun `second 401 is returned without a renewal loop`() = runTest {
        val sessionId = openId()
        gateway.syncResponses += AppResult.Failure(AppError.Authentication())
        gateway.syncResponses += AppResult.Failure(AppError.Authentication())

        assertIs<AppResult.Failure>(
            repository.syncOpenSession(
                sessionId,
                SessionProgress(10.minutes, 6.hours, 90.seconds),
                clock.now(),
                SyncTrigger.Paused,
            ),
        )
        assertEquals(2, gateway.syncCount)
        assertEquals(profileId, auth.renewedProfile)
        assertEquals(profileId, auth.reauthenticationProfile)
        assertEquals(1, assertNotNull(database.sessionOutboxDao().find(sessionId)).attempts)
    }

    @Test
    fun `cancellation during renewal is rethrown and durable position remains pending`() = runTest {
        val sessionId = openId()
        gateway.syncResult = AppResult.Failure(AppError.Authentication())
        auth.onRenew = { throw CancellationException("test cancellation") }

        assertFailsWith<CancellationException> {
            repository.syncOpenSession(
                sessionId,
                SessionProgress(10.minutes, 6.hours, 90.seconds),
                clock.now(),
                SyncTrigger.Paused,
            )
        }
        val row = assertNotNull(database.sessionOutboxDao().find(sessionId))
        assertEquals(10.minutes.inWholeMilliseconds, row.positionMillis)
        assertNull(row.syncedAt)
        assertEquals(0, row.attempts)
    }

    @Test
    fun `close request also renews and retries`() = runTest {
        val sessionId = openId()
        gateway.syncResponses += AppResult.Failure(AppError.Authentication())
        gateway.syncResponses += AppResult.Success(Unit)

        assertIs<AppResult.Success<*>>(
            repository.closeSession(
                sessionId,
                SessionProgress(10.minutes, 6.hours, 90.seconds),
                clock.now(),
                SyncTrigger.ServiceShutdown,
            ),
        )
        assertEquals(listOf(profileId, profileId), gateway.closeProfiles)
        assertEquals(listOf("remote-1", "remote-1"), gateway.closeSessionIds)
    }

    @Test
    fun `offline batch renews and retries as the same profile`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        repository.closeSession(
            sessionId,
            SessionProgress(10.minutes, 6.hours, 90.seconds),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineResponses += AppResult.Failure(AppError.Authentication())
        gateway.offlineResponses += AppResult.Success(listOf(OfflineSessionResult(sessionId, true, true)))

        assertIs<AppResult.Success<*>>(repository.drainOutbox())
        assertEquals(profileId, auth.renewedProfile)
        assertEquals(2, gateway.uploaded.size)
        assertEquals(listOf(profileId, profileId), gateway.offlineProfiles)
        assertEquals(listOf(sessionId), gateway.uploaded[0].map { it.id })
        assertEquals(listOf(sessionId), gateway.uploaded[1].map { it.id })
    }
}
