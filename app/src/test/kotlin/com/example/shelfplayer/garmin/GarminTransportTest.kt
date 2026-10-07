package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GarminTransportTest {
    @Test
    fun projectionUsesExistingPlaybackAndProfileState() {
        val snapshot = GarminSnapshotProjector(FakeClock()).project(
            playback = playback(),
            profile = profile(),
            updatedAtEpochMs = 1_700_000_000_123L,
        )

        requireNotNull(snapshot)
        assertEquals(1, snapshot.protocolVersion)
        assertEquals("profile-a", snapshot.profileId)
        assertEquals("book-a", snapshot.bookId)
        assertEquals("Book title", snapshot.title)
        assertEquals("Chapter 2", snapshot.chapterTitle)
        assertEquals(12_000L, snapshot.positionMs)
        assertEquals(100_000L, snapshot.durationMs)
        assertTrue(snapshot.playing)
        assertEquals("PHONE", snapshot.source)
    }

    @Test
    fun protocolCodecRejectsUnsupportedMajorAndBuildsCompactSnapshotEnvelope() {
        val codec = GarminMessageCodec(FakeClock())
        val unsupported = codec.decode(
            mapOf(
                "v" to 2,
                "t" to "hello",
                "id" to "watch-1",
                "p" to emptyMap<String, Any>(),
            ),
        )
        assertIs<GarminDecodeResult.Failure>(unsupported)
        assertEquals("unsupported_protocol_major", unsupported.reason)

        val outgoing = codec.snapshot(snapshot(), "watch-session", 1L)
        assertEquals(1, outgoing.payload["v"])
        assertEquals("snapshot", outgoing.payload["t"])
        assertEquals(outgoing.id, outgoing.payload["id"])
        val payload = outgoing.payload["p"] as Map<*, *>
        assertEquals("book-a", payload["bookId"])
        assertEquals("PHONE", payload["source"])
    }

    @Test
    fun clearStateIsAnExplicitPrivacyMessage() {
        val outgoing = GarminMessageCodec(FakeClock()).clearState("watch-session", 1L)
        assertEquals("clear_state", outgoing.payload["t"])
        val payload = outgoing.payload["p"] as Map<*, *>
        assertEquals("privacy_or_no_book", payload["reason"])
        assertFalse(payload.containsKey("title"))
        assertFalse(payload.containsKey("profileId"))
    }

    @Test
    fun meaningfulChangesSendButClockLikeTickerUpdatesDoNotSpamBle() {
        val policy = GarminSnapshotSendPolicy()
        val base = snapshot()

        assertTrue(policy.shouldSend(base, Duration.ZERO))
        policy.markSent(base, Duration.ZERO)

        assertFalse(
            policy.shouldSend(
                base.copy(positionMs = base.positionMs + 500, updatedAt = base.updatedAt + 500),
                500.milliseconds,
            ),
        )
        assertTrue(
            policy.shouldSend(
                base.copy(playing = false, updatedAt = base.updatedAt + 1_000),
                1.seconds,
            ),
        )
        assertTrue(
            policy.shouldSend(
                base.copy(positionMs = base.positionMs + 8_000, updatedAt = base.updatedAt + 1_000),
                1.seconds,
            ),
        )
        assertTrue(
            policy.shouldSend(
                base.copy(positionMs = base.positionMs + 30_000, updatedAt = base.updatedAt + 30_000),
                30.seconds,
            ),
        )
    }

    @Test
    fun chapterChangeIsMeaningful() {
        val policy = GarminSnapshotSendPolicy()
        val base = snapshot()
        policy.markSent(base, Duration.ZERO)

        assertTrue(
            policy.shouldSend(
                base.copy(chapterTitle = "Chapter 3", updatedAt = base.updatedAt + 100),
                100.milliseconds,
            ),
        )
    }

    @Test
    fun connectionReducerPreservesMissingSdkDeviceAndAppTruth() {
        val device = GarminDeviceRef(42L, "fenix 8")

        assertEquals(
            GarminBridgeState.SdkUnavailable("GCM_NOT_INSTALLED"),
            GarminSdkState.Unavailable("GCM_NOT_INSTALLED").toBridgeState(),
        )
        assertEquals(GarminBridgeState.NoDevice, GarminSdkState.NoDevice.toBridgeState())
        assertEquals(
            GarminBridgeState.DeviceDisconnected("fenix 8"),
            GarminSdkState.DeviceDisconnected(device).toBridgeState(),
        )
        assertEquals(
            GarminBridgeState.AppNotInstalled("fenix 8"),
            GarminSdkState.AppNotInstalled(device).toBridgeState(),
        )
        assertEquals(
            GarminBridgeState.AppUnavailable("fenix 8", "INVALID_STATE"),
            GarminSdkState.AppUnavailable(device, "INVALID_STATE").toBridgeState(),
        )
        assertEquals(
            GarminBridgeState.AppAvailable("fenix 8"),
            GarminSdkState.AppAvailable(device).toBridgeState(),
        )
    }

    @Test
    fun privacyPolicyFailsClosedForLockedOrReauthenticationProfiles() {
        val policy = GarminPrivacyPolicy()
        val unlocked = ProfileLockState.Unlocked
        val locked = ProfileLockState.Locked(ProfileId("profile-a"))

        assertTrue(policy.mayExpose(profile(), unlocked))
        assertFalse(policy.mayExpose(profile(), locked))
        assertFalse(policy.mayExpose(profile().copy(requiresReauthentication = true), unlocked))
    }

    @Test
    fun fasterPlaybackDoesNotStreamSeekPacketsEverySecond() {
        val policy = GarminSnapshotSendPolicy()
        val fast = snapshot().copy(speed = 3f)
        policy.markSent(fast, Duration.ZERO)
        assertFalse(policy.shouldSend(fast.copy(positionMs = fast.positionMs + 15_000), 5.seconds))
        assertTrue(policy.shouldSend(fast.copy(speed = 1.5f), 5.seconds))
    }

    private fun playback() = PlaybackUiState(
        ownerProfileId = ProfileId("profile-a"),
        bookId = LibraryItemId("book-a"),
        title = "Book title",
        author = "Book author",
        artworkUri = null,
        isPlaying = true,
        isLoading = false,
        position = 12.seconds,
        duration = 100.seconds,
        chapters = listOf(
            Chapter(
                serverId = ServerId("server-a"),
                bookId = LibraryItemId("book-a"),
                index = 1,
                title = "Chapter 2",
                start = 10.seconds,
                end = 20.seconds,
            ),
        ),
        currentChapter = Chapter(
            serverId = ServerId("server-a"),
            bookId = LibraryItemId("book-a"),
            index = 1,
            title = "Chapter 2",
            start = 10.seconds,
            end = 20.seconds,
        ),
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

    private fun snapshot() = GarminPlaybackSnapshot(
        protocolVersion = 1,
        profileId = "profile-a",
        bookId = "book-a",
        title = "Book title",
        author = "Book author",
        chapterTitle = "Chapter 2",
        positionMs = 12_000L,
        durationMs = 100_000L,
        updatedAt = 1_700_000_000_000L,
        playing = true,
    )

    private class FakeClock : AppClock {
        override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_000L)
        override fun elapsed(): Duration = Duration.ZERO
    }
}
