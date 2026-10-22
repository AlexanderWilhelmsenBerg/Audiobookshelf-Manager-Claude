# Car continuity resume effects — Phase 3 (issue #289)

Draft, stacked branch based on #292 (which depends on #291 and #290). Do not merge
into `main` until the stacked prerequisites are accepted.

## Responsibility moved out of PlaybackService

`CarContinuityResumeEffects` now owns the **effect transaction** for a car recovery:
wait only for the previously captured exact headset, recheck book generation and
the latest explicit destination before final eligibility, consume the existing
`CarContinuityRecoveryCoordinator` claim at most once, require a muted headset
probe, notify the existing sleep timer of service-resume intent, and issue Play
on the service's single current ExoPlayer.

It is *not* a second recovery gate or a general event engine:

- `CarContinuityRecoveryCoordinator` owns the existing one-shot/eligibility state.
- `RouteHeardOwnership` owns book generation and evidence provenance.
- `AudioOutputRouter` owns endpoint identity, destination preferences, muted
  AudioTrack verification, and fail-closed behavior on unknown/wrong/multiple sinks.
- `PlaybackService` retains the only ExoPlayer and MediaLibrarySession, raw
  callbacks, player dispatcher, lifecycle authority and logging snapshot.
- `SleepTimerController` retains timer state; it is notified **only** when the
  accepted recovery path has passed the actual guarded probe check.

The executor holds no independent mutable state, launches no new coroutine and
does not change a user-requested destination or Media3 focus policy. Player
access occurs synchronously inside the existing service coroutine's Media3
dispatcher. Critically, the executor looks up the current player **again after**
the suspending exact-headset recovery instead of reusing a potentially old
ExoPlayer pointer.

## Verification

Phase 2 service and scenario contracts still exercise physical projection,
focus/noisy/controller permutations, stale book/profile generations, manual
Pause, exact headset one-shot claims, and missing AudioTrack evidence. The
obsolete `CarContinuityPlayPrecheckTest` source-substring assertion for a
service-private method has been retired: moving that method is now permitted
without changing behavior. Direct policy precheck cases and behavioral
coordinator/service replay tests remain present.

This refactor does **not** resolve #276/#277 virtual input/headset-button
attribution. Route preferences and predicted AudioManager output still cannot
prove the playing AudioTrack or audible sound. The separate progress HTTP 401
issue #288 remains outside this scope.
