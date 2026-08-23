package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC ROUTE-002 — a car connecting consults **the car's policy**.
 *
 * ### The defect these cover
 *
 * `AutoStartDecision` shipped in Phase 4 with a full truth table, and `PlaybackService.onPostConnect` went
 * on reading a global `autoPlayOnCarConnect` boolean from a different Settings section. Two controls decided
 * one thing, and the global one bypassed the other's warning and its `Arm only` default — so a listener who
 * had set their car to *Never react* in the device list could still be auto-played.
 *
 * The comment in `onPostConnect` claimed the per-device policies "are not built". They were. This is R-43's
 * shape again: correct logic behind a caller that never reached it, which is why these tests are about the
 * **wiring** and `AutoStartDecisionTest` is about the table.
 */
class CarConnectionTest {

    @Test
    fun `a car with no stored policy gets the Arm only default, not auto-play`() = runTest {
        val devices = RecordingDevices()

        val action = CarConnection.decide(devices, unlocked, NOW)

        assertEquals(AutoStartAction.Arm, action)
    }

    /**
     * **The defect, stated as a test.** The car's own policy decides, and `Never` means nothing happens.
     *
     * This is the case the global switch overrode: a listener who had deliberately told their car to stay
     * out of it, overruled from another screen.
     */
    @Test
    fun `a car set to never react does nothing`() = runTest {
        val devices = RecordingDevices(policy = DevicePolicy.Never)

        assertEquals(AutoStartAction.None, CarConnection.decide(devices, unlocked, NOW))
    }

    @Test
    fun `a car set to auto-play starts the book`() = runTest {
        val devices = RecordingDevices(policy = DevicePolicy.AutoPlay)

        assertEquals(AutoStartAction.ArmAndPlay, CarConnection.decide(devices, unlocked, NOW))
    }

    /** `Ask` arms: the paused session is the resume control, rather than a second notification. */
    @Test
    fun `a car set to ask arms the book`() = runTest {
        val devices = RecordingDevices(policy = DevicePolicy.Ask)

        assertEquals(AutoStartAction.Arm, CarConnection.decide(devices, unlocked, NOW))
    }

    /**
     * PRODUCT_SPEC ROUTE-002 / AUTH-005 — a locked account is not started, and not armed either.
     *
     * ADR-0023's reading: arming puts a locked account's title, author and cover on the lock screen, one
     * uninterceptable headset press from audio. A car is the clearest case — nobody pressed anything, and it
     * is a speaker in a room with other people in it.
     */
    @Test
    fun `a locked profile suppresses even auto-play`() = runTest {
        val devices = RecordingDevices(policy = DevicePolicy.AutoPlay)

        assertEquals(AutoStartAction.Suppressed, CarConnection.decide(devices, locked, NOW))
    }

    /**
     * The car is remembered before it is asked about, and remembered as a car.
     *
     * Order matters: `OutputDeviceWatcher` records the same reasoning. A first-ever connection has to be
     * stored before `policyFor` can answer, or it falls through the gap between the two. Remembering is also
     * what puts the car in Settings, which is where the listener changes their mind — a policy nobody can
     * see is the global switch's problem all over again.
     */
    @Test
    fun `the car is remembered as a car, with one identity, before its policy is read`() = runTest {
        val devices = RecordingDevices()

        CarConnection.decide(devices, unlocked, NOW)

        val remembered = devices.remembered.single()
        assertEquals(KnownDevice.CAR_ID, remembered.id)
        assertEquals(DeviceKind.Car, remembered.kind)
        assertEquals(NOW, remembered.lastSeenAt)
        assertTrue(devices.rememberedBeforeRead, "a policy read before the remember gets no default")
        assertEquals(listOf(KnownDevice.CAR_ID), devices.policiesRead, "only the car's policy is consulted")
    }

    /** The name is a fallback, never a vehicle somebody could be identified by (ROUTE-002, 14.5). */
    @Test
    fun `the remembered car carries no identifying name`() = runTest {
        val devices = RecordingDevices()

        CarConnection.decide(devices, unlocked, NOW)

        assertEquals(KnownDevice.CAR_DISPLAY_NAME, devices.remembered.single().displayName)
    }

    private val unlocked = ProfileLockGuard { false }
    private val locked = ProfileLockGuard { true }

    private class RecordingDevices(private val policy: DevicePolicy? = null) : DeviceRepository {
        val remembered = mutableListOf<KnownDevice>()
        val policiesRead = mutableListOf<String>()
        var rememberedBeforeRead = false
            private set

        override fun observeDevices(): Flow<List<KnownDevice>> = flowOf(emptyList())

        override suspend fun remember(device: KnownDevice): AppResult<Unit> {
            remembered += device
            return AppResult.Success(Unit)
        }

        override suspend fun setPolicy(deviceId: String, policy: DevicePolicy): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun forget(deviceId: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun policyFor(deviceId: String): DevicePolicy {
            if (remembered.isNotEmpty()) rememberedBeforeRead = true
            policiesRead += deviceId
            // What the real repository does for a device with no stored decision.
            return policy ?: DevicePolicy.Default
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")
    }
}
