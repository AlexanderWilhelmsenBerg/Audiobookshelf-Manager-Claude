package com.example.shelfplayer.playback

import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarProfileSwitcherTest {
    @Test
    fun `duplicate row selections switch and silently restore only once`() = runTest {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSwitch = { release.await() }
        val first = async(UnconfinedTestDispatcher(testScheduler)) { fixture.switcher.select(OTHER) }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { fixture.switcher.select(OTHER) }
        assertEquals(1, fixture.switches)
        release.complete(Unit)
        assertEquals(SessionResult.RESULT_SUCCESS, first.await().resultCode)
        assertEquals(SessionResult.RESULT_SUCCESS, second.await().resultCode)
        assertEquals(1, fixture.switches)
        assertEquals(listOf(OTHER), fixture.restored)
    }

    @Test
    fun `already active row does not switch or restore`() = runTest {
        val fixture = Fixture()
        assertEquals(SessionResult.RESULT_SUCCESS, fixture.switcher.select(FIRST).resultCode)
        assertEquals(0, fixture.switches)
        assertEquals(emptyList(), fixture.restored)
    }

    @Test
    fun `missing row is a bad value without invoking domain switch`() = runTest {
        val fixture = Fixture()
        assertEquals(SessionError.ERROR_BAD_VALUE, fixture.switcher.select(null).resultCode)
        assertEquals(0, fixture.switches)
    }

    @Test
    fun `locked profile preserves permission refusal and never restores`() = runTest {
        val fixture = Fixture()
        fixture.result = AppResult.Failure(AppError.Security("Locked"))
        val result = fixture.switcher.select(OTHER)
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        assertEquals("Unlock on phone", result.sessionError?.message)
        assertEquals(emptyList(), fixture.restored)
    }

    @Test
    fun `reauthentication switch does not request playable restore`() = runTest {
        val fixture = Fixture()
        fixture.result = AppResult.Success(SessionStatus.ReauthenticationRequired)
        assertEquals(SessionResult.RESULT_SUCCESS, fixture.switcher.select(OTHER).resultCode)
        assertEquals(emptyList(), fixture.restored)
    }

    private class Fixture {
        var active = FIRST
        var switches = 0
        val restored = mutableListOf<ProfileId>()
        var beforeSwitch: suspend () -> Unit = {}
        var result: AppResult<SessionStatus> = AppResult.Success(SessionStatus.Active)
        val switcher = CarProfileSwitcher(
            isActive = { active == it },
            switch = { target ->
                switches++
                beforeSwitch()
                if (result is AppResult.Success) active = target
                result
            },
            restore = restored::add,
            unavailableMessage = { "Unavailable" },
            unlockMessage = { "Unlock on phone" },
            failedMessage = { "Failed" },
        )
    }

    private companion object {
        val FIRST = ProfileId("first")
        val OTHER = ProfileId("other")
    }
}
