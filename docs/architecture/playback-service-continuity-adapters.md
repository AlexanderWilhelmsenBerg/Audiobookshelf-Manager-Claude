# PlaybackService continuity event boundaries

The A/B/C foundation merged into `main` on 21 October 2026 after explicit owner
authorization to proceed with architecture-first refactoring. Hardware headset-button
handoff remains open under #276/#277. The older requirement to hold those three
foundation PRs unmerged was superseded; the hardware observations below remain valid.

PlaybackService continues to own the single ExoPlayer and MediaLibrarySession,
lifecycle/source callbacks, focused UI/notification exposure, media-button
commands, and actual Play invocation. It now supplies an immutable snapshot of
service-owned route, book generation, explicit output selection, and car-session
status to CarContinuityRecoveryCoordinator.

The coordinator forwards the platform focus/noisy event and car/projection
boundary to the *existing* CarArrivalResumeGate. It does not add a second gate
or another generation counter. Actual route securing and one-shot consumption
are still consolidated as in PR B. The AudioOutputRouter still owns routing,
including muted AudioTrack destination verification; this change does not
attribute hardware media buttons or treat preferred output as actual route.

The tests cover both callback orders, both platform interruption reasons,
duplicate Play rejection, stale explicit intent, deliberate Pause, physical
projection departure versus weaker legacy-controller departure, and the
service's actual delegated entrypoint references. Existing Robolectric
CarProjectionLifecycleTest and all Phase 1C continuity and service tests
remain required by verifyDebug.

CI cannot establish audible car/headset routing. Any APK requires separate owner
authorization. Hardware acceptance must
exercise the original-headset-to-car transition, headset Play versus car Play,
same-name Bluetooth endpoints, cold start, disconnect/reconnect, focus/noisy
ordering, and explicit Pause/new book/profile/output changes.

## Unmerged Phase 3 continuation

The service retains synchronous lifecycle/input reconciliation, the single player
and session, progress/profile/sleep ownership and read-only diagnostic snapshots.
`CarContinuityResumeEffects` owns route-hold/recovery coroutine lifetime on the
service dispatcher. The three former independent launch sites now forward their
decisions to that same owner. Actual listener/book/queue/service invalidation
cancels pending work; stale completion discards only its own gate-issued episode.
See [the effect boundary](playback-recovery-play-effects.md) and
[ADR-0030](../adr/0030-continuity-effects-have-one-service-scoped-lifetime.md).

Phase 3 completion also separates browse, profile switching, cold resumption and
asynchronous queue acceptance from the service's connection/event adapters. New
controller movement and notification skips use the shared invalidation boundary;
slow queue callbacks cannot publish a stale live book after newer intent. Typed
activity provenance retains the distinction between track proof, policy and
preference. See [the completion checklist](playback-phase3-completion.md) and
[ADR-0031](../adr/0031-controller-requests-have-scoped-acceptance.md).

## 2026-10-21 extended hardware regression and safety stabilization

The initial physical PASS of build 2237 was superseded by a second Android Auto
session: external Play after paused car reconnect played to a measured RemoteSubmix,
not the previously heard A2DP endpoint. Virtual media-button metadata cannot prove
which physical button issued Play. The car-Play path explicitly selects Car, whereas
unattributed external Play does not select a headset. That asymmetry also exists on
main and is not established as an A/B/C refactoring regression. After an explicit
Car choice, profile/book replacement, or lost endpoint identity, no old headset
claim may be recreated merely from a connected Bluetooth device.

This C stabilization tightens the **existing guarded-headset safety path**: when
the actual playing AudioTrack is unmeasured, a predicted AudioManager media route
must not authorize audible playback. The router now passes only measured track
route IDs to its guard and pauses rather than trusting framework prediction.
It does not infer media-button origin, silently lock a headset, or implement a
new output handoff. Headset lock is #276; verified destination handoff is #277.

The Robolectric tests exercise repeated measured headset -> RemoteSubmix ->
unmeasured route changes, explicit Car supersession, and the production wiring
constraint that media-policy prediction cannot authenticate the playing track.
**Hardware acceptance remains OPEN**: verify the safety pause and test an explicit
headset output selection on repeated car reconnects, including new profile/book
and cold start. Do not interpret a safety pause as successful headset routing.

### Async output command ordering

The router previously queued an `apply(null)` for Car/Automatic without rechecking
selection on the ExoPlayer dispatcher. A subsequent exact-headset reassertion or
explicit output choice could therefore be overwritten by an older queued request.
`apply` now drops requests whose target no longer equals the currently selected
endpoint. This fixes a concrete stale-command race at the production Media3 routing
boundary. It is a plausible additional cause of intermittent switching, but the
existing hardware trace does not prove that the stale command executed. A
Robolectric test uses a delayed Automatic request and newer exact guarded
selection to prove the old preference call is not sent.
