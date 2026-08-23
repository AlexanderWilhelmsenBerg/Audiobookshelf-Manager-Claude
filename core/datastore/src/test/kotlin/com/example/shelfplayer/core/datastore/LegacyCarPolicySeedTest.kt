package com.example.shelfplayer.core.datastore

import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC ROUTE-002 — the retired global car switch, carried forward as a policy.
 *
 * ### What is being protected
 *
 * The global "start playing when a car connects" switch is gone: it bypassed the per-device policy's warning
 * and its `Arm only` default, so two controls decided one thing and the louder one won. But somebody who had
 * turned it **on** chose auto-play, and deleting the switch must not quietly take that away — a setting that
 * changes meaning without being touched is the same class of surprise the switch itself was.
 *
 * So the stored bit survives as a seed for the car's policy. These tests pin the three properties that make
 * that safe: it only fires when the flag was on, a real stored policy always beats it, and it cannot produce
 * a second car row.
 */
class LegacyCarPolicySeedTest {

    /** The common case: the flag was never turned on, so nothing is invented. */
    @Test
    fun `a device list is untouched when the legacy flag was off`() {
        val devices = listOf(headphones)

        assertEquals(devices, devices.seeded(legacyAutoPlay = false))
    }

    @Test
    fun `an empty list stays empty when the legacy flag was off`() {
        assertTrue(emptyList<KnownDevice>().seeded(legacyAutoPlay = false).isEmpty())
    }

    /**
     * **The migration.** A listener who had the switch on gets a car set to auto-play.
     *
     * Without this they would plug into their car the day after updating and find it silently stopped
     * resuming, with no setting having visibly changed.
     */
    @Test
    fun `the legacy flag becomes an auto-play car`() {
        val seeded = emptyList<KnownDevice>().seeded(legacyAutoPlay = true)

        val car = seeded.single()
        assertEquals(KnownDevice.CAR_ID, car.id)
        assertEquals(DeviceKind.Car, car.kind)
        assertEquals(DevicePolicy.AutoPlay, car.policy)
    }

    /** It joins the other devices rather than replacing them. */
    @Test
    fun `seeding keeps the devices already known`() {
        val seeded = listOf(headphones).seeded(legacyAutoPlay = true)

        assertEquals(2, seeded.size)
        assertTrue(seeded.any { it.id == headphones.id })
        assertTrue(seeded.any { it.id == KnownDevice.CAR_ID })
    }

    /**
     * **A stored car policy always wins.** This is what makes the seed self-retiring.
     *
     * Once the listener has said anything about their car, the legacy bit is irrelevant for good — including
     * when what they said was *Never react*, which is precisely the decision the old global switch used to
     * override.
     */
    @Test
    fun `a stored car policy is never overridden by the legacy flag`() {
        for (stored in DevicePolicy.entries) {
            val existing = car(policy = stored)

            val seeded = listOf(existing).seeded(legacyAutoPlay = true)

            assertEquals(1, seeded.size, "no second car row for $stored")
            assertEquals(stored, seeded.single().policy, "the stored decision must win over the legacy flag")
        }
    }

    /** Applying it twice cannot double the car — the guard is membership, not a counter. */
    @Test
    fun `seeding is idempotent`() {
        val once = emptyList<KnownDevice>().seeded(legacyAutoPlay = true)
        val twice = once.seeded(legacyAutoPlay = true)

        assertEquals(1, twice.count { it.id == KnownDevice.CAR_ID })
    }

    /**
     * The seeded row sorts last, because it is not a connection.
     *
     * `knownDevices` orders by `lastSeenAt` so the thing you just plugged in is at the top. A setting
     * somebody chose months ago is not that, and stamping it with the current time would make the list lie
     * about what it is ordered by.
     */
    @Test
    fun `the seeded car claims no recent connection`() {
        val car = emptyList<KnownDevice>().seeded(legacyAutoPlay = true).single()

        assertEquals(Instant.EPOCH, car.lastSeenAt)
    }

    private fun List<KnownDevice>.seeded(legacyAutoPlay: Boolean): List<KnownDevice> =
        withCarSeededFromLegacyFlag(legacyAutoPlay)

    private val headphones = KnownDevice(
        id = "bt:studio",
        kind = DeviceKind.Bluetooth,
        displayName = "Studio headphones",
        policy = DevicePolicy.ArmOnly,
        lastSeenAt = Instant.parse("2026-08-23T10:00:00Z"),
    )

    private fun car(policy: DevicePolicy) = KnownDevice(
        id = KnownDevice.CAR_ID,
        kind = DeviceKind.Car,
        displayName = KnownDevice.CAR_DISPLAY_NAME,
        policy = policy,
        lastSeenAt = Instant.parse("2026-08-20T09:00:00Z"),
    )
}
