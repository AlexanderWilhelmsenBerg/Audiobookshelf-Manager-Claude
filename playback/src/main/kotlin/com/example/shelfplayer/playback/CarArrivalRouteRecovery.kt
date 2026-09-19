package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Secures #36's already-proven pause-time headset across Android Auto's transient output-list flap.
 *
 * This class does not decide *which* headset owns continuity; [CarArrivalResumeGate] already did that while
 * playback was audible. It only makes the routing operation resilient: wait briefly for that exact output,
 * reassert it, and verify the preference survived the settle interval. If Android removed/re-added the
 * device and [AudioOutputRouter] fell back to Automatic, the loop reasserts the same target again.
 */
internal class CarArrivalRouteRecovery(private val recoveryWindow: Duration = DEFAULT_RECOVERY_WINDOW) {
    suspend fun secure(
        target: CarArrivalResumeGate.Target,
        outputs: StateFlow<List<AudioOutput>>,
        selectedId: StateFlow<String?>,
        isStillEligible: () -> Boolean,
        reassert: suspend (String) -> Boolean,
    ): String? = withTimeoutOrNull(recoveryWindow) {
        while (isStillEligible()) {
            outputs.first { current ->
                current.any { output -> output.id == target.outputId && output.isHeadsetCandidate }
            }
            if (!isStillEligible()) return@withTimeoutOrNull null

            val applied = reassert(target.outputId)
            if (!isStillEligible()) return@withTimeoutOrNull null

            val stillConnected = outputs.value.any { output ->
                output.id == target.outputId && output.isHeadsetCandidate
            }
            if (applied && stillConnected && selectedId.value == target.outputId) {
                return@withTimeoutOrNull target.outputId
            }
            // A remove/re-add during AudioOutputRouter's settle can clear selectedId back to Automatic.
            // Yield before retrying so the main-thread AudioDeviceCallback can publish the reconnect.
            delay(RETRY_DELAY)
        }
        null
    }

    private companion object {
        /** The measured A2DP disappearance/reappearance completed in about one second. */
        val DEFAULT_RECOVERY_WINDOW: Duration = 2.seconds
        val RETRY_DELAY: Duration = 50.milliseconds
    }
}
