package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.domain.FakeAuthRepository
import com.example.shelfplayer.domain.FakeBookmarkRepository
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC LIB-001 / 5.2 / AUTH-004 — the cheap sync that replaced a 491-request one.
 *
 * The acceptance case behind it: a book played on another device did not appear until the user
 * refreshed the whole library by hand.
 */
class SyncAccountUseCaseTest {

    private val profiles = FakeProfileRepository()
    private val auth = FakeAuthRepository()
    private val libraries = FakeLibraryRepository()
    private val bookmarks = FakeBookmarkRepository()

    private fun useCase() = SyncAccountUseCase(profiles, auth, libraries, bookmarks)

    @Test
    fun `positions the server reported are handed to the library layer`() = runTest {
        auth.willReportProgress(listOf(progress("book-1"), progress("book-2")))

        val result = useCase()()

        assertEquals(AppResult.Success(2), result)
        assertEquals(listOf("book-1", "book-2"), libraries.progressWritten.single().map { it.bookId.value })
    }

    /** The permission refresh and the progress write are the same call, so both happen or neither does. */
    @Test
    fun `the account is refreshed for the profile that asked`() = runTest {
        useCase()(TEST_PROFILE)

        assertEquals(listOf(TEST_PROFILE), auth.permissionRefreshes)
    }

    /**
     * A failure writes nothing.
     *
     * An unreachable server has not told us a position was rolled back, and writing an empty list would
     * be indistinguishable from being told there are none.
     */
    @Test
    fun `a failed account read leaves the stored positions alone`() = runTest {
        auth.willFailToRefreshPermissions(AppError.Network())

        assertIs<AppResult.Failure>(useCase()())

        assertTrue(libraries.progressWritten.isEmpty())
    }

    /**
     * PRODUCT_SPEC 5.2 — no active profile is nothing to do, not an error.
     *
     * This runs on app resume, and a first launch has no profile. Reporting a failure there would put an
     * error on a screen whose correct content is "sign in".
     */
    @Test
    fun `no active profile is a no-op`() = runTest {
        val result = SyncAccountUseCase(FakeProfileRepository(active = null), auth, libraries, bookmarks)()

        assertEquals(AppResult.Success(0), result)
        assertTrue(auth.permissionRefreshes.isEmpty())
    }

    private fun progress(id: String) = AccountProgress(
        bookId = LibraryItemId(id),
        position = 1.minutes,
        duration = 10.minutes,
        isFinished = false,
        updatedAt = Instant.EPOCH,
    )
}
