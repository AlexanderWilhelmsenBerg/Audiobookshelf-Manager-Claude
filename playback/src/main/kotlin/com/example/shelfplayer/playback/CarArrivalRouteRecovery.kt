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
 * Secures #36's already-proven exact headset across Android Auto lifecycle route churn.
 *
 * [CarLifecycleContinuityGate] owns whether an arrival/departure recovery is allowed and which exact headset
 * it may use. This class only performs that route operation: wait briefly for the captured output, reassert
 * it, and verify the preference survived the settle interval. It can never discover or substitute a target.
 */
internal class CarLifecycleRouteRecovery(
    private val recoveryWindow: Duration = DEFAULT_RECOVERY_WINDOW,
) {
    internal enum class Event {
        TargetAbsent,
        TargetReturned,
        Reasserted,
        PreferenceLost,
        EligibilityLost,
        Secured,
        TimedOut,
    }

    suspend fun secure(
        target: CarLifecycleContinuityGate.Target,
        outputs: StateFlow<List<AudioOutput>>,
        selectedId: StateFlow<String?>,
        isStillEligible: () -> Boolean,
        onEvent: (Event) -> Unit = {},
        reassert: suspend (String) -> Boolean,
    ): String? {
        var eligibilityLost = false
        val result = withTimeoutOrNull(recoveryWindow) {
            while (isStillEligible()) {
                val wasAbsent = !outputs.value.containsTarget(target.outputId)
                if (wasAbsent) onEvent(Event.TargetAbsent)

                outputs.first { current -> current.containsTarget(target.outputId) }
                if (wasAbsent) onEvent(Event.TargetReturned)
                if (!isStillEligible()) {
                    eligibilityLost = true
                    onEvent(Event.EligibilityLost)
                    return@withTimeoutOrNull null
                }

                val applied = reassert(target.outputId)
                onEvent(Event.Reasserted)
                if (!isStillEligible()) {
                    eligibilityLost = true
                    onEvent(Event.EligibilityLost)
                    return@withTimeoutOrNull null
                }

                val stillConnected = outputs.value.containsTarget(target.outputId)
                if (applied && stillConnected && selectedId.value == target.outputId) {
                    onEvent(Event.Secured)
                    return@withTimeoutOrNull target.outputId
                }

                onEvent(Event.PreferenceLost)
                // A remove/re-add during AudioOutputRouter's measured settle can clear selectedId to Automatic.
                // This bounded retry yields only to let that platform transition publish before the same target
                // is reasserted; it is not a correctness timer and cannot authorize a different route.
                delay(RETRY_DELAY)
            }
            eligibilityLost = true
            onEvent(Event.EligibilityLost)
            null
        }

        if (result == null && !eligibilityLost) onEvent(Event.TimedOut)
        return result
    }

    private fun List<AudioOutput>.containsTarget(outputId: String): Boolean =
        any { output -> output.id == outputId && output.isHeadsetCandidate }

    private companion object {
        /** The measured A2DP disappearance/reappearance completed in about one second. */
        val DEFAULT_RECOVERY_WINDOW: Duration = 2.seconds
        val RETRY_DELAY: Duration = 50.milliseconds
    }
}
