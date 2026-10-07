package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Test
import java.time.Instant
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** AUTH-002/003: actual candidate codec/projector must fail closed at external boundaries. */
class GarminBoundaryRegressionTest {
    @Test
    fun fractionalProtocolAndNegativeTimestampAreRejected() {
        val codec = GarminMessageCodec(Clock())
        assertIs<GarminDecodeResult.Failure>(codec.decode(envelope() + ("v" to 1.5)))
        assertIs<GarminDecodeResult.Failure>(codec.decode(envelope() + ("ts" to -1L)))
    }

    @Test
    fun malformedCorrelationAndPayloadKeysAreRejected() {
        val codec = GarminMessageCodec(Clock())
        assertIs<GarminDecodeResult.Failure>(codec.decode(envelope() + ("r" to 42)))
        assertIs<GarminDecodeResult.Failure>(codec.decode(envelope() + ("p" to mapOf(42 to "value"))))
    }

    @Test
    fun externalProjectionRequiresCapturedPlaybackOwner() {
        val playback = PlaybackUiState(
            bookId = LibraryItemId("book-a"),
            title = "Private fixture",
            author = null,
            artworkUri = null,
            isPlaying = false,
            isLoading = false,
            position = 12.seconds,
            duration = 100.seconds,
        )
        assertNull(GarminSnapshotProjector(Clock()).project(playback, profile(), 1_700_000_000_123L))
    }

    private fun envelope() = mapOf(
        "v" to 1,
        "t" to "state_request",
        "id" to "watch-1",
        "ts" to 1_700_000_000_123L,
        "p" to emptyMap<String, Any>(),
    )

    private fun profile() = Profile(
        id = ProfileId("profile-a"),
        serverId = ServerId("server-a"),
        username = "fixture",
        displayName = "Fixture",
        role = ProfileRole.Listener,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = true,
    )

    private class Clock : AppClock {
        override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_123L)
        override fun elapsed(): Duration = Duration.ZERO
    }
}
