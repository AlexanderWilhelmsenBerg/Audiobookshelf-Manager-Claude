package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Causal replay over the production car-continuity policy, not an alternate playback engine.
 *
 * All route metadata here is synthetic policy input, NOT actual playing AudioTrack evidence.
 * The separate physical acceptance contract remains unverified until instrumented on hardware.
 */
internal class PlaybackScenarioContractTest {
    private sealed interface Input {
        data class Interrupted(val focusLoss: Boolean = true) : Input

        data object CarArrived : Input

        data class CarDeparted(val physicalProjection: Boolean) : Input

        data object ExplicitSelection : Input

        data object NewBook : Input

        data object DeliberatePause : Input

        data object ClaimPlay : Input
    }

    private enum class Verdict {
        PASS,
        FAIL,
        INCONCLUSIVE,
        BLOCKED,
    }

    private enum class Evidence {
        POLICY_DECISION,
        ACTUAL_AUDIO_TRACK,
        HUMAN_HEARD,
    }

    private data class Step(
        val id: String,
        val at: Duration,
        val input: Input,
    )

    private data class Observation(
        val stepId: String,
        val status: CarArrivalResumeGate.Status?,
        val reason: CarArrivalResumeGate.Reason?,
        val authorizedPlay: Boolean?,
        val evidence: Evidence = Evidence.POLICY_DECISION,
    )

    private data class Expected(
        val stepId: String,
        val authorizedPlay: Boolean,
    )

    private data class Outcome(
        val verdict: Verdict,
        val reason: String,
    )

    private fun judge(
        observations: List<Observation>,
        expected: Expected,
        requiredEvidence: Evidence = Evidence.POLICY_DECISION,
    ): Outcome {
        val observed = observations.lastOrNull { it.stepId == expected.stepId }
            ?: return Outcome(Verdict.INCONCLUSIVE, "No observation for causal step")
        if (observed.authorizedPlay == null) {
            return Outcome(Verdict.INCONCLUSIVE, "Event did not attempt recovery Play")
        }
        if (requiredEvidence != Evidence.POLICY_DECISION && observed.evidence == Evidence.POLICY_DECISION) {
            return Outcome(Verdict.BLOCKED, "Physical output evidence is unavailable in JVM policy replay")
        }
        return if (observed.authorizedPlay == expected.authorizedPlay) {
            Outcome(Verdict.PASS, "Observed production policy")
        } else {
            Outcome(Verdict.FAIL, "Production policy contradicts expected authorization")
        }
    }

    private fun replay(
        steps: List<Step>,
        initiallyAudible: Boolean = true,
    ): List<Observation> {
        require(steps.zipWithNext().all { (a, b) -> a.at <= b.at }) {
            "Nonmonotonic scenario clock"
        }
        require(steps.map(Step::id).distinct().size == steps.size) {
            "Duplicate causal ID"
        }

        val headset = AudioOutput(
            id = "synthetic:headset-appearance-1",
            displayName = "Synthetic device",
            kind = DeviceKind.Bluetooth,
            role = AudioOutputRole.Ambiguous,
            isActive = true,
        )
        val owner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            if (initiallyAudible) onPlaybackObserved(listOf(headset))
        }
        val coordinator = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        var sequence = 0L
        var carConnected = false
        var target: CarArrivalResumeGate.Target? = null

        fun inputs() = CarContinuityRecoveryCoordinator.Inputs(
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(headset)),
            generation = owner.currentGeneration,
            selectionSequence = sequence,
            carConnected = carConnected,
        )

        if (initiallyAudible) coordinator.observePlayingHeadset(inputs())

        return steps.map { step ->
            var decision: CarArrivalResumeGate.Decision? = null
            var authorized: Boolean? = null
            when (val event = step.input) {
                is Input.Interrupted -> {
                    decision = coordinator.platformInterruption(step.at, inputs(), event.focusLoss)
                }

                Input.CarArrived -> {
                    decision = coordinator.carArrival(step.at, owner.currentGeneration, sequence)
                    carConnected = true
                }

                is Input.CarDeparted -> {
                    decision = coordinator.carDeparture(
                        at = step.at,
                        generation = owner.currentGeneration,
                        sequence = sequence,
                        physicalProjection = event.physicalProjection,
                    )
                    carConnected = false
                }

                Input.ExplicitSelection -> {
                    sequence++
                }

                Input.NewBook -> {
                    owner.onBookChanged(hasBook = true)
                    coordinator.cancelAll()
                }

                Input.DeliberatePause -> {
                    coordinator.cancelAll()
                }

                Input.ClaimPlay -> {
                    authorized = target?.let {
                        coordinator.assess(
                            target = it,
                            precheck = CarContinuityPlayPrecheck.Proceed,
                            currentGeneration = owner.currentGeneration,
                            selectionSequence = sequence,
                            securedHeadsetId = headset.id,
                        ).mayPlay
                    } ?: false
                }
            }
            decision?.target?.let { target = it }
            Observation(step.id, decision?.status, decision?.reason, authorized)
        }
    }

    @Test
    fun focusBeforeCarArrivalClaimsExactlyOnePlay() {
        val observations = replay(
            listOf(
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("arrival", 11.seconds, Input.CarArrived),
                Step("claim", 12.seconds, Input.ClaimPlay),
                Step("duplicate", 13.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", true)).verdict)
        assertEquals(Verdict.PASS, judge(observations, Expected("duplicate", false)).verdict)
    }

    @Test
    fun arrivalBeforeFocusClaimsExactlyOnePlay() {
        val observations = replay(
            listOf(
                Step("arrival", 10.seconds, Input.CarArrived),
                Step("focus", 11.seconds, Input.Interrupted()),
                Step("claim", 12.seconds, Input.ClaimPlay),
                Step("duplicate", 13.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(CarArrivalResumeGate.Status.Ready, observations[1].status)
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", true)).verdict)
        assertEquals(Verdict.PASS, judge(observations, Expected("duplicate", false)).verdict)
    }

    @Test
    fun noisyAndFocusDuplicateCannotIssueTwoPlays() {
        val observations = replay(
            listOf(
                Step("noisy", 10.seconds, Input.Interrupted(focusLoss = false)),
                Step("focus", 10.seconds + 500.milliseconds, Input.Interrupted()),
                Step("arrival", 11.seconds, Input.CarArrived),
                Step("claim", 12.seconds, Input.ClaimPlay),
                Step("duplicate", 13.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", true)).verdict)
        assertEquals(Verdict.PASS, judge(observations, Expected("duplicate", false)).verdict)
    }

    @Test
    fun pausedCarConnectionCannotCreateAutoplay() {
        val observations = replay(
            listOf(
                Step("arrival", 10.seconds, Input.CarArrived),
                Step("claim", 11.seconds, Input.ClaimPlay),
            ),
            initiallyAudible = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Rejected, observations[0].status)
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", false)).verdict)
    }

    @Test
    fun lateCarBoundaryCannotBorrowEarlierFocus() {
        val observations = replay(
            listOf(
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("arrival", 17.seconds, Input.CarArrived),
                Step("claim", 18.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(CarArrivalResumeGate.Reason.OutsidePairingWindow, observations[1].reason)
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", false)).verdict)
    }

    @Test
    fun explicitOutputSelectionInvalidatesStaleRecovery() {
        val observations = replay(
            listOf(
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("arrival", 11.seconds, Input.CarArrived),
                Step("selection", 12.seconds, Input.ExplicitSelection),
                Step("stale", 13.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(Verdict.PASS, judge(observations, Expected("stale", false)).verdict)
    }

    @Test
    fun newBookCannotInheritOldRecovery() {
        val observations = replay(
            listOf(
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("arrival", 11.seconds, Input.CarArrived),
                Step("new-book", 12.seconds, Input.NewBook),
                Step("stale", 13.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(Verdict.PASS, judge(observations, Expected("stale", false)).verdict)
    }

    @Test
    fun deliberatePauseCannotBeOverriddenByRecovery() {
        val observations = replay(
            listOf(
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("arrival", 11.seconds, Input.CarArrived),
                Step("pause", 12.seconds, Input.DeliberatePause),
                Step("claim", 13.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", false)).verdict)
    }

    @Test
    fun physicalDepartureBeforeFocusCanPairButWeakControllerCannot() {
        val physical = replay(
            listOf(
                Step("arrival", 1.seconds, Input.CarArrived),
                Step("departure", 10.seconds, Input.CarDeparted(physicalProjection = true)),
                Step("focus", 11.seconds, Input.Interrupted()),
                Step("claim", 12.seconds, Input.ClaimPlay),
            ),
        )
        val legacy = replay(
            listOf(
                Step("arrival", 1.seconds, Input.CarArrived),
                Step("departure", 10.seconds, Input.CarDeparted(physicalProjection = false)),
                Step("focus", 11.seconds, Input.Interrupted()),
                Step("claim", 12.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(Verdict.PASS, judge(physical, Expected("claim", true)).verdict)
        assertEquals(Verdict.PASS, judge(legacy, Expected("claim", false)).verdict)
    }

    @Test
    fun physicalDepartureAfterFocusCanPair() {
        val observations = replay(
            listOf(
                Step("arrival", 1.seconds, Input.CarArrived),
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("departure", 11.seconds, Input.CarDeparted(physicalProjection = true)),
                Step("claim", 12.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(CarArrivalResumeGate.Status.Ready, observations[2].status)
        assertEquals(Verdict.PASS, judge(observations, Expected("claim", true)).verdict)
    }

    @Test
    fun policyAuthorizationIsNotPhysicalHeadsetVerification() {
        val observations = replay(
            listOf(
                Step("focus", 10.seconds, Input.Interrupted()),
                Step("arrival", 11.seconds, Input.CarArrived),
                Step("claim", 12.seconds, Input.ClaimPlay),
            ),
        )
        assertEquals(
            Verdict.BLOCKED,
            judge(observations, Expected("claim", true), Evidence.ACTUAL_AUDIO_TRACK).verdict,
        )
        assertEquals(
            Verdict.BLOCKED,
            judge(observations, Expected("claim", true), Evidence.HUMAN_HEARD).verdict,
        )
        assertEquals(Verdict.INCONCLUSIVE, judge(observations, Expected("missing", true)).verdict)
        assertEquals(Verdict.INCONCLUSIVE, judge(observations, Expected("focus", true)).verdict)
        assertEquals(Verdict.FAIL, judge(observations, Expected("claim", false)).verdict)
    }

    @Test
    fun invalidCausalSequencesAreRejectedBeforePolicyExecution() {
        assertFailsWith<IllegalArgumentException> {
            replay(
                listOf(
                    Step("later", 12.seconds, Input.CarArrived),
                    Step("earlier", 11.seconds, Input.Interrupted()),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            replay(
                listOf(
                    Step("same", 10.seconds, Input.Interrupted()),
                    Step("same", 11.seconds, Input.CarArrived),
                ),
            )
        }
    }
}
