package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSession
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import com.example.shelfplayer.domain.repository.SyncOutboxRepository
import com.example.shelfplayer.domain.sync.SessionSyncUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class BookChangesServerEvidenceTest {

    @Test
    fun `server progress updates freshness evidence without seeking the player`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val baseline = ResumeBaseline()
        val realtime = RealtimeProgressEvidenceStore()
        val history = mutableListOf<PlaybackEvent>()
        val changes = changes(dispatcher, this, baseline, realtime) { event -> history += event }

        changes.onServerProgress(
            profileId = ProfileId("profile"),
            bookId = LibraryItemId("book"),
            progress = ServerProgress(position = 10.minutes, isFinished = false, updatedAt = null),
        )
        testScheduler.runCurrent()

        assertEquals(10.minutes, realtime.current()?.position)
        assertEquals(emptyList(), history)
    }

    private fun changes(
        dispatcher: CoroutineDispatcher,
        backgroundScope: CoroutineScope,
        baseline: ResumeBaseline,
        realtime: RealtimeProgressEvidenceStore,
        record: suspend (PlaybackEvent) -> Unit,
    ): BookChanges {
        val clock = TestClock()
        val sync = SessionSyncCoordinator(
            sync = proxy<SessionSyncUseCase>(),
            outbox = proxy<SyncOutboxRepository>(),
            baseline = baseline,
            clock = clock,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val history = proxy<PlaybackHistoryRepository> { name ->
            if (name == "record") {
                @Suppress("UNCHECKED_CAST")
                val args = currentArguments.get() ?: emptyArray()
                record(args[1] as PlaybackEvent)
                Unit
            } else {
                null
            }
        }
        val sleepTimer = SleepTimerController(
            repository = proxy<SleepTimerRepository> { name ->
                if (name == "observeSettings") flowOf(SleepTimerSettings.Default) else null
            },
            shakes = ShakeDetector(ApplicationProvider.getApplicationContext<Context>(), NO_OP_LOGGER),
            sessionSync = sync,
            history = history,
            stopHistoryCause = PlaybackStopHistoryCause(),
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
            realtime = realtime,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        return BookChanges(
            library = proxy<LibraryRepository>(),
            profiles = proxy<ProfileRepository>(),
            devices = proxy<DeviceRepository>(),
            sleepTimer = sleepTimer,
            autoRewind = autoRewind,
            freshness = freshness,
            realtime = realtime,
            history = history,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
    }

    private class TestClock : com.example.shelfplayer.core.common.time.AppClock {
        override fun now() = java.time.Instant.EPOCH
        override fun elapsed() = Duration.ZERO
    }

    companion object {
        private val currentArguments = ThreadLocal<Array<out Any?>?>()

        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }

        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> proxy(noinline answer: ((String) -> Any?)? = null): T {
            val clazz = T::class.java
            return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
                currentArguments.set(args ?: emptyArray())
                try {
                    answer?.invoke(method.name) ?: when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f
                        java.lang.Double.TYPE -> 0.0
                        java.lang.Void.TYPE -> Unit
                        else -> null
                    }
                } finally {
                    currentArguments.remove()
                }
            } as T
        }
    }
}
