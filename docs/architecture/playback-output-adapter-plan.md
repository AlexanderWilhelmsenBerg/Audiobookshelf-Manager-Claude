# Playback output adapters and continuity plan

**Status:** Owner approved; implementation and verification in progress. See the
[implementation and acceptance record](../testing/output-adapter-continuity.md).
**Date:** 30 October 2026.
**Primary implementation lane:** Playback & Lifecycle. Android System & Auto reviews platform seams;
Architecture & Integration and Test & Acceptance review boundaries and acceptance, sequentially.
**Requirements:** PLAY-002/004/008/009, ROUTE-001/002; preserve ADR-0034/0035/0036 and S01–S22.

## Objective and source baseline

Preserve a healthy headset without changing its route. When a transfer or recovery is necessary,
establish the intended destination before applying effects and confirm current actual output before
restoring audible volume. Reduce avoidable app waiting, with no interruption as the preferred result
and less than one second of audible interruption as the owner's device acceptance target.

GitHub was refreshed for this plan: main `acce406c8ffef058da5fe0a5db1cc2960cb56614`, feature PR300
`f3f3b5675c21ebffcab00bbca181c2c09f6c7a1f`, combined QA PR301
`b65f01acbf95eed4b87b30c6ad788093cf2c251e`. Both PRs are open drafts; current verification checks
report success. Both have no review threads or submitted reviews. Issues 276, 277 and 289 remain open.
This does not accept the hardware behavior of APK2245, for which no new device result was supplied.

Repowise's exact previous feature increment e2d35c37..f3f3b567 reports one new medium advisory:
`MutedPositionEffects.prepareProof`, cyclomatic complexity 9. Changed-code structural health is
9.01→8.94, not a whole-repository health score. Its test-reachability graph is incomplete.
jCodeMunch symbol search and ranked context confirm the baseline service→recovery coordinator→route
recovery organization. Its index is main acce406c, not the feature head; current implementations and
callers were inspected directly in the matching local checkout. Neither tool proves runtime reachability.

Current physical file lengths, including comments and blanks: PlaybackService 3410, AudioOutputRouter
1012, HeadsetRouteGuard 506, OutputHandoffEffects 447, MutedPositionEffects 407,
CarContinuityResumeEffects 310, OutputIntentOwner 238, LiveOutputEndpointRegistry 78,
OutputTargetProof 58. These are baseline measurements, not quality scores or prescribed target sizes.

## Contract: intended, observed and audible are different

- A selected destination states intent. Preferred-device acceptance and framework route prediction
  cannot authorize audible release.
- Healthy preservation requires a fresh, unique actual-track observation, current attachment/observer
  epoch, live collision-free endpoint, authorized generation/selection, and no conflicting newer intent.
  Reuse existing scope and proof rules; do not use an unlabeled `isActive` boolean as sufficient evidence.
- A healthy same-target observation takes the preservation path: no preference request, pause, seek,
  mute acquisition, synthetic Play or reset of the ownership sequence. Repeated Auto binds do nothing
  beyond observation/presentation. Existing focus/noisy safety remains authoritative.
- Android can measure a new track's actual route only while it plays. Therefore the contract is proof
  before **audible release**, not a promise to predict the future route before creating playback.
  Eligible recovery uses the existing muted probe and held-position protocol on the existing player.
- Recheck current ownership and target proof at commit and unmute, including reentrant observers.
  Unknown, mismatch, collision, multiple sinks, timeout or supersession cannot release audible audio.
- Auto RemoteSubmix plus positive projection context means delegated to Auto, not identified physical
  car speakers. Preserve this distinction in UI, diagnostics and tests.
- A disconnected endpoint appearance retires its token. Names, list order, a single Bluetooth candidate
  and a generic virtual key do not prove that a replacement is the same device.

## Responsibility map

| Component | Owns | Must not own |
| --- | --- | --- |
| Existing OutputIntentOwner | Selection, sequence, lock and transfer state | Android inventory, callbacks or player lifecycle |
| Existing LiveOutputEndpointRegistry | Identity of current local endpoint appearances | Reconnect guesses, playback policy or Cast sessions |
| AndroidLocalOutputAdapter | AudioManager callback lifetime, inventory/device-handle mapping, preferred-device application and explicitly labeled framework prediction | Destination selection, recovery retries, progress or focus |
| Existing AudioTrackRouteMonitor | Current track observation and observer epoch | Intent or transfer decisions |
| Existing Auto projection/lifecycle components | Projection availability and controller/lifecycle events | Another player, selection owner or physical speaker identity |
| Existing OutputTargetProof | Pure target-specific evidence evaluation | Waiting, mutation, retry or unmute |
| Existing router, handoff and recovery components | Coordinate those capabilities using existing owner/probe claims | Copies of selection/connection state or new competing retry loops |
| Existing media-input boundary | Normalize commands and expose known/unknown origin evidence | Converting virtual input into assumed headset identity |

Keep adapters in :playback. Introduce only narrow ports needed by actual callers and production-path
tests. Do not create a universal device-manager interface with unsupported methods. Adapt the existing
AndroidAutoProjectionMonitor/lifecycle seam where needed; do not add an empty Auto wrapper for symmetry.
Keep Android objects inside the platform adapter; immutable snapshots carry endpoint tokens and evidence.
Move platform handle storage out of the router rather than retaining two maps with separate lifetimes.
The registry remains the single appearance authority. Observations are snapshots, not new state owners.

Chromecast is a later remote-player backend, not a local AudioDeviceInfo endpoint. Aircast/AirPlay needs
its protocol/product scope defined separately. Reserve neither implementations nor speculative plugin
registries now. Future backends must integrate with shared queue/progress/session ownership explicitly.

## Sequential implementation slices

### 1. Freeze contracts and reproduce the latency paths

Add focused tests through existing production service/router/recovery seams before altering behavior.
Record preference calls, mute claims, Play, seeks, route observations and checkpoint effects in the
existing fixtures. Establish healthy preservation and event ordering, including repeated Auto callbacks.
Separate actual observation from predicted/preferred-only evidence in the test data.

Characterize the existing 400ms settle, 50ms retry, two-second recovery-before-probe path and early
completion behavior. The six-second pairing window is eligibility, not a sleep. Keep these baseline
tests descriptive; add changed-behavior regression tests when slice 3 changes the contract.

Exit: callers and alternate entry paths are mapped; baseline behavior and intended differences are
explicit. No new runtime state machine, observer, production timer or test-only model of playback.

### 2. Extract platform access without changing routing policy

Move Android inventory registration/reconciliation, handle lookup, device preference effects and
framework prediction from AudioOutputRouter into AndroidLocalOutputAdapter. Reuse the registry and
actual-track monitor; retain the one application dispatcher and deterministic attach/detach lifetime.
Keep disconnection decisions, selection and proof in their current owners. Use existing source ports
where practical; replace broad router dependencies only where the extraction removes real coupling.

Exit: existing scenarios remain unchanged, adapter callback teardown and late callback rejection pass,
and current callers reach the extracted implementation. Remove replaced router code in the same slice.
No duplicated inventory, second preference epoch, new singleton observer or new Gradle module.

### 3. Preserve first and recover from events

Use the same actual-evidence contract for healthy preservation across Auto bind, inventory publication
and recovery entry. A still-healthy target returns immediately without preferred-device reapplication.

For eligible interrupted playback, distinguish absent endpoint from available-but-unmeasurable paused
track. An absent original appearance waits for valid evidence within the existing bounded policy; it
cannot attach to a guessed replacement. An available exact target may enter the existing muted proof
path promptly instead of necessarily exhausting two seconds waiting for paused-track evidence.

Replace mandatory settle waiting on the success path with current observation events where sufficient.
A deadline remains a failure bound; elapsed time never becomes route proof. A delayed diagnostic refresh
may remain off the critical path if it is needed to observe platform changes without callbacks.
Subscribe before requesting effects and re-read afterward to avoid missing synchronous notifications.

Reuse the existing one-shot recovery claim, preference epoch, probe and final Play gate. Cancel old waits
on newer intent, Pause/Stop, item/profile change, track replacement and teardown. Do not race independent
recovery and transfer jobs or launch a second probe. Preserve Media3 focus ownership, two compensation
stages and the final 0–50ms position bound. Do not change those protections to satisfy the latency target.

Exit: new regression tests fail with the behavior change reverted and pass with it restored. Event-ready
success does not consume a fixed 400ms/two-second delay; failure remains bounded and silent. Hardware
latency is still unaccepted until slice 4. Preserve all existing route policies and manual/paused semantics.

### 4. Verify integration, remove obsolete paths and test one APK

Audit phone, Auto, generic keys, notification, cold resume and foreground/background paths for the same
owner and proof boundary. Remove redundant helpers/timers after caller inspection, not by dead-code
score alone. Update architecture, ADR follow-up and acceptance docs with actual implemented behavior.
Use the feature branch for playback and the existing combined QA branch for device delivery; keep auth
changes separate and do not duplicate earlier stacked extraction work.

Run formatter, focused production-path tests, full strict verifyDebug and normal exact-head PR CI.
Follow the repository rerun requirement if the classpath changes. Build the authorized combined signed
APK only after its exact CI passes. No merge or manual Standard CI rerun/dispatch.

Test the same headset/car on repeated arrival and Car→Headset→Car sequences, paused connection/departure,
focus interruptions, endpoint loss/replacement, two outputs, service recreation and diagnostics off/on.
Measure last audible headset sample to resumed audible headset sample; app timestamp intervals alone
do not measure human-heard silence. Report individual durations and worst observed interruption, separate
from app readiness-to-unmute time. Record platform-unavailable intervals without hiding them from the
end-to-end acceptance result. A gap of one second or more fails the owner's stated latency target for
that scenario even if software tests pass. Unknown physical key origin remains explicitly unsupported.

## Required regression coverage

- Healthy repeated arrival: zero route changes, seeks, extra Play or mute; one player/session.
- Preferred-only, predicted-only, stale observer, multiple/colliding or wrong output: no false preservation
  or unmute. Fresh evidence before/after subscription and same-stack callbacks cannot be missed.
- One accepted recovery Play; no extra Play after success, timeout, callback repetition or supersession.
- Pause, newer selection, seek, book/profile change and detach during suspended recovery/proof: cancellation
  cannot mutate or unmute a successor. Existing pause journaling still completes for its captured context.
- Original endpoint disappears/reappears with a new token: no identity inheritance by name or sole candidate.
- Car projection Unknown/departed and RemoteSubmix without projection: no false Car confirmation.
- Buffering, initial/final correction, +51ms/backward movement, wrong output and cancellation retain the
  existing held-position/rollback tests; progress, history, sleep, speed and profile remain consistent.
- Input ambiguity remains ambiguous; explicit Headset/Car actions retain deterministic destination intent.

## Complexity and review gates

Compare each slice against its immediate parent and the recorded baseline. Track production NLOC,
method complexity, public surface, dependencies and retained mutable state, with tests reported separately.
Use exact-revision Repowise findings plus jCodeMunch navigation only where indexed; validate current source.
Do not claim a whole-system improvement from a changed-file score or hide a new hotspot in an extraction.

The adapter extraction must reduce AudioOutputRouter's responsibilities and size; PlaybackService must
gain no routing policy and have no net growth from the completed change. Existing 60-line method and
20-public-function limits remain, without new exclusions/suppressions. Aim for focused new collaborators
around 150–250 physical lines; review anything over 300 for cohesion, rather than splitting arbitrarily.
Review prepareProof's existing complexity before adding branches; keep resource cleanup with its lifetime.
Reject extra pass-through layers, duplicate decisions and callback bundles that merely relocate a hotzone.

Before advancing each slice, review ownership, removed code, test reachability and complexity deltas.
Use economical sequential specialist review when available. A review that was not performed must remain
listed as outstanding. Physical acceptance, input attribution and future remote backends are separate
claims; none becomes complete because the structural refactor or CI passes.
