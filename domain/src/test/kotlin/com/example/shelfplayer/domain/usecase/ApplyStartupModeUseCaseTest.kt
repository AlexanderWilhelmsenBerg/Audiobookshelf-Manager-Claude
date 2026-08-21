package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.StartupMode
import com.example.shelfplayer.domain.FakeSettingsRepository
import com.example.shelfplayer.domain.RecordingLogger
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.playback.StartupPlayer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC ROUTE-003 — what opening the app does, and the criterion that matters most:
 * *"App launch alone never starts playback by default."*
 *
 * Only one of the three modes makes a sound, and it is not the default. The test that would fail loudest if
 * somebody reordered the enum is the first one here.
 */
class ApplyStartupModeUseCaseTest {

    private val player = RecordingStartupPlayer()

    /** The default, and the whole of ROUTE-003's safety: no service, no notification, no sound. */
    @Test
    fun `the default mode touches nothing`() = runTest {
        useCase(StartupMode.Default)(BOOK)

        assertTrue(player.armed.isEmpty())
        assertTrue(player.played.isEmpty())
        assertEquals(StartupMode.OnMediaCommand, StartupMode.Default)
    }

    @Test
    fun `restore paused loads the book without playing it`() = runTest {
        useCase(StartupMode.RestorePaused)(BOOK)

        assertEquals(listOf(BOOK), player.armed)
        assertTrue(player.played.isEmpty(), "nothing was played")
    }

    /** Chosen deliberately or not at all. */
    @Test
    fun `resume on open plays the book`() = runTest {
        useCase(StartupMode.ResumeOnOpen)(BOOK)

        assertEquals(listOf(BOOK), player.played)
    }

    /**
     * A profile that has never played anything has nothing to restore.
     *
     * Without this, a fresh install in a restoring mode would ask the player to open `null` — and the
     * failure would land on somebody's very first launch.
     */
    @Test
    fun `a profile with no last book is left alone`() = runTest {
        useCase(StartupMode.ResumeOnOpen)(lastPlayed = null)

        assertTrue(player.played.isEmpty())
        assertTrue(player.armed.isEmpty())
    }

    /**
     * PRODUCT_SPEC AUTH-005 — a locked profile's book does not resume when the app opens.
     *
     * ROUTE-003 states no lock clause; extending it is a decision ADR-0023 records. The reasoning is that
     * this and a connecting device are the same event from two sides: something other than a deliberate
     * press is about to play a locked account's book out loud.
     */
    @Test
    fun `a locked profile does not resume on open`() = runTest {
        useCase(StartupMode.ResumeOnOpen, isLocked = true)(lastPlayed = BOOK)

        assertTrue(player.played.isEmpty(), "a locked account must not start playing by itself")
        assertTrue(player.armed.isEmpty())
    }

    /**
     * Arming is suppressed too, and it makes no sound — which is exactly why it needs suppressing.
     *
     * `RestorePaused` would put the locked account's title, author and cover on the lock screen, one headset
     * press from audio, and no code in this app can intercept that press.
     */
    @Test
    fun `a locked profile is not even armed`() = runTest {
        useCase(StartupMode.RestorePaused, isLocked = true)(lastPlayed = BOOK)

        assertTrue(player.armed.isEmpty())
        assertTrue(player.played.isEmpty())
    }

    private fun useCase(mode: StartupMode, isLocked: Boolean = false) = ApplyStartupModeUseCase(
        settings = FakeSettingsRepository(startupMode = mode),
        player = player,
        lock = ProfileLockGuard { isLocked },
        logger = RecordingLogger(),
    )

    private class RecordingStartupPlayer : StartupPlayer {
        val armed = mutableListOf<LibraryItemId>()
        val played = mutableListOf<LibraryItemId>()

        override suspend fun arm(bookId: LibraryItemId) {
            armed += bookId
        }

        override suspend fun play(bookId: LibraryItemId) {
            played += bookId
        }
    }

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
    }
}
