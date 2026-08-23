package com.example.shelfplayer.playback

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 5.2 / ROUTE-001 — the car must be told when the tree it cached belongs to another account.
 *
 * ### The defect
 *
 * `onGetChildren` builds the browse tree on demand, which made it look self-updating. It is not. A browser
 * fetches once and caches; Media3 re-asks only after `notifyChildrenChanged`, and nothing called it. A head
 * unit therefore kept whatever it had loaded first — **including the previous profile's book titles after a
 * switch**, in front of whoever else is in the car. That is a profile boundary rather than stale UI.
 *
 * ### What this covers, and what it cannot
 *
 * It covers the signal: that changing the active profile produces exactly one invalidation, and that
 * ordinary re-emissions of the *same* profile produce none — a notification per library write would spend
 * the car's binder on nothing.
 *
 * It cannot cover the delivery. `MediaLibrarySession.notifyChildrenChanged` needs a real session and a real
 * connected browser, which is `:playback`'s absent instrumented tier and, past that, the Desktop Head Unit.
 * `AutoBrowseInvalidationTest` proving the flow and a DHU run proving the car acts on it are two different
 * jobs; only the first one runs here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBrowseInvalidationTest {

    private val profiles = SwitchableProfiles()

    @Test
    fun `switching the active profile invalidates the tree exactly once`() = runTest {
        auto().invalidations().test {
            profiles.switchTo(OTHER)

            awaitItem()
            expectNoEvents()
        }
    }

    /**
     * **The first emission is not a change.**
     *
     * `observeActiveProfile` replays the current profile to every new subscriber. Treating that as an
     * invalidation would make the service notify a browser that has not fetched anything yet, spending a
     * binder round trip over the car's link to tell it nothing.
     */
    @Test
    fun `the profile already active when the service starts is not an invalidation`() = runTest {
        auto().invalidations().test {
            expectNoEvents()
        }
    }

    /** A profile re-emitting unchanged — a permission refresh, a `lastUsedAt` touch — is not a switch. */
    @Test
    fun `re-emitting the same profile does not invalidate`() = runTest {
        auto().invalidations().test {
            profiles.touch()
            profiles.touch()

            expectNoEvents()
        }
    }

    @Test
    fun `switching away and back invalidates twice`() = runTest {
        auto().invalidations().test {
            profiles.switchTo(OTHER)
            awaitItem()

            profiles.switchTo(PROFILE)
            awaitItem()

            expectNoEvents()
        }
    }

    /** Signing out entirely is a change too: the tree must not keep serving the account that left. */
    @Test
    fun `losing the active profile invalidates`() = runTest {
        auto().invalidations().test {
            profiles.signOut()

            awaitItem()
            expectNoEvents()
        }
    }

    /**
     * Every parent a browser can subscribe to is named, or the ones left out keep serving the old account.
     *
     * Asserted against the tree's own root and tab ids rather than a copied list, so a tab added to
     * `rootTabs` without being added to `browsableParents` fails here instead of on somebody's dashboard.
     */
    @Test
    fun `every browsable parent is invalidated`() {
        val parents = auto().browsableParents()

        assertEquals(parents.size, parents.distinct().size, "a parent must not be notified twice")
        assertTrue(AutoLibrary.ROOT in parents, "the browse root")
        assertTrue(AutoLibrary.RECENT_ROOT in parents, "the resume tile's root, which is a separate node")
        listOf(
            AutoLibrary.TAB_CONTINUE,
            AutoLibrary.TAB_RECENT,
            AutoLibrary.TAB_DISCOVER,
            AutoLibrary.TAB_AGAIN,
            AutoLibrary.TAB_CHAPTERS,
            AutoLibrary.TAB_HISTORY,
        ).forEach { tab -> assertTrue(tab in parents, "$tab is browsable and must be invalidated") }
    }

    private fun auto(): AutoLibrary = AutoLibrary(
        context = ApplicationProvider.getApplicationContext(),
        profiles = profiles,
        library = EmptyLibrary,
        history = NoHistory,
        homeShelves = ObserveHomeShelvesUseCase(profiles, EmptyLibrary, UnconfinedTestDispatcher()),
    )

    private object NoHistory : PlaybackHistoryRepository {
        override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> = flowOf(emptyList())

        override suspend fun record(
            bookId: LibraryItemId,
            event: PlaybackEvent,
            from: Duration?,
            to: Duration,
            detail: Duration?,
            at: Instant?,
            owner: ProfileId?,
        ) = Unit

        override suspend fun clear(bookId: LibraryItemId) = Unit
    }

    /** A profile repository whose active profile the test moves. */
    private class SwitchableProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(profileOf(PROFILE))

        fun switchTo(id: ProfileId) {
            active.value = profileOf(id)
        }

        /** Re-emits the same profile with a different unrelated field, as a permission refresh would. */
        fun touch() {
            val current = active.value ?: return
            active.value = current.copy(lastUsedAt = Instant.ofEpochMilli(1_000))
        }

        fun signOut() {
            active.value = null
        }

        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)

        companion object {
            fun profileOf(id: ProfileId) = Profile(
                id = id,
                serverId = SERVER,
                username = "demo",
                displayName = "Demo listener",
                role = ProfileRole.Listener,
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
            )
        }
    }

    private object EmptyLibrary : LibraryRepository {
        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = flowOf(emptyList())

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = flowOf(null)

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = flowOf(emptyList())

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = flowOf(emptyList())

        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId): Flow<List<Chapter>> =
            flowOf(emptyList())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = flowOf(null)

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = emptyFlow()

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)

        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)
    }

    private companion object {
        val SERVER = ServerId("srv_books")
        val PROFILE = ProfileId("prf_ada")
        val OTHER = ProfileId("prf_grace")
    }
}
