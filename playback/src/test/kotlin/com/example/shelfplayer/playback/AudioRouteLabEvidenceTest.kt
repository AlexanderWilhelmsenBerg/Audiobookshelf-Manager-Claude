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
    fun `role counts expose ambiguity rather than claiming A2DP is a headset`() {
        val outputs = listOf(
            AudioOutput("one", "first", DeviceKind.Bluetooth, role = AudioOutputRole.Ambiguous),
            AudioOutput("two", "second", DeviceKind.Bluetooth, role = AudioOutputRole.Ambiguous),
        )
        assertContains(AudioRouteLabEvidence.route(outputs, null), "Ambiguous:2")
    }
}
