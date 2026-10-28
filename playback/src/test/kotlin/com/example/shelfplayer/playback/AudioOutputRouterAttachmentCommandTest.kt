package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputRouterAttachmentCommandTest {
    @Test
    fun `Keep audio here rejects collision or newer intent delivered during measurement`() = runTest {
        for (collision in listOf(false, true)) {
            val fixture = fixture()
            val endpoint = fixture.observeEndpoint(47)
            fixture.router.outputIntentOwner.onBookChanged(15L)
            fixture.present(endpoint)
            var queries = 0
            fixture.measuredRoutes {
                queries++
                if (queries == 2) {
                    if (collision) fixture.colliding(endpoint.id) else fixture.router.selectCar()
                }
                setOf(endpoint.id)
            }

            assertTrue(!fixture.router.keepAudioHere())
            assertEquals(null, fixture.router.outputLock.value)
            if (!collision) {
                assertEquals(OutputIntentOwner.Destination.Car, fixture.router.explicitSelection.value?.destination)
            }
            runCurrent()
        }
    }

    @Test
    fun `Keep audio here cannot authorize a replacement player from its predecessor measurement`() = runTest {
        val fixture = fixture()
        val replacement = fixture()
        val endpoint = fixture.observeEndpoint(48)
        fixture.router.outputIntentOwner.onBookChanged(16L)
        fixture.present(endpoint)
        var queries = 0
        fixture.measuredRoutes {
            queries++
            if (queries == 2) fixture.replacePlayerFrom(replacement)
            setOf(endpoint.id)
        }

        assertTrue(!fixture.router.keepAudioHere())
        assertEquals(null, fixture.router.outputLock.value)
        assertTrue(replacement.pauseThreads.isEmpty())
    }

    @Test
    fun `Keep audio here arms only a unique exact live playing track and shares router owner`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(40, "Ambiguous A2DP")
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.router.outputIntentOwner.onBookChanged(7L)
        fixture.present(endpoint)

        assertTrue(fixture.router.keepAudioHere())
        val lock = fixture.router.outputLock.value
        assertEquals(OutputIntentOwner.LockPhase.Active, lock?.phase)
        assertEquals(endpoint.id, lock?.outputId)
        assertEquals(endpoint.id, fixture.router.selectedId.value)
        assertEquals(lock?.selectionSequence, fixture.router.explicitSelection.value?.sequence)
        assertTrue(!fixture.router.selectCarFromGenericController())
        assertEquals(lock?.selectionSequence, fixture.router.explicitSelection.value?.sequence)
        assertEquals(endpoint.id, fixture.router.outputLock.value?.outputId)
    }

    @Test
    fun `Keep audio here refuses unknown duplicate and stale measurements`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(43, "Ambiguous A2DP")
        fixture.router.outputIntentOwner.onBookChanged(5L)
        fixture.present(endpoint)

        fixture.measuredRoutes { null }
        assertTrue(!fixture.router.keepAudioHere())
        fixture.measuredRoutes { setOf(endpoint.id, "other") }
        assertTrue(!fixture.router.keepAudioHere())
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.colliding(endpoint.id)
        assertTrue(!fixture.router.keepAudioHere())
        fixture.colliding(null)
        fixture.present(endpoint, com.example.shelfplayer.core.model.playback.AudioOutputRole.Car)
        assertTrue(!fixture.router.keepAudioHere(), "a measured car/speaker category is not eligible")
        assertEquals(null, fixture.router.outputLock.value)
    }

    @Test
    fun `Keep audio here rejects a book generation change during route query`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(44, "Ambiguous A2DP")
        fixture.router.outputIntentOwner.onBookChanged(11L)
        fixture.present(endpoint)
        var reads = 0
        fixture.measuredRoutes {
            reads += 1
            if (reads == 2) fixture.router.outputIntentOwner.onBookChanged(12L)
            setOf(endpoint.id)
        }

        assertTrue(!fixture.router.keepAudioHere())
        assertEquals(null, fixture.router.outputLock.value)
    }

    @Test
    fun `prepare is refused after router detaches the still-current service player`() = runTest {
        val fixture = fixture()
        fixture.router.attach(fixture.player)
        val commands = PlaybackCommandEffects(
            currentPlayer = { fixture.player },
            itemGeneration = { 0L },
            isAttached = fixture.router::isAttachedPlayer,
            protectOutput = { player, willStartAudio, holdPaused ->
                fixture.router.protectManualPlayOnGuardedHeadset(player, willStartAudio, holdPaused)
            },
        )
        fixture.router.detach()

        assertEquals(false, commands.prepare(fixture.player, stillAuthorized = { true }))
        assertTrue(fixture.volumeChanges.isEmpty())
    }
}
