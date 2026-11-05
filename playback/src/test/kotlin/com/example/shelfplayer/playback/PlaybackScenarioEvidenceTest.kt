package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S06-S09, S18, S22: route origin is not inferred from preferred output, media
 * button transport, or AudioManager media policy. Only actual playing track
 * evidence can be used as physical verification.
 */
internal class PlaybackScenarioEvidenceTest {
    private val headset = "bluetooth:synthetic-1"

    @Test
    fun onlyConfirmedActualTrackOnTheExactEndpointPassesPhysicalVerification() {
        for (source in RouteEvidenceSource.entries) {
            for (confidence in RouteConfidence.entries) {
                val evidence = RouteEvidence(source, confidence, headset)
                val verified = source == RouteEvidenceSource.ActualAudioTrack &&
                    confidence == RouteConfidence.Confirmed
                if (verified) {
                    assertTrue(evidence.confirms(headset))
                } else {
                    assertFalse(evidence.confirms(headset), "$source / $confidence cannot prove a physical sink")
                }
            }
        }
    }

    @Test
    fun wrongOrMissingEndpointFailsEvenWithConfirmedAudioTrackOrigin() {
        assertFalse(RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Confirmed).confirms(headset))
        assertFalse(
            RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Confirmed, "bluetooth:car")
                .confirms(headset),
        )
        assertFalse(
            RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Ambiguous, headset)
                .confirms(headset),
        )
        assertFalse(
            RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Unknown, headset)
                .confirms(headset),
        )
    }

    @Test
    fun unknownPhysicalMediaButtonDoesNotSelectARoute() {
        assertFalse(
            RouteEvidence(RouteEvidenceSource.MediaButtonUnknown, RouteConfidence.Confirmed, headset)
                .confirms(headset),
        )
        assertFalse(
            shouldSelectCarForControllerPlay(
                requestedPlay = true,
                controllerIsCar = false,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun explicitCarTransportPlayCanRequestCarWithoutProvingActualSink() {
        assertTrue(
            shouldSelectCarForControllerPlay(
                requestedPlay = true,
                controllerIsCar = true,
                playWhenReady = false,
            ),
        )
        assertFalse(
            RouteEvidence(RouteEvidenceSource.ListenerPreference, RouteConfidence.Confirmed, "bluetooth:car")
                .confirms("bluetooth:car"),
        )
        assertFalse(
            shouldSelectCarForControllerPlay(
                requestedPlay = true,
                controllerIsCar = true,
                playWhenReady = true,
            ),
            "duplicate Gearhead Play during already-running playback must not steal routing",
        )
    }

    @Test
    fun guardedHeadsetMustPauseOnUnknownWrongOrMultipleActualRoutes() {
        assertTrue(shouldSuspendGuardedPlayback(headset, headset, null, true))
        assertTrue(shouldSuspendGuardedPlayback(headset, headset, emptySet(), true))
        assertTrue(shouldSuspendGuardedPlayback(headset, headset, setOf("bluetooth:car"), true))
        assertTrue(shouldSuspendGuardedPlayback(headset, headset, setOf(headset, "bluetooth:car"), true))
        assertFalse(shouldSuspendGuardedPlayback(headset, headset, setOf(headset), true))
    }
}
