package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ObserveRealtimeUpdatesUseCaseTest {

    @Test
    fun `one progress push is handed to the existing conflict safe repository path`() = runTest {
        val progress = AccountProgress(
            bookId = LibraryItemId("book-id"),
            position = 3.hours + 53.minutes,
            duration = 27.hours,
            isFinished = false,
            updatedAt = Instant.parse("2026-09-05T20:20:16Z"),
        )
        val repository = FakeLibraryRepository()
        val realtime = object : RealtimeUpdates {
            override val status = MutableStateFlow(RealtimeStatus.Connected)
            override fun events(profileId: ProfileId): Flow<RealtimeEvent> =
                flowOf(RealtimeEvent.ProgressChanged(progress, sessionId = "remote-session"))
        }
        val useCase = ObserveRealtimeUpdatesUseCase(
            realtime = realtime,
            libraryRepository = repository,
            logger = object : Logger {
                override fun log(event: LogEvent) = Unit
            },
        )

        useCase(TEST_PROFILE)

        assertEquals(listOf(listOf(progress)), repository.progressWritten)
    }
}
