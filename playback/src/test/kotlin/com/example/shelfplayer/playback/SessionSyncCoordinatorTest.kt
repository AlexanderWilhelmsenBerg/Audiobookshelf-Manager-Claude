package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SyncOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionSyncCoordinatorTest {

    @Test
    fun `opening a session does not return before its outbox row exists`() = runTest {
        val repository = BlockingSessionSyncRepository()
        val coordinator = coordinator(repository)
        val book = LibraryItemId("book-a")

        val opening = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.onSessionOpened(session(book))
        }

        assertEquals(book, repository.started.receive())
        assertFalse(opening.isCompleted, "the caller must still be waiting for the durable open")

        repository.allowNextOpen()
        opening.await()

        assertEquals(listOf(book), repository.opened)
    }

    @Test
    fun `two book openings cannot overtake each other`() = runTest {
        val repository = BlockingSessionSyncRepository()
        val coordinator = coordinator(repository)
        val firstBook = LibraryItemId("book-a")
        val secondBook = LibraryItemId("book-b")

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.onSessionOpened(session(firstBook))
        }
        assertEquals(firstBook, repository.started.receive())

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.onSessionOpened(session(secondBook))
        }

        assertTrue(
            repository.started.tryReceive().isFailure,
            "the second open must wait behind the first transition",
        )

        repository.allowNextOpen()
        first.await()

        assertEquals(secondBook, repository.started.receive())
        assertFalse(second.isCompleted, "the second caller must also wait for its own durable row")

        repository.allowNextOpen()
        second.await()

        assertEquals(listOf(firstBook, secondBook), repository.opened)
    }

    @Test
    fun `a player snapshot from another book is never synced into the active session`() = runTest {
        val repository = RecordingSessionSyncRepository()
        val coordinator = coordinator(repository)
        val activeBook = LibraryItemId("book-a")

        coordinator.onSessionOpened(session(activeBook))
        coordinator.attach(playerFor(LibraryItemId("book-b")))

        val accepted = coordinator.sync(SyncTrigger.Interval)

        assertFalse(accepted)
        assertEquals(0, repository.syncCalls)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(repository: SessionSyncRepository) =
        SessionSyncCoordinator(
            repository = repository,
            baseline = ResumeBaseline(),
            clock = TestAppClock(),
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private fun session(bookId: LibraryItemId) = PlaybackSession(
        id = "remote-${bookId.value}",
        profileId = ProfileId("profile-a"),
        bookId = bookId,
        title = "Test book",
        author = "Test author",
        coverUrl = null,
        startAt = 10.seconds,
        duration = 60.minutes,
        tracks = emptyList(),
        chapters = emptyList(),
    )

    private fun playerFor(bookId: LibraryItemId): Player {
        val item = MediaItem.Builder().setMediaId(bookId.value).build()
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> 10.seconds.inWholeMilliseconds
                "getDuration" -> 60.minutes.inWholeMilliseconds
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

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

    private class BlockingSessionSyncRepository : SessionSyncRepository {
        val started = Channel<LibraryItemId>(Channel.UNLIMITED)
        val opened = mutableListOf<LibraryItemId>()
        private val permits = Channel<Unit>(Channel.UNLIMITED)

        fun allowNextOpen() {
            permits.trySend(Unit).getOrThrow()
        }

        override suspend fun openSession(
            bookId: LibraryItemId,
            remoteSessionId: String?,
            title: String,
            author: String?,
            position: Duration,
            duration: Duration,
            startedAt: Instant,
        ): AppResult<String> {
            started.send(bookId)
            permits.receive()
            opened += bookId
            return AppResult.Success("local-${bookId.value}")
        }

        override suspend fun syncOpenSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> = AppResult.Success(SyncOutcome.Accepted)

        override suspend fun closeSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> = AppResult.Success(SyncOutcome.Accepted)

        override suspend fun drainOutbox(): AppResult<Int> = AppResult.Success(0)

        override fun observeDiagnostics(): Flow<SessionSyncDiagnostics> = emptyFlow()
    }

    private class RecordingSessionSyncRepository : SessionSyncRepository {
        var syncCalls: Int = 0
            private set

        override suspend fun openSession(
            bookId: LibraryItemId,
            remoteSessionId: String?,
            title: String,
            author: String?,
            position: Duration,
            duration: Duration,
            startedAt: Instant,
        ): AppResult<String> = AppResult.Success("local-${bookId.value}")

        override suspend fun syncOpenSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> {
            syncCalls += 1
            return AppResult.Success(SyncOutcome.Accepted)
        }

        override suspend fun closeSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> = AppResult.Success(SyncOutcome.Accepted)

        override suspend fun drainOutbox(): AppResult<Int> = AppResult.Success(0)

        override fun observeDiagnostics(): Flow<SessionSyncDiagnostics> = emptyFlow()
    }

    private companion object {
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
