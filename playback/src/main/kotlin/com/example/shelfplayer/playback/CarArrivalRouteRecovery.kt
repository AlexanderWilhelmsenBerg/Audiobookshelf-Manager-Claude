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
 * [CarArrivalResumeGate] owns whether an arrival/departure recovery is allowed and which exact headset
 * it may use. This class only performs that route operation: wait briefly for the captured output, reassert
 * it, and verify the preference survived the settle interval. It can never discover or substitute a target.
 */
internal class CarArrivalRouteRecovery(private val recoveryWindow: Duration = DEFAULT_RECOVERY_WINDOW) {
    internal enum class Event {
        TargetAbsent,
        TargetReturned,
        Reasserted,
        PreferenceLost,
        RouteUnknown,
        RouteCompeting,
        EligibilityLost,
        Secured,
        TimedOut,
    }

    suspend fun secure(
        target: CarArrivalResumeGate.Target,
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

                // If Android still routes media exclusively to the previously heard headset, do not
                // disturb a healthy AudioTrack by reapplying its preferred device. Automatic selection is
                // acceptable here only with positive active-route evidence; absence/ambiguity takes the
                // existing bounded reassertion path below.
                if (alreadySecured(target.outputId, outputs.value, selectedId.value)) {
                    onEvent(Event.Secured)
                    return@withTimeoutOrNull target.outputId
                }

                val applied = reassert(target.outputId)
                onEvent(Event.Reasserted)
                if (!isStillEligible()) {
                    eligibilityLost = true
                    onEvent(Event.EligibilityLost)
                    return@withTimeoutOrNull null
                }

                // A preferred device is only a request. Positive evidence of playback on
                // another output must block the one-shot automatic Play.
                if (preferenceSecured(applied, target.outputId, outputs.value, selectedId.value)) {
                    onEvent(Event.Secured)
                    return@withTimeoutOrNull target.outputId
                }

                if (applied && selectedId.value == target.outputId) {
                    val outputsNow = outputs.value
                    onEvent(
                        if (outputsNow.any(AudioOutput::isActive)) Event.RouteCompeting else Event.RouteUnknown,
                    )
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

    private fun alreadySecured(outputId: String, outputs: List<AudioOutput>, selected: String?): Boolean =
        headsetAlreadyActive(outputId, outputs) && (selected == null || selected == outputId)

    private fun preferenceSecured(
        applied: Boolean,
        outputId: String,
        outputs: List<AudioOutput>,
        selected: String?,
    ): Boolean {
        if (!applied || selected != outputId) return false
        // An absent route report is not evidence for the headset. In auto-5 the
        // preferred device was connected, but Android reported honoured=false.
        // Treat that as unresolved until the original headset is uniquely active.
        return headsetAlreadyActive(outputId, outputs)
    }

    private fun List<AudioOutput>.containsTarget(outputId: String): Boolean =
        any { output -> output.id == outputId && output.isHeadsetCandidate }

    private companion object {
        /** The measured A2DP disappearance/reappearance completed in about one second. */
        val DEFAULT_RECOVERY_WINDOW: Duration = 2.seconds
        val RETRY_DELAY: Duration = 50.milliseconds
    }
}

/**
 * Conservative continuity fast path: the captured headset is the only active output.
 * Merely connected, preferred, or chosen-by-list-order is not sufficient evidence.
 */
internal fun headsetAlreadyActive(outputId: String, outputs: List<AudioOutput>): Boolean =
    outputs.any { it.id == outputId && it.isHeadsetCandidate && it.isActive } &&
        outputs.none { it.id != outputId && it.isActive }
