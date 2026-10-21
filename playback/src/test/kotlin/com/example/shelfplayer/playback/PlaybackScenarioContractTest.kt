package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Refactor-safe event/observation contract. The runner calls production coordination policy;
 * it does not model a second player or duplicate the car-arrival recovery rules.
 *
 * This is policy evidence only, never proof of a physical AudioTrack destination.
 */
internal class PlaybackScenarioContractTest {
    private sealed interface Input {
        data class Interrupted(val focusLoss: Boolean = true) : Input
        data object CarArrived : Input
        data object CarDeparted : Input
        data object ExplicitSelection : Input
        data object NewBook : Input
        data object Cancelled : Input
        data object ClaimPlay : Input
    }

    private enum class Verdict { PASS, FAIL, INCONCLUSIVE, BLOCKED }
    private enum class Evidence { POLICY_DECISION, ACTUAL_AUDIO_TRACK, HUMAN_HEARD }
    private data class Step(
        val id: String,
        val at: Duration,
        val input: Input,
    )
    private data class Observation(
        val stepId: String,
        val status: CarArrivalResumeGate.Status?,
        val authorizedPlay: Boolean,
        val evidence: Evidence = Evidence.POLICY_DECISION,
    )
    private data class Expected(val stepId: String, val authorizedPlay: Boolean)
    private data class Outcome(val verdict: Verdict, val reason: String)

    private fun judge(
        observations: List<Observation>,
        expected: Expected,
        requiredEvidence: Evidence = Evidence.POLICY_DECISION,
    ): Outcome {
        val observed = observations.lastOrNull { it.stepId == expected.stepId }
            ?: return Outcome(Verdict.INCONCLUSIVE, "No observation for causal step")
        if (requiredEvidence != Evidence.POLICY_DECISION && observed.evidence == Evidence.POLICY_DECISION) {
            return Outcome(Verdict.BLOCKED, "Physical route evidence was not instrumented by this test")
        }
        return if (observed.authorizedPlay == expected.authorizedPlay) {
            Outcome(Verdict.PASS, "Observed production policy")
        } else {
            Outcome(Verdict.FAIL, "Production policy contradicts expected authorization")
        }
    }

    private fun replay(steps: List<Step>): List<Observation> {
        require(steps.zipWithNext().all { (a, b) -> a.at <= b.at }) { "Nonmonotonic scenario clock" }
        require(steps.map { it.id }.distinct().size == steps.size) { "Duplicate causal ID" }

        val headset = AudioOutput(
            id = "synthetic:headset-appearance-1",
            displayName = "Synthetic device",
            kind = DeviceKind.Bluetooth,
            role = AudioOutputRole.Ambiguous,
            isActive = true,
        )
        val owner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        val coordinator = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        var generation = owner.currentGeneration
        var selectionSequence = 0L
        var target: CarArrivalResumeGate.Target? = null

        fun inputs() = CarContinuityRecoveryCoordinator.Inputs(
            owner.heardRoute, headset.id, generation, selectionSequence, carConnected = false,
        )
        coordinator.observePlayingHeadset(inputs())
        return steps.map { step ->
            var status: CarArrivalResumeGate.Status? = null
            var authorized = false
            when (val event = step.input) {
                is Input.Interrupted -> {
                    status = coordinator.platformInterruption(step.at, inputs(), event.focusLoss).status
                }
                Input.CarArrived -> {
                    val decision = coordinator.carArrival(step.at, generation, selectionSequence)
                    status = decision.status
                    target = decision.target
                }
                Input.CarDeparted -> {
                    val decision = coordinator.carDeparture(step.at, generation, selectionSequence, true)
                    status = decision.status
                    target = decision.target
                }
                Input.ExplicitSelection -> selectionSequence++
                Input.NewBook -> {
                    generation = requireNotNull(generation) + 1
                    coordinator.cancelAll()
                }
                Input.Cancelled -> coordinator.cancelAll()
                Input.ClaimPlay -> {
                    authorized = target?.let {
                        coordinator.assess(
                            it, CarContinuityPlayPrecheck.Proceed, generation, selectionSequence, headset.id,
                        ).mayPlay
                    } ?: false
                }
            }
            Observation(step.id, status, authorized)
        }
    }

    @Test fun focusBeforeCarArrivalClaimsOnlyOnePlay() {
        val observations = replay(listOf(
            Step("focus", 10.seconds, Input.Interrupted()),
            Step("arrival", 11.seconds, Input.CarArrived),
            Step("claim", 12.seconds, Input.ClaimPlay),
            Step("duplicate", 13.seconds, Input.ClaimPlay),
        ))
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", true)).verdict)
        assertEquals(Verdict.PASS, judge(observations, Expected("duplicate", false)).verdict)
    }

    @Test fun explicitOutputIntentInvalidatesStaleRecovery() {
        val observations = replay(listOf(
            Step("focus", 10.seconds, Input.Interrupted()),
            Step("arrival", 11.seconds, Input.CarArrived),
            Step("selection", 12.seconds, Input.ExplicitSelection),
            Step("stale", 13.seconds, Input.ClaimPlay),
        ))
        assertEquals(Verdict.PASS, judge(observations, Expected("stale", false)).verdict)
    }

    @Test fun newBookCannotInheritOldRecovery() {
        val observations = replay(listOf(
            Step("focus", 10.seconds, Input.Interrupted()),
            Step("arrival", 11.seconds, Input.CarArrived),
            Step("new-book", 12.seconds, Input.NewBook),
            Step("stale", 13.seconds, Input.ClaimPlay),
        ))
        assertEquals(Verdict.PASS, judge(observations, Expected("stale", false)).verdict)
    }

    @Test fun policyAuthorizationIsNotPhysicalHeadsetVerification() {
        val observations = replay(listOf(
            Step("focus", 10.seconds, Input.Interrupted()),
            Step("arrival", 11.seconds, Input.CarArrived),
            Step("claim", 12.seconds, Input.ClaimPlay),
        ))
        assertEquals(Verdict.BLOCKED,
            judge(observations, Expected("claim", true), Evidence.ACTUAL_AUDIO_TRACK).verdict)
        assertEquals(Verdict.INCONCLUSIVE, judge(observations, Expected("missing", true)).verdict)
        assertEquals(Verdict.FAIL, judge(observations, Expected("claim", false)).verdict)
    }
}
