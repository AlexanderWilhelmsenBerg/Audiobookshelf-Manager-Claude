# Car lifecycle authority — Phase 3 (issue #289)

**Stacked on the Phase 2 verification branch:** `refactor/289-playback-scenario-contracts`
([draft PR #290](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/290)).
Do not interpret this document as merged-`main` architecture until its PR merges.

## Responsibility

`CarLifecycleAuthority` owns three formerly independent mutable
`PlaybackService` fields: the latest physical projection observation,
whether a **positive projection** owns the car interval, and whether a
MediaSession car playback context has actually been established. It references
the existing `CarConnections` controller count; it does not copy that count
or introduce another state machine for playback, focus, output routing, or
session persistence.

`PlaybackService` remains the **sole** ExoPlayer + MediaLibrarySession owner.
It interprets lifecycle edges from this authority and owns the decision to
call `CarContinuityRecoveryCoordinator`, log privacy-safe diagnostics and forward
effect decisions to `CarContinuityResumeEffects`. That component owns pending
route-hold/recovery work on the service scope; the service retains its player/session.
The authority
cannot send Play or change preferred audio outputs.

## Ordering contract

1. On first car-controller binding, snapshot the arrival timestamp before
   incrementing the existing controller count. Mark the car session established
   synchronously. Report the recovery gate's result before dispatching any
   asynchronous route effect. A second controller is not a second arrival.
2. On controller disconnection, update `CarConnections`. Only a **final**
   controller disappearance without positive projection may use the legacy
   departure fallback. The established flag is retired before issuing the
   departure effect. A still-active positive physical projection wins over
   old/stale controller lifetimes.
3. On projection observations, publish the current reported state for the
   existing diagnostic snapshot first, then synchronously reconcile the
   boundary. An initial positive observation marks projection ownership but
   does not invent an established MediaSession or a fresh arrival.
4. `Unknown` is **not** a departure, including after another controller
   disappears. `Unknown → Projection` while an existing positive latch
   survives is not another arrival. Only confirmed `NotConnected` after a
   positive latch retires stale controller bindings and ends the physical
   interval once.
5. The sleep timer sees **controller-connected OR positive-projection** status;
   unknown provider reads cannot switch off an active car interval.
6. `projectionOwnsCarLifecycle` is *physical-lifecycle precedence*, not proof
   of the current playing AudioTrack or that a key originated from a headset.
   `sessionEstablished` is MediaSession-context classification, not
   `CarConnections.isConnected()`.

## Test safety

`CarLifecycleAuthorityTest` characterizes positive/unknown/departure edges,
controller multiplicity, initial states and sleep-timer eligibility.
`CarProjectionLifecycleTest` still exercises the actual Robolectric service
and provider callback path; Phase 2 `PlaybackScenarioContractTest` and
`PlaybackScenarioAsyncEffectsTest` protect focus/recovery decisions and stale
asynchronous effects. A physical route PASS requires real playing AudioTrack
evidence and separate hardware acceptance. #276/#277 remain open.

This extraction is intended to **reduce** service-local mutable authorities;
it is not a new Android module, event bus, player, or alternative playback policy.
Subsequent Phase 3 slices can extract input interpretation and serialized effects
on the existing Media3 dispatcher after the scenario gates remain green.
