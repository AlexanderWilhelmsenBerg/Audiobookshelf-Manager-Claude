# Playback transport input interpretation — Phase 3

This document describes the unmerged, stacked work in draft PR #291
(based on Phase 2's draft PR #290).

## Raw input is not destination intent

The `PlaybackTransportInput` pure, stateless interpreter maps the raw Media3
`playWhenReady` flag and `PLAY_WHEN_READY_CHANGE_REASON_*` value into:
- whether the player was asked to Play rather than Pause;
- whether a Pause has a reported user/request origin relevant to auto-rewind;
- whether the interruption represents system focus loss or becoming-noisy;
- the same privacy-safe reason label used in BookWave's diagnostics.

There is no new playback state machine, duplicate command generation, MediaSession
controller owner, route owner, or additional coroutine effect queue. A result is
a **classification**, not proof of a headset, steering wheel, car audio output,
or that any AudioTrack actually routed to a given sink. Media3 local-player
`USER_REQUEST` intentionally does not distinguish those physical sources.

`PlaybackService.PlayerEvents` remains the adapter for transport
consequences: history, rewind intent, cancellation of stale recovery work, and passing
focus/noisy interruptions to the existing recovery coordinator. Ready decisions enter
`CarContinuityResumeEffects`, which owns the service-scoped Job and final Play against
the service's current player; lifecycle and eligibility policy do not move into that Job.
The extraction does not change accepted policy or infer output identity.

`PlaybackTransportInputTest` covers Play for all reasons, system focus/noisy
pause, user/remote pause, end-of-item, unknown reasons, stable diagnostic labels,
and the lack of fabricated platform interruption events.

## System boundaries

`CarLifecycleAuthority` (same Phase 3 stack) owns physical projection and
controller lifecycle reconciliation, while `AndroidAutoTraceRecorder` owns
diagnostic correlation and bounded privacy-safe projections. The service owns
the single ExoPlayer, MediaLibrarySession and effect initiation. The existing
`CarContinuityRecoveryCoordinator` and `AudioOutputRouter` retain their
recovery claim and actual-output verification responsibilities.

The shared effect lifetime now cancels/supersedes overlapping suspended work using
the existing Media3 dispatcher and gate-issued episode identity. It introduces no
parallel playback state machine. Headset-button handoff #276/#277 remains open.
