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
