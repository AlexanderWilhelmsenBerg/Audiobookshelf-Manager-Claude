package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.ListenedTime
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.time.Instant
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** SYNC-002 — an opened server session stages its position before Media3 receives the item. */
class BookChangesResumeBaselineTest {

    @Test
    fun `opening a normal session stages the server start until the player transitions`() {
        val baseline = ResumeBaseline()
        val changes = changes(baseline)

        changes.onBookOpened(session(startAt = 4.hours))

        assertNull(baseline.acknowledged(BOOK), "server evidence alone is not yet a player agreement")
        baseline.onBookClosed()
        kotlin.test.assertEquals(4.hours, baseline.acknowledged(BOOK)?.position)
    }

    private fun changes(baseline: ResumeBaseline): BookChanges {
        val dispatcher = Dispatchers.Unconfined
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return BookChanges(
            sleepTimer = SleepTimerController(
                repository = NoSleepRepository,
                history = NoHistoryRepository,
                clock = FixedClock,
                logger = NoOpLogger,
                applicationScope = scope,
                mainDispatcher = dispatcher,
            ),
            sessionSync = SessionSyncCoordinator(
                repository = NoSessionSyncRepository,
                listenedTime = ListenedTime(),
                baseline = baseline,
                clock = FixedClock,
                logger = NoOpLogger,
                applicationScope = scope,
                mainDispatcher = dispatcher,
            ),
            autoRewind = AutoRewindController(
                settings = NoPlaybackSettingsRepository,
                history = NoHistoryRepository,
                clock = FixedClock,
                applicationScope = scope,
                mainDispatcher = dispatcher,
            ),
            resumeBaseline = baseline,
        )
    }

    private fun session(startAt: Duration) = PlaybackSession(
        id = "session-restored",
        profileId = ProfileId("profile-a"),
        bookId = BOOK,
        title = "Restored book",
        author = null,
        coverUrl = null,
        startAt = startAt,
        duration = 10.hours,
        tracks = listOf(
            PlayableTrack(0, "https://books.example/0", Duration.ZERO, 5.hours, "audio/mpeg", false),
            PlayableTrack(1, "https://books.example/1", 5.hours, 5.hours, "audio/mpeg", false),
        ),
        chapters = emptyList(),
    )

    private companion object {
        val BOOK = LibraryItemId("restored-book")
        val SERVER = ServerId("server-a")

        val NoOpLogger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }

        val FixedClock = object : AppClock {
            override fun now(): Instant = Instant.EPOCH
        }
    }
}
