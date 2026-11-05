package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoRewindOwnershipTest {
    @Test
    fun `rewind history retains the source profile and event time before asynchronous persistence`() = runTest {
        val clock = TestAppClock()
        val history = History()
        val settings = Proxy.newProxyInstance(
            PlaybackSettingsRepository::class.java.classLoader,
            arrayOf(PlaybackSettingsRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "observeSettings")
            flowOf(
                PlaybackSettings(autoRewind = AutoRewind.Default.copy(isEnabled = true, afterShortPause = 5.seconds)),
            )
        } as PlaybackSettingsRepository
        val rewind = AutoRewindController(
            settings,
            clock,
            history,
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            backgroundScope,
        )
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItem(item("original", "source-profile"))
            player.seekTo(60_000L)
            rewind.attach(player)
            runCurrent()
            rewind.onPaused(wasUserInitiated = true)
            clock.advanceBy(1.seconds)
            val happenedAt = clock.now()
            rewind.onResumed()
            assertEquals(55_000L, player.currentPosition)
            player.setMediaItem(item("successor", "new-profile"))
            clock.advanceBy(20.seconds)
            runCurrent()

            assertEquals(LibraryItemId("original"), history.book)
            assertEquals(ProfileId("source-profile"), history.owner)
            assertEquals(happenedAt, history.at)
            assertEquals(55.seconds, history.to)
        } finally {
            player.release()
        }
    }

    private fun item(book: String, profile: String) = MediaItem.Builder().setMediaId(book).setUri("file:///fixture")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putString(MediaItems.KEY_OWNER_PROFILE_ID, profile)
                },
            ).build(),
        ).build()

    private class History : PlaybackHistoryRepository {
        var book: LibraryItemId? = null
        var owner: ProfileId? = null
        var at: Instant? = null
        var to: Duration? = null
        override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> = emptyFlow()
        override suspend fun record(
            bookId: LibraryItemId,
            event: PlaybackEvent,
            from: Duration?,
            to: Duration,
            detail: Duration?,
            at: Instant?,
            owner: ProfileId?,
        ) {
            book = bookId
            this.owner = owner
            this.at = at
            this.to = to
        }
        override suspend fun refreshServerSessions(bookId: LibraryItemId) = Unit
        override suspend fun clear(bookId: LibraryItemId) = Unit
    }
}
