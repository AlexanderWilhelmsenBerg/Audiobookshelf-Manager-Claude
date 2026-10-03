package com.example.shelfplayer.feature.book

import androidx.lifecycle.SavedStateHandle
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.download.DownloadExecutionEvidence
import com.example.shelfplayer.domain.download.DownloadExecutionKey
import com.example.shelfplayer.domain.download.DownloadExecutionObserver
import com.example.shelfplayer.domain.download.DownloadExecutionSnapshot
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.DownloadBookUseCase
import com.example.shelfplayer.domain.usecase.EmbedMetadataUseCase
import com.example.shelfplayer.domain.usecase.EmbedTaskWatcher
import com.example.shelfplayer.domain.usecase.ObserveBookChaptersUseCase
import com.example.shelfplayer.domain.usecase.ObserveBookDetailsUseCase
import com.example.shelfplayer.domain.usecase.ObserveManagementPermissionsUseCase
import com.example.shelfplayer.domain.usecase.PauseDownloadUseCase
import com.example.shelfplayer.domain.usecase.RemoveDownloadUseCase
import com.example.shelfplayer.domain.usecase.RemoveFromDatabaseUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/** DL-001 / DL-003 / R-124 — execution evidence must reach the actual Book ViewModel and Pause use case. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModelDownloadTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val active = MutableStateFlow<Profile?>(profile(PROFILE, SERVER))
    private val manifests = MutableStateFlow(mapOf(KEY to offline()))
    private val executions = MutableStateFlow(mapOf(KEY to snapshot(DownloadExecutionEvidence.Retrying)))
    private val observedKeys = mutableListOf<Set<DownloadExecutionKey>>()
    private val operations = mutableListOf<String>()
    private val cancelled = mutableListOf<DownloadExecutionKey>()

    @Test
    fun `retry execution replaces Failed and live percent reaches the menu until durable completion`() = runTest {
        val model = subscribedModel()
        val retry = assertIs<DownloadButtonState.Downloading>(model.menu.value.download)
        assertEquals(DownloadPhase.Retrying, retry.phase)
        assertEquals(42, retry.percent)
        assertEquals(setOf(KEY), observedKeys.last())

        executions.value = mapOf(KEY to snapshot(DownloadExecutionEvidence.Running, 0.999f))
        runCurrent()
        val running = assertIs<DownloadButtonState.Downloading>(model.menu.value.download)
        assertEquals(DownloadPhase.Transferring, running.phase)
        assertEquals(99, running.percent)

        val stored = manifests.value.getValue(KEY)
        manifests.value = mapOf(
            KEY to stored.copy(
                state = DownloadState.Complete,
                files = stored.files.map { it.copy(state = DownloadState.Complete, downloadedBytes = 1_000) },
            ),
        )
        runCurrent()
        assertEquals(DownloadButtonState.Downloaded, model.menu.value.download)
    }

    @Test
    fun `missing execution falls back to the durable failure`() = runTest {
        val model = subscribedModel()
        assertIs<DownloadButtonState.Downloading>(model.menu.value.download)

        executions.value = emptyMap()
        runCurrent()

        assertEquals(DownloadButtonState.Failed, model.menu.value.download)
    }

    @Test
    fun `Pause from an executing failed manifest writes durable intent before cancelling the right work`() = runTest {
        val model = subscribedModel()
        val button = assertIs<DownloadButtonState.Downloading>(model.menu.value.download)
        model.onDownloadClicked(button)
        runCurrent()
        assertTrue(operations.isEmpty(), "the in-flight tap only opens the screen's prompt")

        model.onPauseDownload()
        runCurrent()

        assertEquals(listOf("paused", "cancelled"), operations)
        assertEquals(listOf(KEY), cancelled)
        assertEquals(DownloadState.Paused, manifests.value.getValue(KEY).state)
        assertIs<DownloadButtonState.Paused>(model.menu.value.download)
        assertEquals(DownloadExecutionEvidence.Retrying, executions.value.getValue(KEY).evidence)
    }

    @Test
    fun `shared claim refuses Pause without cancelling another profiles execution`() = runTest {
        manifests.value = mapOf(KEY to offline().copy(requestedBy = setOf(PROFILE, ProfileId("other"))))
        val model = subscribedModel()
        assertTrue(model.menu.value.isSharedDownload)

        model.onPauseDownload()
        runCurrent()

        assertTrue(operations.isEmpty())
        assertTrue(cancelled.isEmpty())
        assertIs<BookMessage.Failed>(model.message.value)
        assertIs<DownloadButtonState.Downloading>(model.menu.value.download)
    }

    @Test
    fun `profile switch consumes only execution for the newly active server and item`() = runTest {
        val model = subscribedModel()
        val otherProfile = ProfileId("profile-b")
        val otherServer = ServerId("server-b")
        val otherKey = DownloadExecutionKey(otherServer, BOOK)
        manifests.value = manifests.value + (
            otherKey to offline().copy(
                serverId = otherServer,
                requestedBy = setOf(otherProfile),
            )
            )
        executions.value = executions.value + (otherKey to snapshot(DownloadExecutionEvidence.Waiting, 0.73f))
        active.value = profile(otherProfile, otherServer)
        runCurrent()

        assertEquals(setOf(otherKey), observedKeys.last())
        val button = assertIs<DownloadButtonState.Downloading>(model.menu.value.download)
        assertEquals(DownloadPhase.Waiting, button.phase)
        assertEquals(73, button.percent)
    }

    private fun TestScope.subscribedModel(): BookViewModel {
        val model = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.menu.collect() }
        runCurrent()
        assertIs<BookUiState.Loaded>(model.uiState.value)
        return model
    }

    private fun model(): BookViewModel {
        val profiles = fake<ProfileRepository> { name, _ ->
            when (name) {
                "observeActiveProfile" -> active
                "observeServers" -> flowOf(emptyList<Any>())
                else -> error("Unexpected profile call: $name")
            }
        }
        val downloads = fake<DownloadRepository> { name, args ->
            val key = keyFrom(args)
            when (name) {
                "observe" -> manifests.map { it[key] }

                "markPaused" -> {
                    val stored = manifests.value.getValue(key)
                    manifests.value = manifests.value + (key to stored.copy(state = DownloadState.Paused))
                    operations += "paused"
                    AppResult.Success(Unit)
                }

                else -> error("Unexpected download call: $name")
            }
        }
        val scheduler = fake<DownloadScheduler> { name, args ->
            check(name == "cancel")
            cancelled += keyFrom(args)
            operations += "cancelled"
            Unit
        }
        val library = fake<LibraryRepository> { name, _ ->
            when (name) {
                "observeBook" -> flowOf(book().copy(serverId = requireNotNull(active.value).serverId))
                "observeChapters" -> flowOf(emptyList<Any>())
                else -> error("Unexpected library call: $name")
            }
        }
        val observer = DownloadExecutionObserver { keys ->
            observedKeys += keys
            executions.map { rows -> rows.filterKeys { it in keys } }
        }
        return BookViewModel(
            SavedStateHandle(mapOf(ShelfDestinations.ARG_BOOK_ID to BOOK.value)),
            ObserveBookDetailsUseCase(profiles, library),
            ObserveBookChaptersUseCase(profiles, library),
            fake<PlaybackHistoryRepository> { _, _ -> flowOf(emptyList<Any>()) },
            profiles,
            downloads,
            BookDownloadActions(
                DownloadBookUseCase(profiles, unused<BookAssetSource>(), downloads, scheduler),
                PauseDownloadUseCase(profiles, downloads, scheduler),
                observer,
            ),
            serverActions(profiles, library, downloads, scheduler),
            unused<PlaybackRepository>(),
        )
    }

    private fun serverActions(
        profiles: ProfileRepository,
        library: LibraryRepository,
        downloads: DownloadRepository,
        scheduler: DownloadScheduler,
    ): BookServerActions {
        val network = fake<NetworkMonitor> { _, _ -> flowOf(true) }
        val metadata = unused<MetadataRepository>()
        val remove = RemoveDownloadUseCase(profiles, downloads, unused<OfflineFiles>(), scheduler)
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        return BookServerActions(
            remove,
            RemoveFromDatabaseUseCase(profiles, metadata, remove),
            network,
            EmbedMetadataUseCase(
                metadata,
                ObserveManagementPermissionsUseCase(profiles, library, unused<CapabilityRepository>(), network),
                logger,
            ),
            EmbedTaskWatcher(unused<RealtimeUpdates>()),
        )
    }

    private fun snapshot(evidence: DownloadExecutionEvidence, fraction: Float = 0.42f) =
        DownloadExecutionSnapshot(evidence, DownloadProgress((fraction * 1_000).toLong(), 1_000, fraction))

    private fun offline() = OfflineBook(
        SERVER, BOOK, DownloadState.Failed,
        files = listOf(
            OfflineFile(
                remoteFileId = "file",
                index = 0,
                uri = "file:///test/book.part",
                state = DownloadState.Running,
                expectedBytes = 1_000,
                downloadedBytes = 200,
                mimeType = "audio/mp4",
                duration = null,
                eTag = null,
                lastModified = null,
            ),
        ),
        coverUri = null, requestedBy = setOf(PROFILE), isPinned = false,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun profile(id: ProfileId, serverId: ServerId) =
        Profile(id, serverId, "fixture", "Fixture", ProfileRole.Listener, false, null, true, canDownload = true)

    private fun book() = Book(
        serverId = SERVER, id = BOOK, libraryId = LibraryId("library"), title = "Fixture book", subtitle = null,
        authors = emptyList(), narrators = emptyList(), seriesMemberships = emptyList(), duration = Duration.ZERO,
        description = null, genres = emptyList(), tags = emptyList(), publishedYear = null, publisher = null,
        language = null, isbn = null, asin = null, isExplicit = false, isAbridged = false, coverPath = null,
        trackCount = 1, sizeBytes = 1_000, remoteUpdatedAt = null, addedAt = null, lastFetchedAt = Instant.EPOCH,
        progress = null, localAvailability = LocalAvailability.Partial,
    )

    private inline fun <reified T : Any> unused(): T = fake { name, _ -> error("Unexpected fixture call: $name") }

    private inline fun <reified T : Any> fake(crossinline answer: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name.substringBefore('-'), args.orEmpty())
        } as T

    private fun keyFrom(args: Array<out Any?>) = DownloadExecutionKey(
        (args[0] as? ServerId) ?: ServerId(args[0] as String),
        (args[1] as? LibraryItemId) ?: LibraryItemId(args[1] as String),
    )

    private companion object {
        val PROFILE = ProfileId("profile-a")
        val SERVER = ServerId("server-a")
        val BOOK = LibraryItemId("book")
        val KEY = DownloadExecutionKey(SERVER, BOOK)
    }
}
