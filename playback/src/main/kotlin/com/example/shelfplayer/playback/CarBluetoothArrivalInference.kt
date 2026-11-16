package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.playback.CarArrivalResumeGate.Decision
import com.example.shelfplayer.playback.CarArrivalResumeGate.Phase
import com.example.shelfplayer.playback.CarArrivalResumeGate.Reason
import com.example.shelfplayer.playback.CarArrivalResumeGate.Status
import kotlin.time.Duration

/**
 * Joins the Bluetooth arrival window, Android's audio mode and the recovery gate for one focus loss.
 *
 * It decides nothing about routes and never calls Play: a returned decision goes through the same muted
 * actual-track proof as an Auto-boundary arrival. A ringing or active call is never mistaken for Auto.
 */
internal class CarBluetoothArrivalInference(
    private val window: CarBluetoothArrivalWindow,
    private val gate: CarArrivalResumeGate,
    private val audioMode: () -> Int?,
    private val now: () -> Duration,
    private val log: (String, List<LogField>) -> Unit,
) {
    /** Non-null only when this focus loss was waiting for a boundary and the car window supplied it. */
    fun onFocusLoss(decision: Decision, generation: Long?, selectionSequence: Long): Decision? {
        val waiting = decision.phase == Phase.Arrival && decision.status == Status.Armed &&
            decision.reason == Reason.FocusLossWaitingForBoundary
        val age = window.age()?.takeIf { waiting } ?: return null
        val mode = audioMode()
        val inferred = if (mode == AudioManager.MODE_NORMAL) {
            gate.onInferredCarArrival(now(), generation, selectionSequence)
        } else {
            null
        }
        if (inferred != null) window.consume()
        log(
            "Car Bluetooth arrival inference",
            listOf(
                LogField.Millis("windowAge", age.inWholeMilliseconds),
                LogField.Public("audioMode", modeName(mode)),
                LogField.Public(
                    "outcome",
                    when {
                        inferred != null -> "resume-issued"
                        mode != AudioManager.MODE_NORMAL -> "blocked-by-audio-mode"
                        else -> "not-qualified"
                    },
                ),
            ),
        )
        return inferred
    }

    private fun modeName(mode: Int?): String = when (mode) {
        null -> "unavailable"
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in-call"
        AudioManager.MODE_IN_COMMUNICATION -> "in-communication"
        else -> "other"
    }
}
