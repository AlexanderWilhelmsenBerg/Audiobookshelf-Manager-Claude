package com.example.shelfplayer.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Issue #91 — only a real server `/play` result may become an acknowledged start baseline.
 *
 * These tests intentionally cross the real [BookChanges.onBookOpened] boundary and then perform the same
 * [ResumeBaseline.onBookClosed] transition the service performs when Media3 installs the item. If
 * [BookChanges] ever stages `MediaItems.serverStartPositionFor(session)` directly again, the offline case
 * fails because a blank-id local session would be promoted as server evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookChangesServerEvidenceTest {

    @Test
    fun `blank id offline open cannot become acknowledged server evidence after media transition`() = runTest {
        val baseline = ResumeBaseline()
        val changes = bookChanges(baseline)

        changes.onBookOpened(session(id = ""))
        baseline.onBookClosed()

        assertNull(baseline.acknowledged(BOOK))
    }

    @Test
    fun `real server open becomes acknowledged server evidence after media transition`() = runTest {
        val baseline = ResumeBaseline()
        val changes = bookChanges(baseline)

        changes.onBookOpened(session(id = "remote-session"))
        baseline.onBookClosed()

        assertEquals(START, baseline.acknowledged(BOOK)?.position)
    }

    private fun TestScope.bookChanges(baseline: ResumeBaseline): BookChanges {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val clock = TestAppClock()
        val sync = SessionSyncCoordinator(
            repository = proxy<SessionSyncRepository> { name ->
                when (name) {
                    "openSession" -> AppResult.Success("local-session")
                    else -> null
                }
            },
            baseline = baseline,
            clock = clock,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val history = proxy<PlaybackHistoryRepository>()
        val sleepTimer = SleepTimerController(
            repository = proxy<SleepTimerRepository> { name ->
                if (name == "observeSettings") flowOf(SleepTimerSettings.Default) else null
            },
            shakes = ShakeDetector(ApplicationProvider.getApplicationContext<Context>(), NO_OP_LOGGER),
            sessionSync = sync,
            history = history,
            clock = clock,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val autoRewind = AutoRewindController(
            repository = proxy<PlaybackSettingsRepository> { name ->
                if (name == "observeSettings") flowOf(PlaybackSettings()) else null
            },
            clock = clock,
            history = history,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
        )
        val freshness = ResumeFreshnessCoordinator(
            playback = proxy<PlaybackRepository>(),
            profiles = proxy<ProfileRepository>(),
            baseline = baseline,
            realtime = RealtimeProgressEvidenceStore(),
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        return BookChanges(
            sleepTimer = sleepTimer,
            sessionSync = sync,
            autoRewind = autoRewind,
            resumeBaseline = baseline,
            resumeFreshness = freshness,
        )
    }

    private fun session(id: String) = PlaybackSession(
        id = id,
        profileId = PROFILE,
        bookId = BOOK,
        title = "Test book",
        author = null,
        coverUrl = null,
        startAt = START,
        duration = 8.hours,
        tracks = listOf(
            PlayableTrack(
                index = 0,
                url = "file:///book.m4b",
                startOffset = Duration.ZERO,
                duration = 8.hours,
                mimeType = "audio/mp4",
                isExcluded = false,
            ),
        ),
        chapters = emptyList(),
    )

    private inline fun <reified T : Any> proxy(crossinline answer: (String) -> Any? = { null }): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> answer(method.name) ?: defaultValue(method.returnType) } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        val PROFILE = ProfileId("profile-a")
        val BOOK = LibraryItemId("book-a")
        val START: Duration = 42.minutes
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
