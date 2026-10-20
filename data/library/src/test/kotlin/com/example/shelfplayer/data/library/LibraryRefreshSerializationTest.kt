package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** LIB-001 / #268 — concurrent refreshes must not publish stale snapshots or sync state. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRefreshSerializationTest {
    private val profile = ProfileId("fixture-profile")

    @Test
    fun `a failing older refresh cannot overwrite the result of a newer same-profile refresh`() = runTest {
        val database = createDatabase()
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val upstream = fakeGateway(dispatcher)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val libraryApi = object : LibraryApi by upstream.library {
                override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> {
                    calls++
                    if (calls == 1) {
                        entered.complete(Unit)
                        release.await()
                        return AppResult.Failure(AppError.Network())
                    }
                    return upstream.library.listLibraries(profileId)
                }
            }
            val repository = repository(database, upstream, libraryApi, dispatcher)
            val first = async { repository.refresh(profile) }
            entered.await()
            val second = async { repository.refresh(profile) }
            try {
                runCurrent()
                assertEquals(1, calls, "newer same-profile work must wait before reaching the network")
            } finally {
                release.complete(Unit)
            }
            assertIs<AppResult.Failure>(first.await())
            assertIs<AppResult.Success<Int>>(second.await())
            assertEquals(SyncStatus.Succeeded, repository.observeSyncState(profile).first().status)
        } finally {
            database.close()
        }
    }

    @Test
    fun `refreshes belonging to another profile do not share the same gate`() = runTest {
        val database = createDatabase()
        try {
            val other = ProfileId("other-profile")
            val fixture = checkNotNull(database.profileDao().findProfile(profile.value))
            database.profileDao().upsertProfile(fixture.copy(profileId = other.value))
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val upstream = fakeGateway(dispatcher)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val libraryApi = object : LibraryApi by upstream.library {
                override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> =
                    if (profileId == profile) {
                        entered.complete(Unit)
                        release.await()
                        AppResult.Failure(AppError.Network())
                    } else {
                        AppResult.Failure(AppError.Network())
                    }
            }
            val repository = repository(database, upstream, libraryApi, dispatcher)
            val first = async { repository.refresh(profile) }
            entered.await()
            val independent = async { repository.refresh(other) }
            try {
                runCurrent()
                assertTrue(independent.isCompleted, "unrelated profiles should refresh independently")
                assertFalse(first.isCompleted)
            } finally {
                release.complete(Unit)
            }
            assertIs<AppResult.Failure>(first.await())
            assertIs<AppResult.Failure>(independent.await())
            assertEquals(SyncStatus.Failed, repository.observeSyncState(other).first().status)
        } finally {
            database.close()
        }
    }

    private suspend fun createDatabase(): ShelfPlayerDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = "fixture-server",
                displayName = "Fixture",
                baseUrl = "https://fixture.invalid",
                detectedVersion = "fixture",
                isFixture = true,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = profile.value,
                serverId = "fixture-server",
                remoteUserId = null,
                username = "demo",
                displayName = "Demo listener",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
        return database
    }

    private fun fakeGateway(dispatcher: CoroutineDispatcher): FakeAudiobookshelfGateway = FakeAudiobookshelfGateway(
            loader = FixtureLibraryLoader(),
            clock = TestAppClock(),
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = dispatcher,
        )

    private fun repository(
        database: ShelfPlayerDatabase,
        upstream: AudiobookshelfGateway,
        libraryApi: LibraryApi,
        dispatcher: CoroutineDispatcher,
    ): DefaultLibraryRepository {
        val gateway = object : AudiobookshelfGateway by upstream {
            override val library: LibraryApi = libraryApi
        }
        return DefaultLibraryRepository(
            libraryDao = database.libraryDao(),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            syncStateDao = database.syncStateDao(),
            gateway = gateway,
            writer = LibrarySnapshotWriter(
                transaction = RoomDatabaseTransactionRunner(database),
                libraryWriteDao = database.libraryWriteDao(),
                progressDao = database.progressDao(),
                historyDao = database.playbackHistoryDao(),
            ),
            clock = TestAppClock(),
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = dispatcher,
        )
    }
}
