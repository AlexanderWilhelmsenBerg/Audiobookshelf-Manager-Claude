package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.offlineBook
import com.example.shelfplayer.domain.profile
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DL-003 / 5.2: a recorded claim, current grant and visible file are all required. */
class DownloadClaimAccessTest {
    private val ada = ProfileId("ada")
    private val grace = ProfileId("grace")
    private val book = offlineBook("book", requestedBy = setOf(ada, grace))
    private val claims = Claims(book)
    private val profiles = Profiles(listOf(ada, grace).map { profile(it).copy(canDownload = true) })
    private val lookedUp = mutableListOf<ProfileId>()
    private val fetched = mutableListOf<ProfileId>()
    private val rejected = mutableSetOf<ProfileId>()
    private var beforeAssets: suspend (ProfileId) -> Unit = {}
    private val assetErrors = mutableMapOf<ProfileId, AppError>()
    private var visibleFiles = book.files
    private val access = DownloadClaimAccess(
        claims,
        profiles,
        object : BookAssetSource {
            override suspend fun assetsFor(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookAssets> {
                lookedUp += profileId
                beforeAssets(profileId)
                return assetErrors[profileId]?.let { AppResult.Failure(it) }
                    ?: AppResult.Success(BookAssets(visibleFiles, coverUrl = null, estimatedBytes = 1_024))
            }
        },
    )

    @Test
    fun `the queued owner wins while still eligible`() = runTest {
        assertIs<AppResult.Success<Unit>>(fetch())
        assertEquals(listOf(ada), fetched)
        assertEquals(listOf(ada), lookedUp)
    }

    @Test
    fun `a released queued owner is replaced by a remaining claimant`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(grace))
        assertIs<AppResult.Success<Unit>>(fetch())
        assertEquals(listOf(grace), fetched)
    }

    @Test
    fun `reauthentication blocks the original owner without a network attempt`() = runTest {
        changeProfile(ada) { it.copy(requiresReauthentication = true) }
        assertIs<AppResult.Success<Unit>>(fetch())
        assertEquals(listOf(grace), fetched)
    }

    @Test
    fun `a matching item claim from a different server grants no access`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(grace))
        changeProfile(grace) { it.copy(serverId = ServerId("other-server")) }
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
        assertTrue(lookedUp.isEmpty())
    }

    @Test
    fun `the current download grant is required even for an existing claim`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(grace))
        changeProfile(grace) { it.copy(canDownload = false) }
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `catalogue access is checked for the replacement claimant`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(grace))
        assetErrors[grace] = AppError.Authorization(summary = "This book is not in your library.")
        assertIs<AppResult.Failure>(fetch())
        assertEquals(listOf(grace), lookedUp)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a stale or excluded file is never fetched for another claimant`() = runTest {
        visibleFiles = emptyList()
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `releasing a claim during asset lookup prevents its transfer`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(ada))
        beforeAssets = { claims.stored.value = book.copy(requestedBy = emptySet()) }
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `revoking permission during asset lookup prevents its transfer`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(ada))
        beforeAssets = { changeProfile(ada) { current -> current.copy(canDownload = false) } }
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `removing a profile during asset lookup prevents its transfer`() = runTest {
        claims.stored.value = book.copy(requestedBy = setOf(ada))
        beforeAssets = { profiles.stored.value = emptyList() }
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `an unclaimed or forgotten copy never uses the active profile`() = runTest {
        claims.stored.value = book.copy(requestedBy = emptySet())
        assertIs<AppResult.Failure>(fetch())
        claims.stored.value = null
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `authentication and permission failures try each other claimant once`() = runTest {
        val errors = listOf(
            AppError.Authentication(),
            AppError.Authorization(summary = "Downloading is no longer allowed."),
        )
        for (error in errors) {
            rejected.clear()
            fetched.clear()
            assertIs<AppResult.Success<Unit>>(
                fetch {
                    if (it ==
                        ada
                    ) {
                        AppResult.Failure(error)
                    } else {
                        AppResult.Success(Unit)
                    }
                },
            )
            assertEquals(listOf(ada, grace), fetched)
            assertEquals(setOf(ada), rejected)
            fetched.clear()
            assertIs<AppResult.Success<Unit>>(fetch())
            assertEquals(listOf(grace), fetched, "later files do not retry the rejected account")
        }
    }

    @Test
    fun `all expired claimants fail without a retry loop`() = runTest {
        assertIs<AppResult.Failure>(fetch { AppResult.Failure(AppError.Authentication()) })
        assertEquals(listOf(ada, grace), fetched)
        fetched.clear()
        assertIs<AppResult.Failure>(fetch())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `network and compatibility failures do not switch credentials`() = runTest {
        val errors = listOf(
            AppError.Network(),
            AppError.ApiCompatibility(summary = "Unsupported transfer response."),
        )
        for (error in errors) {
            fetched.clear()
            val result = assertIs<AppResult.Failure>(fetch { AppResult.Failure(error) })
            assertEquals(error, result.error)
            assertEquals(listOf(ada), fetched)
            assertTrue(rejected.isEmpty())
            fetched.clear()
            assetErrors[ada] = error
            val lookupFailure = assertIs<AppResult.Failure>(fetch())
            assertEquals(error, lookupFailure.error)
            assertTrue(fetched.isEmpty(), "failed catalogue lookup must not fetch or try another credential")
            assertTrue(rejected.isEmpty())
            assetErrors.clear()
        }
    }

    @Test
    fun `cancellation is rethrown without a second owner attempt`() = runTest {
        assertFailsWith<CancellationException> { fetch { throw CancellationException("stopped") } }
        assertEquals(listOf(ada), fetched)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `artwork requires book access but does not replan audio files`() = runTest {
        visibleFiles = emptyList()
        assertIs<AppResult.Success<Unit>>(
            access.withOwner(book, ada, rejected) {
                fetched += it
                AppResult.Success(Unit)
            },
        )
        assertEquals(listOf(ada), fetched)
        assertEquals(book.files, claims.stored.value?.files)
    }

    private suspend fun fetch(
        transfer: suspend (ProfileId) -> AppResult<Unit> = { AppResult.Success(Unit) },
    ): AppResult<Unit> = access.withOwner(book, ada, rejected, book.files.single().remoteFileId) {
        fetched += it
        transfer(it)
    }

    private fun changeProfile(id: ProfileId, change: (Profile) -> Profile) {
        profiles.stored.value = profiles.stored.value.map { if (it.id == id) change(it) else it }
    }

    private class Claims(book: OfflineBook) : DownloadRepository by FakeDownloadRepository() {
        val stored = MutableStateFlow<OfflineBook?>(book)
        override fun observe(serverId: ServerId, itemId: LibraryItemId) =
            stored.map { it?.takeIf { row -> row.serverId == serverId && row.itemId == itemId } }
    }

    private class Profiles(initial: List<Profile>) : ProfileRepository by FakeProfileRepository() {
        val stored = MutableStateFlow(initial)
        override fun observeProfiles() = stored
        override fun observeActiveProfile(): Nothing = error("Queued work must not read the active profile")
        override suspend fun activeProfileId(): Nothing = error("Queued work must not read the active profile")
    }
}
