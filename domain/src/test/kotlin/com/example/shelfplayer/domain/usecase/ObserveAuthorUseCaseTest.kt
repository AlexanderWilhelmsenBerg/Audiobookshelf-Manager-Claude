package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.library.AuthorShelfObservation
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveAuthorUseCaseTest {
    @Test
    fun `only a successful complete catalogue permits series completion and revocation clears it`() = runTest {
        val profiles = FakeProfileRepository()
        val books = MutableStateFlow(listOf(book("book", sequence = "1")))
        val sync = MutableStateFlow(SyncState.idle(TEST_SERVER, TEST_PROFILE))
        val repository = object : LibraryRepository by FakeLibraryRepository() {
            override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = books
            override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = sync
        }
        var latest: AuthorShelfObservation? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveAuthorUseCase(
                profiles,
                repository,
                UnconfinedTestDispatcher(testScheduler),
            )(AuthorId("author-1")).collect {
                latest =
                    it
            }
        }
        assertFalse(requireNotNull(latest?.shelf).catalogueComplete)
        sync.value = sync.value.copy(status = SyncStatus.Succeeded)
        assertTrue(requireNotNull(latest?.shelf).catalogueComplete)
        listOf(
            SyncStatus.Syncing,
            SyncStatus.PartiallySucceeded,
            SyncStatus.Failed,
            SyncStatus.NeverSynced,
        ).forEach { status ->
            sync.value = sync.value.copy(status = status)
            assertFalse(requireNotNull(latest?.shelf).catalogueComplete, status.name)
        }
        books.value = emptyList()
        assertNull(latest?.shelf)
    }

    @Test
    fun `profile switch clears outgoing private author before incoming query emits`() = runTest {
        val profiles = FakeProfileRepository()
        val incoming = MutableSharedFlow<List<Book>>()
        val requested = mutableListOf<ProfileId>()
        val repository = object : LibraryRepository by FakeLibraryRepository() {
            override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> {
                requested += profileId
                return if (profileId == TEST_PROFILE) MutableStateFlow(listOf(book("private"))) else incoming
            }
        }
        var latest: AuthorShelfObservation? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveAuthorUseCase(
                profiles,
                repository,
                UnconfinedTestDispatcher(testScheduler),
            )(AuthorId("author-1")).collect {
                latest =
                    it
            }
        }
        assertEquals("private", requireNotNull(latest?.shelf).books.single().id.value)
        profiles.setActiveProfile(ProfileId("incoming"))
        assertNull(latest?.shelf)
        assertTrue(requireNotNull(latest).isLoading)
        assertEquals(listOf(TEST_PROFILE, ProfileId("incoming")), requested)
        incoming.emit(listOf(book("incoming")))
        assertEquals("incoming", requireNotNull(latest?.shelf).books.single().id.value)
        profiles.signOut()
        assertNull(latest?.shelf)
    }
}
