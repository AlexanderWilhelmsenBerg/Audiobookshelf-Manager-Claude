# Playback continuity event-sequence CI contract

This test-only increment reuses the real CarArrivalResumeGate, RouteHeardOwnership,
and CarArrivalRouteRecovery production classes. It does not copy policy into a
separate test state machine.

PlaybackContinuityInvariantMatrixTest covers focus-loss and noisy callbacks before
or after the first car arrival (four sequences), three intent invalidations for
each sequence (explicit Pause, new book/profile generation, changed output selection),
and positive competing speaker-route evidence.

Safety invariants: recovery secures only the originally heard headset, at most one
automatic Play is authorized, duplicates do not renew arrival, invalidated work
never changes route, and a preferred route without actual headset activity is
not proof that the headset is receiving audio.

The existing playback JVM test tier runs through verifyDebug in ordinary PR CI.
No production source, Gradle dependency, or GitHub workflow changes are needed.

LIMIT: tests inject ordered events into production policy objects, not Android
service callbacks. They do not prove physical AudioTrack routing, Android Auto OEM
behavior, or audible output. Service integration and car/headset acceptance remain
separate requirements before closing playback routing problems.

| Headset comparison | Expected observation |
| --- | --- |
| Verified headset, route-safety pause, player clock jumps | Pause-time progress and the same-headset probe retain the earlier book position. |
| Healthy headset, user seek, stale sample or profile change | Ordinary progress follows the player and the newer command wins. |
| Normal, capture blocked, short buffer, both | Run and drive exports identify the case. Compare actual PCM buffer duration, underruns and ordered reference audio on hardware. |
| Disconnect with 500 ms or 1,000 ms PCM | Both cases allow normal capture and disable automatic system take-back. Compare silence and position discontinuities at departure. |
| Connect with automatic take-back off or on | Both cases allow normal capture and use 500 ms PCM. Compare time to audible narration on the same headset. |

The paired comparisons and earlier cases are covered in the [headset test procedure](headset-route-test.md#compare-headset-output-settings).
