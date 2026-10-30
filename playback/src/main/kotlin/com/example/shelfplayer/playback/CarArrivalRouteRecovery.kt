package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 * it, and wait for measured output or eligibility for the existing silent probe. ProbeReady does not prove
 * the route and cannot authorize unmute. This class can never discover or substitute a target.
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
        ProbeReady,
        TimedOut,
    }

    suspend fun secure(
        target: CarArrivalResumeGate.Target,
        outputs: StateFlow<List<AudioOutput>>,
        selectedId: StateFlow<String?>,
        isStillEligible: () -> Boolean,
        measured: (String) -> Boolean,
        canProbe: (String) -> Boolean = { false },
        onEvent: (Event) -> Unit = {},
        reassert: suspend (String) -> Boolean,
    ): String? {
        var eligibilityLost = false
        val result = withTimeoutOrNull(recoveryWindow) {
            while (isStillEligible()) {
                awaitTarget(target.outputId, outputs, onEvent)
                if (!isStillEligible()) {
                    eligibilityLost = true
                    onEvent(Event.EligibilityLost)
                    return@withTimeoutOrNull null
                }

                // If Android still routes media exclusively to the previously heard headset, do not
                // disturb a healthy AudioTrack by reapplying its preferred device. Automatic selection is
                // acceptable here only with positive active-route evidence; absence/ambiguity takes the
                // existing bounded reassertion path below.
                val measuredTarget = alreadySecured(target.outputId, selectedId.value, measured)
                if (!isStillEligible()) {
                    eligibilityLost = true
                    onEvent(Event.EligibilityLost)
                    return@withTimeoutOrNull null
                }
                if (measuredTarget) {
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

                val ready = if (applied && selectedId.value == target.outputId) {
                    readiness(target.outputId, measured, canProbe)
                } else {
                    null
                }
                if (ready != null && isStillEligible()) {
                    onEvent(ready)
                    return@withTimeoutOrNull target.outputId
                }
                reportUnsecured(applied, target.outputId, outputs.value, selectedId.value, onEvent)
                // An inventory/route publication wakes recovery immediately. The retry bound is only
                // a fallback for platforms that do not publish a change; elapsed time is never proof.
                val snapshot = outputs.value to selectedId.value
                withTimeoutOrNull(RETRY_DELAY) {
                    combine(outputs, selectedId) { current, selected -> current to selected }.first { it != snapshot }
                }
            }
            eligibilityLost = true
            onEvent(Event.EligibilityLost)
            null
        }

        if (result == null && !eligibilityLost) onEvent(Event.TimedOut)
        return result
    }

    private suspend fun awaitTarget(id: String, outputs: StateFlow<List<AudioOutput>>, onEvent: (Event) -> Unit) {
        val absent = !outputs.value.containsTarget(id)
        if (absent) onEvent(Event.TargetAbsent)
        outputs.first { it.containsTarget(id) }
        if (absent) onEvent(Event.TargetReturned)
    }

    private fun alreadySecured(id: String, selected: String?, measured: (String) -> Boolean): Boolean =
        (selected == null || selected == id) && measured(id)

    private fun readiness(id: String, measured: (String) -> Boolean, canProbe: (String) -> Boolean): Event? = when {
        measured(id) -> Event.Secured
        canProbe(id) -> Event.ProbeReady
        else -> null
    }

    private fun reportUnsecured(
        applied: Boolean,
        id: String,
        outputs: List<AudioOutput>,
        selected: String?,
        onEvent: (Event) -> Unit,
    ) {
        if (applied && selected == id) {
            onEvent(if (outputs.any(AudioOutput::isActive)) Event.RouteCompeting else Event.RouteUnknown)
        }
        onEvent(Event.PreferenceLost)
    }

    private fun List<AudioOutput>.containsTarget(outputId: String): Boolean =
        any { output -> output.id == outputId && output.isHeadsetCandidate }

    private companion object {
        /** The measured A2DP disappearance/reappearance completed in about one second. */
        val DEFAULT_RECOVERY_WINDOW: Duration = 2.seconds
        val RETRY_DELAY: Duration = 50.milliseconds
    }
}
