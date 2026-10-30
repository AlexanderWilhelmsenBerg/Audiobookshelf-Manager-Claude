package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AudioRouteLabEvidenceTest {
    @Test
    fun `route proof and controller lifecycle preserve ordering without exposing caller identifiers`() {
        val proof = requireNotNull(
            AudioRouteLabEvidence.event(
                LoggedEvent(
                    Instant.EPOCH,
                    LogLevel.Info,
                    "Playback",
                    "Muted output route proof completed confirmation=Delegated destination=Car " +
                        "generation=3 selectionSequence=7 proofEpoch=8 requestGeneration=3 requestSelection=7 " +
                        "controllerPackage=private.package deviceAddress=private-address",
                ),
            ),
        )
        assertContains(proof, "output-proof")
        assertContains(proof, "confirmation=Delegated")
        assertContains(proof, "selectionSequence=7")
        assertContains(proof, "generation=3")
        assertContains(proof, "proofEpoch=8")
        assertContains(proof, "requestSelection=7")
        assertFalse(proof.contains("private"))

        val departure = requireNotNull(
            AudioRouteLabEvidence.event(
                LoggedEvent(
                    Instant.EPOCH,
                    LogLevel.Info,
                    "AndroidAuto",
                    "A car controller disconnected from the media session finalDeparture=false " +
                        "carStillConnected=true selectionSequence=7 controllerPackage=private.package",
                ),
            ),
        )
        assertContains(departure, "car-controller-unbound")
        assertContains(departure, "finalDeparture=false")
        assertContains(departure, "carStillConnected=true")
        assertFalse(departure.contains("private"))
    }

    @Test
    fun `event classifier exports only allowlisted keys and not private media metadata`() {
        val event = LoggedEvent(
            Instant.EPOCH,
            LogLevel.Info,
            "AndroidAuto",
            "MediaSession transport request observed requested=Play controllerKind=external " +
                "bookTitle=SecretBook serverHost=my-home.example keyCode=85 keyInputId=0",
        )
        val line = requireNotNull(AudioRouteLabEvidence.event(event))
        assertContains(line, "transport-intent")
        assertContains(line, "requested=Play")
        assertContains(line, "keyInputId=0")
        assertFalse(line.contains("SecretBook"))
        assertFalse(line.contains("my-home.example"))
    }

    @Test
    fun `arbitrary event lines never enter durable incident capture`() {
        assertNull(AudioRouteLabEvidence.event(LoggedEvent(Instant.EPOCH, LogLevel.Info, "Network", "token=secret")))
        assertNull(
            AudioRouteLabEvidence.event(
                LoggedEvent(Instant.EPOCH, LogLevel.Info, "Playback", "My secret filename=private.epub"),
            ),
        )
    }

    @Test
    fun `output report never exports a real device display name or id`() {
        val list = listOf(
            AudioOutput("bluetooth:private headset a", "Household Headset A", DeviceKind.Bluetooth, true),
            AudioOutput("bluetooth:private car", "Private Family Car", DeviceKind.Bluetooth, false),
        )
        val rendered = AudioRouteLabEvidence.route(list, list.first().id)
        assertContains(rendered, "selection=Ambiguous")
        assertContains(rendered, "actualTrack=unavailable")
        assertFalse(rendered.contains("Household"))
        assertFalse(rendered.contains("Family"))
        assertFalse(rendered.contains("private"))
    }

    @Test
    fun `incident recorder bounds retained rows by age and count`() {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        val records = listOf(
            "2026-10-15T09:00:00Z old-row",
            "2026-10-17T11:00:00Z first",
            "2026-10-17T11:10:00Z second",
            "2026-10-17T11:20:00Z third",
            "not-a-timestamp secret",
        )
        val retained = AudioRouteLabEvidence.recent(records, now, 2)
        assertFalse(retained.any { it.contains("old-row") })
        assertFalse(retained.any { it.contains("secret") })
        assertContains(retained.joinToString(","), "second")
        assertContains(retained.joinToString(","), "third")
    }

    @Test
    fun `role counts expose ambiguity rather than claiming A2DP is a headset`() {
        val outputs = listOf(
            AudioOutput("one", "first", DeviceKind.Bluetooth, role = AudioOutputRole.Ambiguous),
            AudioOutput("two", "second", DeviceKind.Bluetooth, role = AudioOutputRole.Ambiguous),
        )
        assertContains(AudioRouteLabEvidence.route(outputs, null), "Ambiguous:2")
    }
}
