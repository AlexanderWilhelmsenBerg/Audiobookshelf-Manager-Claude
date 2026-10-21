# Playback scenario verification ledger (issue #289)

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
| S02 — focus loss then first bind | `PlaybackScenarioContractTest`, `PlaybackContinuityInvariantMatrixTest` (policy) | Verify original physical endpoint |
| S03 — first bind then focus loss | Same tests (policy) | Verify physical route, no duplicate Play |
| S04 — noisy/focus duplicate | `PlaybackScenarioContractTest`, `PlaybackContinuityInvariantMatrixTest` (policy) | Service event-order replay with both callbacks |
| S05 — paused car connection | `PlaybackScenarioContractTest`, `CarLifecycleContinuityIntegrationTest` (policy/service) | Real paused-on-connect acceptance |
| S06 — explicit Gearhead Play | `PlaybackScenarioEvidenceTest`, `CarArrivalRouteRecoveryTest` (policy) | Measured car output, service-controller propagation |
| S07 — virtual/system Play after car | `PlaybackScenarioEvidenceTest`, `MediaButtonInputEvidenceTest` (transport/unknown-origin policy) | **Open product failure:** no reliable physical headset attribution or automatic handoff; #276/#277 |
| S08 — manual headset selection while projected | `AudioOutputRouterThreadTest`, `PlaybackScenarioEvidenceTest` (route guard) | Physical muted probe exact-route result and recovery/rollback |
| S09 — Play after explicit headset | `AudioOutputRouterThreadTest`, `LiveOutputEndpointRegistryTest` (partial) | Physical continuity while endpoint is still valid |
| S10 — reconnect/endpoint churn | `PlaybackScenarioAsyncEffectsTest`, `LiveOutputEndpointRegistryTest` (policy) | Real alias churn with no stale endpoint reuse |
| S11 — same-named Bluetooth devices | `LiveOutputEndpointRegistryTest`, `MediaButtonInputEvidenceTest` (identity policy) | Two-device physical ambiguity and unknown media key source |
| S12 — newer output intent during queued reassert | `PlaybackScenarioContractTest`, `PlaybackScenarioAsyncEffectsTest`, `DeferredPlaybackWorkTest` (policy/async) | Full service effect ordering under connected car |
| S13 — book/profile replacement | `PlaybackScenarioContractTest`, `PlaybackScenarioAsyncEffectsTest`, `CarPostConnectRestorerTest` (policy/async) | Full session/credential/artwork isolation with in-flight effects |
| S14 — physical departure/controller order | `PlaybackScenarioContractTest`, `CarProjectionLifecycleTest` (policy/service) | OEM projection behavior with stale controller |
| S15 — deliberate Pause at departure | `PlaybackScenarioContractTest`, `CarArrivalResumeGateTest` (policy) | Service-level no-resume assertion |
| S16 — Media3 focus suppression | `CarContinuityPlayPrecheckTest`, `CarContinuityRecoveryCoordinatorTest` (policy partial) | Real Media3 focus state and no duplicate Play |
| S17 — cold key/autoplay policy | `ColdStartToggleGateTest`, `ColdPlaybackResumptionFreshnessTest` (policy) | Controller/notification/cold-service entry paths |
| S18 — muted route verification | `PlaybackScenarioEvidenceTest`, `AudioOutputRouterThreadTest`, `LiveOutputEndpointRegistryTest` (route guard/looper) | Real AudioTrack exact/unknown/wrong/multiple and restored volume |
| S19 — callback dispatcher | `AudioOutputRouterThreadTest` (Robolectric/player-thread) | Device callback/ExoPlayer thread acceptance |
| S20 — resume/progress/sleep/history | `ResumeFreshnessCoordinatorRaceTest`, `SessionSyncCoordinatorTest`, `SleepTimerControllerTest` (component partial) | End-to-end paused-to-Play freshness/profile/queue effect replay |
| S21 — progress 401/profile rotation | `SessionSyncCoordinatorTest` and network contract tests (component partial) | **Separate defect #288**: exact 401 failure and final-position recovery |
| S22 — missing evidence/diagnostics | `PlaybackScenarioContractTest`, `PlaybackScenarioEvidenceTest`, `AudioRouteLabEvidenceTest`, `AudioRouteTestCorrelatorTest` (policy/diagnostics partial) | Incomplete route observer/human mark must remain INCONCLUSIVE/BLOCKED; privacy and export integration |

## Refactor safety gates

1. Expand service-level adapter replay with actual `PlaybackService`/MediaSession callbacks where
   practical before moving command or lifecycle ownership. The Phase 1 policy-only runner does not
   demonstrate every caller reaches the policies it invokes.
2. Keep one ExoPlayer and MediaLibrarySession. Keep generation/intent invalidation at the **effect
   completion boundary**, not only when the coroutine starts.
3. Preserve production output safety: muted guard may become audible only for one positively
   measured playing AudioTrack on the exact live endpoint. Weak prediction remains distinct.
4. Retain source-substring checks such as `CarContinuityEventAdapterTest.serviceAdapterCallsCoordinatorWithoutSecondGateImplementation`
   until a service-level behavioral substitute proves equivalent caller reachability. Then retire
   those checks without reimplementing policy.
5. Do not turn the known headset-button destination failure into a green test by redefining its
   expected physical outcome. Keep #276/#277 separate from the structural refactor and #288
   separate from audio routing.
6. Physical acceptance is required before claiming a verified headset handoff even if all JVM,
   Robolectric and GitHub CI tests pass.
