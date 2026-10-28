# Playback scenario verification ledger (issue #289)

## Build 2241 route-loss safety continuation (27 October 2026)

The owner reports headset hold and headset Pause/Play return failed on combined build 2241.
The retained capture shows a playing Automatic Bluetooth endpoint disappearing, fresh appearance IDs,
a playing speaker observation before noisy pause, rejected continuity and subsequent external Play
on `RemoteSubmix`. The full sanitized assessment and ordered implementation plan are in issue #289;
raw diagnostics are not repository fixtures. This adds a regression spanning S02/S04/S07/S10/S18,
rather than changing the existing physical outcomes to PASS.

The first implementation slice establishes transient protection from a current-generation, sole
measured AudioTrack endpoint before car bind. `PlaybackService` uses `RouteHeardOwnership` eligibility;
the router rechecks actual route, endpoint/collision state, attached player, preference epoch and newer
selection at the effect boundary. It does not classify a virtual media key as a physical headset.
The guard retains its existing API 33+ boundary and candidate eligibility.

| Regression | Production path / assertion |
| --- | --- |
| Automatic playback with no preinstalled guard | `PlaybackServiceCommandContinuityTest` invokes the actual service observer; removing its router call must fail the guard assertion |
| Endpoint loss and replacement appearance | `AudioOutputRouterThreadTest` drives the actual route callback; old target stays protected and a new appearance is not silently adopted |
| Multiple/current competing measured outputs | `HeadsetHoldTest` and router tests reject ambiguous proof; an inactive listed speaker alone is not conflicting AudioTrack evidence |
| Explicit Car before any prior headset observation | Router test rejects an Automatic continuity claim even when the actual headset route still lags the requested Car destination |
| Intent, book or collision changes during measurement | Router tests invalidate the old arm at the post-query boundary |
| Book end after policy reassertion and a muted probe | Cleanup retires only the transient effect claim, restores its volume and clears a policy-requested preference |
| New attachment or newer explicit same-output choice | Old implicit work cannot cross attachment or erase the newer listener choice |
| Route-safety pause vs later ordinary Pause | Real Media3 plus service `PlayerEvents` clears auto-rewind eligibility for the guard effect; the later listener pause still primes rewind, and genuine focus/noisy provenance is preserved |

The existing book generation, explicit selection sequence and queued-preference epoch scope these
effects; no new listener-intent counter is introduced. Profile handover already pauses/flushes and
clears the queue before changing account context, reaching the book/queue retirement boundary.

This slice is **not** #276's user-facing persistent lock or #277's controlled verified handoff.
A disappearing endpoint may require safe pause and a fresh explicit output choice. No automatic
association between old and new appearance IDs, continuous audio through a missing route, physical
button attribution, or real head-unit acceptance is certified by these tests.

Capability provenance and pending physical checks are in
[`../architecture/playback-output-capabilities.md`](../architecture/playback-output-capabilities.md).
The rest of this ledger remains the baseline scenario inventory.

The #300 command-boundary continuation adds actual-service regressions for fresh-start controller Play,
retained-intent controller Prepare and deferred accepted installation on a denied output. Continuity's
synchronous raw-Play callback can issue a newer forwarding Stop without resurrecting the old awaited target;
the test observes Stop's idle state and controller generation, because Media3 may retain PlayWhenReady.
Post-connect tests cover Arm with inherited Play intent and same-item installation without an assumed item
generation increment. These supplement S08/S12/S13/S15/S18/S19; their physical acceptance columns are unchanged.

The subsequent position candidate supplements S08/S12/S13/S15/S18/S20. `MutedPositionEffectsTest` controls
Media3 PlayerMessage delivery around the production position component to test compensation, fresh proof,
wrong/unknown/multiple sinks, repeated callbacks, timeout cleanup, newer Play and a genuine seek during a
suspended barrier. `PlaybackServiceCommandContinuityTest` uses a real ExoPlayer and the service's actual
seek callback to exclude compensation persistence while preserving nested and later user seeks. A newer seek
inside the compensation callback keeps its own position/write and refuses the superseded outer Pause. It also holds zero
and nonzero journal points, including a reentrant mute-listener read, and exercises real forwarded Pause/Stop.
`SessionSyncCoordinatorTest` queues an old pause behind a new session's mutex-held durable open; the old
command must not snapshot or upload the successor. `AutoRewindOwnershipTest` retains source profile and
event time through a replacement book before asynchronous history persistence. These remain software
assertions; the physical acceptance columns are unchanged.

Current-item activation regressions additionally observe the raw singular/plural queue overloads selected by
pinned Media3. Guarded Set/Replace/Remove of the current item and first-item insertion must reach that mutation
with PlayWhenReady cleared even without an active muted probe. Future-only edits and unguarded replacement
retain normal transport. A synchronous newer seek during the safety Pause refuses the older raw setter and
keeps the original item at the newer position. These supplement S12/S13/S15 without claiming physical output.

<!-- Updated against merged GitHub main acce406c8ffe and scenario PR #290; 2026-10-21. -->

This ledger connects the 22 product scenarios to **executable production-policy tests**, existing
service/Android tests and the still-required physical evidence. It is a verification inventory, not
a replacement playback model. The implementation plan and expected outcomes remain in
[issue #289](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289).

## What a passing test means

- **Policy**: JVM/coroutine tests executed the production coordinator, gate or safety decision and
  saw its specified authorization/cancellation. This does **not** prove audio was heard anywhere.
- **Service**: Robolectric/Media3 exercises production Android integration or threading, but not a
  real headset/head unit. Static source-substring tests alone do not count as service behavior.
- **Physical pending**: an actual *playing* AudioTrack and, where applicable, a human-heard marker
  are needed. `AudioOutput.isActive`, `AudioManager.getAudioDevicesForAttributes`, an output
  preference request, a connected endpoint and a virtual/system media key are weaker evidence.
- **Gap**: more deterministic test integration, a platform limitation or an unresolved product
  defect remains. Unknown or absent observer data yields INCONCLUSIVE or BLOCKED, **never PASS**.

The new `PlaybackScenarioContractTest` gives PASS/FAIL/INCONCLUSIVE/BLOCKED policy outcomes.
`PlaybackScenarioAsyncEffectsTest` uses the coroutine test scheduler and actual
`CarContinuityRecoveryCoordinator.secure` to exercise delayed effects. Route evidence checks use
production `RouteEvidence.confirms` and the guarded output policy, not inferred acoustic results.

## S01–S22 traceability

| Scenario | Automated source / status | Acceptance still required |
| --- | --- | --- |
| S01 — active headset, car connects without focus loss | `CarLifecycleContinuityIntegrationTest`, `AudioOutputRouterThreadTest` (service/policy partial) | Hardware preservation of the measured headset, with no needless reassert |
| S02 — focus loss then first bind | `PlaybackScenarioContractTest`, `PlaybackContinuityInvariantMatrixTest` (policy), `CarProjectionLifecycleTest` focus-first service callback (Robolectric) | Verify original physical endpoint |
| S03 — first bind then focus loss | Same tests (policy), `CarProjectionLifecycleTest` controller-first service callback (Robolectric) | Verify physical route, no duplicate Play |
| S04 — noisy/focus duplicate | `PlaybackScenarioContractTest`, `PlaybackContinuityInvariantMatrixTest` (policy), `CarProjectionLifecycleTest` duplicate controller boundary (service partial) | Service event-order replay with both callbacks |
| S05 — paused car connection | `PlaybackScenarioContractTest`, `CarLifecycleContinuityIntegrationTest` (policy/service) | Real paused-on-connect acceptance |
| S06 — explicit Gearhead Play | `PlaybackScenarioEvidenceTest`, `CarArrivalRouteRecoveryTest` (policy) | Measured car output, service-controller propagation |
| S07 — virtual/system Play after car | `PlaybackScenarioEvidenceTest`, `MediaButtonInputEvidenceTest` (transport/unknown-origin policy) | **Open product failure:** no reliable physical headset attribution or automatic handoff; #276/#277 |
| S08 — manual headset selection while projected | `AudioOutputRouterThreadTest`, `PlaybackScenarioEvidenceTest` (route guard) | Physical muted probe exact-route result and recovery/rollback |
| S09 — Play after explicit headset | `AudioOutputRouterThreadTest`, `LiveOutputEndpointRegistryTest` (partial) | Physical continuity while endpoint is still valid |
| S10 — reconnect/endpoint churn | `PlaybackScenarioAsyncEffectsTest`, `LiveOutputEndpointRegistryTest` (policy) | Real alias churn with no stale endpoint reuse |
| S11 — same-named Bluetooth devices | `LiveOutputEndpointRegistryTest`, `MediaButtonInputEvidenceTest` (identity policy) | Two-device physical ambiguity and unknown media key source |
| S12 — newer output intent during queued reassert | `PlaybackScenarioContractTest`, `PlaybackScenarioAsyncEffectsTest`, `DeferredPlaybackWorkTest` (policy/async) | Full service effect ordering under connected car |
| S13 — book/profile replacement | `PlaybackScenarioContractTest`, `PlaybackScenarioAsyncEffectsTest`, `DeferredPlaybackWorkTest` profile-generation suspensions, `SessionSyncCoordinatorTest` same-book cross-profile guard, `ExistingSessionAttachmentTest` actual MediaSession replacement (policy/Media3) | Full session/credential/artwork isolation with in-flight effects |
| S14 — physical departure/controller order | `PlaybackScenarioContractTest`, `CarProjectionLifecycleTest` including focus-then-physical-projection and unknown recovery (policy/service) | OEM projection behavior with stale controller |
| S15 — deliberate Pause at departure | `PlaybackScenarioContractTest`, `CarArrivalResumeGateTest` (policy) | Service-level no-resume assertion |
| S16 — Media3 focus suppression | `CarContinuityPlayPrecheckTest`, `CarContinuityRecoveryCoordinatorTest` (policy partial) | Real Media3 focus state and no duplicate Play |
| S17 — cold key/autoplay policy | `ColdStartToggleGateTest`, `PlaybackServiceMediaButtonReceiverTest`, `ColdPlaybackResumptionFreshnessTest` (policy/service seam) | Controller/notification/cold-service entry paths; physical headset attribution |
| S18 — muted route verification | `PlaybackScenarioEvidenceTest`, `AudioOutputRouterThreadTest`, `LiveOutputEndpointRegistryTest` (route guard/looper) | Real AudioTrack exact/unknown/wrong/multiple and restored volume |
| S19 — callback dispatcher | `AudioOutputRouterThreadTest` including repeated background resettle callbacks and Media3 application-thread assertions (Robolectric/player-thread) | Device callback/ExoPlayer thread acceptance |
| S20 — resume/progress/sleep/history | `ResumeFreshnessCoordinatorRaceTest`, `SessionSyncCoordinatorTest` stale-snapshot sync/close rejection, `SleepTimerControllerTest` car pause/countdown/history, `CarProjectionLifecycleTest` service car-state forwarding to sleep timer, `ExistingSessionAttachmentTest` Media3 sleep title/profile replacement (component/service/Media3) | End-to-end paused-to-Play freshness/profile/queue effect replay |
| S21 — progress 401/profile rotation | `SessionSyncCoordinatorTest` and network contract tests (component partial) | **Separate defect #288**: exact 401 failure and final-position recovery |
| S22 — missing evidence/diagnostics | `PlaybackScenarioContractTest`, `PlaybackScenarioEvidenceTest`, `AudioRouteLabEvidenceTest`, `AudioRouteTestCorrelatorTest` (policy/diagnostics partial) | Incomplete route observer/human mark must remain INCONCLUSIVE/BLOCKED; privacy and export integration |

## Service-adapter checkpoint (2026-10-22)

`CarProjectionLifecycleTest` now drives actual `PlaybackService` platform-focus and
car-controller/projection adapters through an existing Robolectric service fixture. Tests cover
focus-before-car, car-before-focus, focus-before-positive-projection, duplicate boundaries,
and deliberately cancelled continuity. Their assertions read **runtime decisions**.
The previous `CarContinuityEventAdapterTest.serviceAdapterCallsCoordinatorWithoutSecondGateImplementation`
source-substring assertion was retired after these adapter tests passed Standard CI:
renaming or extracting callbacks should no longer fail merely because service source text changed.

This is **not** a full MediaSession playback pipeline: the fixture does not initialize a
real ExoPlayer, receive OEM audio focus, or prove AudioTrack output. The listener callback is
invoked through the existing test fixture's reflective seam. The Phase 3 completion adds
`createSessionPlayer`, the actual service forwarding-player factory, and drives its
Seek/Stop/media replacement paths with a real ExoPlayer in
`PlaybackServiceCommandContinuityTest`. This supplements the lifecycle fixture without
claiming a complete Hilt-created service. The stronger
`AudioOutputRouterThreadTest` source-backed fail-closed guard is intentionally retained
until equivalent runtime evidence tests cover an adversarial framework-policy fallback.

## Final Phase 2 integration boundary (2026-10-22)

The additional Android/Robolectric regression suites now exercise production event paths for:
Media3 player-thread confinement during repeated background output callbacks; profile and
command generations changing while durable work or authorization suspends; the same-book,
different-profile progress/sync/close guard; a real Media3 controller observing a profile
replacement without inheriting the old sleep countdown; and car controller/projection
callbacks reaching the production sleep-timer car-state owner.

These tests preserve the established division between **transport**, **playback state**,
**progress/session ownership** and **actual playing AudioTrack**. The ProfileId check in
`SessionSyncCoordinator` rejects explicit foreign-owner snapshots but preserves ownerless
legacy metadata fallback. No physical headset route is simulated or certified.

Remaining system-level acceptance involves genuine OEM media-button attribution, actual
AudioTrack output, hardware focus/projection behavior and complete Hilt service lifecycle on
a device. Those cannot honestly be declared passing based on Robolectric alone. The
headset-button handoff remains open under #276/#277; 401 sync transport is #288.

## Refactor safety gates

The 24 October Phase 3 continuation adds direct production-effect coverage in
`CarContinuityResumeTransactionTest` for accepted one Play/timer intent, suspended
player replacement, episode supersession, duplicate callbacks, cancellation and
service-scope disposal, synchronous self-Play callbacks and cancellation during
listener-intent synchronization. These strengthen S02–S04, S12–S16, S18–S20 and S22.
`CarContinuityRecoveryCoordinatorTest` rejects old completions and same-value
episode reuse without consuming a successor. `AudioTrackRouteQueryTest` calls the
actual monitor and rejects replacement/release/new observer epoch during a query,
including identical endpoints, and state/play-state/route exceptions. Unknown,
wrong and multiple measured sinks still pause while silent before restoring volume.
Tests use platform shadows and production policy; they do not certify physical sound.

`AudioRouteLabRecorderTest` retains real AtomicFile transactions and all persistence/privacy
assertions. Its class-scoped shadow adapts only the rename primitive to Android's atomic
overwrite semantics: host Java `File.renameTo` on Windows otherwise leaves the first
snapshot in place. An added assertion requires all 28 accepted commands in the committed
file before restoration; no recorder test is skipped or relaxed.

## Phase 3 completion coverage

`PlaybackServiceCommandContinuityTest` drives the service's actual forwarding-player
wiring and notification skip while production continuity recovery is suspended. A
positive control reaches one muted raw Play; Seek, Stop, media replacement and skip
revoke that transaction even when the already-paused player emits no raw Pause
transition. Notification skip also revokes the existing deferred-media claim.

`ControllerQueueResolutionTest` covers authoritative start preservation, newer
commands during preparation and durable acceptance, profile A/B/A, player replacement,
teardown, foreign-owner sessions, locking and stale failed requests. The helper uses
the existing guarded `BookChanges` seam; existing durable-session tests remain required.
`SessionCallbackFuturesTest` checks completion on the application dispatcher, both
cancellation directions and privacy-safe failure mapping. `CarProfileSwitcherTest` and
`PlaybackResumptionCallbacksTest` protect serialized/no-op profile switching, refusal,
metadata-only no-open behavior and passive cold-play intent after successful acceptance.
`ControllerQueueMedia3ReplayTest` connects real MediaControllers to a MediaLibrarySession:
a newer controller Stop cancels a suspended production queue-resolution future without
clearing the existing item, while ordinary Set-then-Play still completes. Its callback
composition uses the production request/future owners; it is not a full Hilt service test.
Its cold-resumption cases also exercise Media3's actual failure fallback: a cancelled
old request cannot prepare/Play newer media, valid cold completion still installs and
plays once as passive intent, and a later explicit Play remains allowed. Future tests
also cover already-completed results and queued listener execution.

`AudioOutputActivityEvidenceTest` exercises the production router with conflicting
measured, predicted and selected outputs. The source of presentation activity is
explicit, including unknown/legacy evidence. `AudioOutputRouterQueuedSafetyTest`
checks that released guards and superseded effect generations cannot pause a newer
choice, while a current ambiguous/colliding endpoint still fails closed. Existing
actual-track-only safety assertions remain intact.

See [the Phase 3 completion checklist](../architecture/playback-phase3-completion.md)
and ADR-0031. These software tests strengthen S01, S04, S08, S12–S20 and S22; the physical
acceptance column above remains authoritative. #276/#277 and #288 are not certified by
these tests.

1. Expand service-level adapter replay with actual `PlaybackService`/MediaSession callbacks where
   practical before moving command or lifecycle ownership. The Phase 1 policy-only runner does not
   demonstrate every caller reaches the policies it invokes.
2. Keep one ExoPlayer and MediaLibrarySession. Keep generation/intent invalidation at the **effect
   completion boundary**, not only when the coroutine starts.
3. Preserve production output safety: muted guard may become audible only for one positively
   measured playing AudioTrack on the exact live endpoint. Weak prediction remains distinct.
4. Use service-adapter behavioral tests rather than the retired continuity-source substring check.
   Keep any other source-sensitive routing guard only until its replacement proves the same
   safety invariant with runtime evidence.
5. Do not turn the known headset-button destination failure into a green test by redefining its
   expected physical outcome. Keep #276/#277 separate from the structural refactor and #288
   separate from audio routing.
6. Physical acceptance is required before claiming a verified headset handoff even if all JVM,
   Robolectric and GitHub CI tests pass.

## Guard lifetime follow-up — 27 October 2026

The production router now creates one `HeadsetRouteGuard` for transient safety, attached-player
callbacks, muted proof and volume cleanup. `AudioOutputRouterQueuedSafetyTest` covers retired
listeners after replacement and same-player reattachment, old probe completion after Car intent,
and restoration to the original volume owner. `CarContinuityResumeTransactionTest` additionally
checks that a same-target policy reassertion stays fail-closed on an unknown track and that a
Car choice during retirement measurement prevents an old pause. The latter enters through the
router's actual track-change callback with a one-shot measurement hook.

These checks extend S01/S04/S08/S12–S20 software evidence. The physical/inconclusive columns
above remain authoritative; no hardware identity, headset handoff or human-heard continuity
claim follows from these tests. The #295 cold receiver fix and separate #296/#288 progress auth
recovery also require their documented stationary device/server retests.

## #276 verified output lock candidate

Strict local `verifyDebug` with warnings as errors passes 2,639 tests / 314 suites, including 823 playback
tests, with no failures/errors/skips. Formatter, lint, Detekt and coverage gates pass. The first complete
rebuild reran every task; affected targets were rerun after corrections. Local APK assembly was excluded.
The exact committed source and normal PR-triggered CI result are recorded in issue #289 and the draft PR.

Removing the production service's generic-Car lock guard makes its Media3 request-context regression
fail; removing the Stop hook makes the real forwarding-Stop regression fail even while the loaded queue
is retained. Both mutations were restored before the passing gate. The route-eligibility mutation also
fails its intended assertion. Real Compose cases cover advisory refusal and release with no outputs.

A further production-effects regression exposed the former headset being re-protected during a car
rebind after explicit Car selection. Both the actual continuity-effect and evidence-owner cases failed
before the fix. Explicit Car now excludes headset recovery eligibility while keeping actual route
observations truthful; a newer explicit output choice restores eligibility. The full gate above includes
these cases. The older 2,637-test CI result does not verify this follow-up commit.

`AudioOutputRouter` owns one singleton `OutputIntentOwner` shared with the service's
`RouteHeardOwnership` evidence tracker. The phone output chooser exposes **Keep audio here** and
**Release** through read-only lock status. The lock requires a loaded, actively playing book and two
matching fresh reads of one exact live AudioTrack route; it accepts an ambiguous A2DP endpoint only
as the user-confirmed current destination and makes no wearable or button-origin claim.

Focused tests cover a measured lock, unknown/multiple/colliding route denial, generic controller Car
selection refusal while locked, stale status callbacks after a newer lock/release, and Automatic
release surviving unknown route evidence. A waiting lock retains the logical target across pause and
nonempty book/player changes, while physical proof is invalidated. Empty queue, explicit Automatic/
release, profile handover through its queue boundary, and service death clear the runtime claim.
Reconnection never selects a new endpoint or calls Play.

This is JVM/Robolectric production-path evidence only. Physical route stability, Android Auto host
behavior, and audible outcomes remain pending under the separate device acceptance guide; this slice
does not implement #277's explicit verified destination transfer/rollback. Physical headset-key attribution
remains unsupported when Android supplies only a virtual key. Use the
[device acceptance guide](output-ownership-device-acceptance.md) for audible routing and lifecycle results.
