package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputIntentDepartureTest {
    @Test
    fun `a buffering Play intent is not mistaken for a paused departed Car choice`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.playWhenReady = false
        fixture.router.selectCar()
        runCurrent()
        val selection = fixture.router.explicitSelection.value
        fixture.projection = false
        fixture.playWhenReady = true
        fixture.playbackState = Player.STATE_BUFFERING

        assertFalse(fixture.router.handoffs.retirePausedCar(selection))
        assertEquals(selection, fixture.router.explicitSelection.value)
        assertTrue(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `retained explicit lock prevents departure cleanup of a paused Car transfer`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        assertTrue(fixture.router.keepAudioHere())
        fixture.playWhenReady = false
        fixture.router.selectCar()
        runCurrent()
        val before = fixture.router.outputIntentOwner.state.value
        fixture.projection = false

        assertFalse(fixture.router.handoffs.retirePausedCar(before.selection))
        assertEquals(before, fixture.router.outputIntentOwner.state.value)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `departure captured before a newer Car choice cannot retire that successor`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.playWhenReady = false
        fixture.router.selectCar()
        runCurrent()
        val old = fixture.router.explicitSelection.value
        fixture.router.selectCar()
        runCurrent()
        val newer = fixture.router.outputIntentOwner.state.value
        fixture.projection = false

        assertFalse(fixture.router.handoffs.retirePausedCar(old))
        assertEquals(newer, fixture.router.outputIntentOwner.state.value)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `synchronous newer Headset choice wins over departure publication and its queued Car preference`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.playWhenReady = false
        fixture.router.selectCar()
        runCurrent()
        val selection = fixture.router.explicitSelection.value
        val preferences = fixture.preferenceRequests.size
        fixture.projection = false
        var replaced = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.router.outputIntentOwner.state.collect {
                if (!replaced && it.selection?.destination == OutputIntentOwner.Destination.Automatic) {
                    replaced = true
                    fixture.router.select(fixture.outputId)
                }
            }
        }

        assertFalse(fixture.router.handoffs.retirePausedCar(selection))
        assertTrue(replaced)
        assertEquals(fixture.outputId, fixture.router.explicitSelection.value?.outputId)
        assertEquals(fixture.outputId, fixture.router.selectedId.value)
        assertEquals(preferences, fixture.preferenceRequests.size, "retirement itself issues no preference")
        runCurrent()
        assertEquals(OutputIntentOwner.Destination.Output, fixture.router.explicitSelection.value?.destination)
        assertFalse(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
    }
}
