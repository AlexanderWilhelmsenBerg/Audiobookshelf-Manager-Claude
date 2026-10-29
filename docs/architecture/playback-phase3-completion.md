# Phase 3 playback refactoring acceptance — issue #289

This is the software completion checklist for the selected unmerged stack
**#294 → #292 → #291 → #290 → main**. Sibling #293 is closed as superseded.
The authoritative repository is GitHub. Phase 3 completion is distinct from merging
these PRs and from Phase 4 device/head-unit acceptance.

## Output-ownership continuation — 28 October 2026

The owner subsequently authorized the dependent #276/#277 implementation plan after the 2241 route-loss
report. Those features are active work, rather than deferred work in the earlier refactor review below.
Draft #297 adds measured route-loss safety; #298 consolidates output intent and verified logical lock;
#299 shares volume effects and audible-time accounting; #300 adds the common guarded activation boundary.
All remain unmerged. The selected sibling is #294; closed #293 is not stacked into this work.

The #300 position checkpoint adds one probe-owned heard-position projection, compensation before Pause/Stop,
and existing-authority checks around synchronous and suspended effects. Strict local verification passes
2,700 tests /319 suites, including 884 playback tests, with no failures, errors or skips. Normal exact-head
CI and the remaining software slices are tracked in
[the current checkpoint](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6448375926).
Explicit transfer/rollback now passes full strict local verification: 2,748 tests /322 suites, including
932 playback tests, with no failures, errors or skips. Its exact committed source and normal CI are recorded
in that checkpoint. Selection-scoped deferred work also passes full strict local verification: 2,759 tests
/325 suites, including 943 playback tests, with no failures, errors or skips. Muted heard-route gating
and typed selection/UI consolidation remain required. The full execution plan is not complete. A final combined candidate must also include
separate auth #296; physical output, startup and real-token acceptance remain distinct.

### Remaining work overview

| Work | Current status | Exit evidence |
| --- | --- | --- |
| Common activation and held position | Published in draft #300 at `84f192d4`; strict local and normal exact-head CI 40704175652 pass 2,700 tests | Recheck the final combined candidate |
| Explicit verified handoff and rollback | Published in draft #300 at `7133b974`; strict local and normal exact-head CI 40762550930 pass 2,748 tests, ADR-0036 | Recheck the final combined candidate; physical acceptance remains separate |
| Service asynchronous ownership | Selection scope passes strict local verification; normal exact-head CI tracked in checkpoint | Existing selection sequence revokes stale prepare/install/Play; captured progress writes still complete |
| Muted heard-route observation | Remaining | Muted raw playback cannot create heard-route evidence or consume the awaiting recovery claim; unmute and listener teardown use current ownership |
| Typed presentation and callback cleanup | Remaining | Requested/measured/predicted/unknown remain distinct; Waiting/Verifying/Verified/Delegated/Failed shown truthfully, with English/Norwegian and accessibility checks |
| Final integration and APK | Remaining | Include auth #296, full `verifyDebug --rerun-tasks`, normal exact-head CI, independent review, then one combined signed QA APK |
| Device/server acceptance | Pending | Stationary headset/Auto and lifecycle guide, #295 startup cases, #288 genuine 401 renewal; unsupported input identity recorded honestly |

Green evidence above belongs to its recorded revision. It does not verify subsequent implementation work or
constitute physical audio, zero-silence, headset-key identity, startup or real-server acceptance.

### Selection-scoped deferred work — 29 October 2026

Pending player mutations capture the existing explicit-selection sequence alongside command, item,
profile and player identity. A newer output choice revokes queued Play/Prepare, retry, held-item
materialization and accepted-connect restoration. A Car controller's own synchronous route selection
is captured before its Play future starts, so the accepted request does not invalidate itself.
Controller queue preparation and durable acceptance capture that same sequence, including cold
callback delivery. Two suspended-queue regressions and a real Media3 cold-future regression failed
before that claim gained the selection check.

Captured pause journaling and session synchronization intentionally retain their original book,
profile and heard position after an output-only choice. Their authorization remains separate from
player mutation. No selection counter or persistence owner was added. Changing output also retires
sleep-grace transport authority through the existing timer API without resetting an active timer.

The focused production-path controls pass. Removing only the selection guards produced five intended
regression failures while the captured-persistence, own-Car-Play and current-post-connect controls
remained green; exact source bytes were restored. The restored full strict repository gate passes
2,759 tests /325 suites, including 943 playback tests; formatter, lint, Detekt and coverage pass.
The shared service fixture and tests are split by command continuity, queue mutation, output protection
and selection scope, retaining the existing cases and assertions. APK assembly was excluded.

## Ownership after refactoring

| Responsibility | Owner and boundary |
| --- | --- |
| Player, MediaLibrarySession, focus configuration and service lifetime | `PlaybackService`, existing `PlayerFactory`, Media3 |
| Controller movement and custom skip invalidation | `PlaybackService.onControllerCommand`, using the existing command generation |
| Browse protocol and entry-time player snapshots | `LibraryBrowseCallbacks` |
| Serialized profile-row/custom-command switching | `CarProfileSwitcher`, invoking the existing domain switch |
| Cold metadata versus playback resumption | `PlaybackResumptionCallbacks` |
| Asynchronous queue request authorization | `ControllerQueueResolution`; final publication through `BookChanges.acceptBook` |
| Callback completion and cancellation | `SessionCallbackFutures` on the Media3 application dispatcher |
| Car/projection lifecycle and diagnostic correlation | `CarLifecycleAuthority`, `AndroidAutoTraceRecorder` |
| One-shot episode eligibility | `CarContinuityRecoveryCoordinator` and its existing `CarArrivalResumeGate` |
| Suspended route hold/recovery lifetime | One service-scoped Job in `CarContinuityResumeEffects` |
| Preferred-device effect ordering | `RoutePreferenceEffects`, existing generation and Media3 dispatcher |
| Endpoint identity, discovery and platform preference | `AudioOutputRouter`, `LiveOutputEndpointRegistry`, `RoutePreferenceEffects` |
| Explicit destination/selection/release and runtime lock | One `OutputIntentOwner`, shared by router and route evidence |
| Raw activation on all service-owned paths | One `PlaybackCommandEffects`; controller/fresh-start/retry/install/restore/continuity adapters supply existing authorization |
| Attached-player guard, probe proof and grace | `HeadsetRouteGuard`, with router epoch authorization and `AudioTrackRouteMonitor` evidence |
| Route mute and latest sleep gain | One `PlaybackVolumeEffects` writer; synchronous mute boundaries freeze timer/listened accounting |
| Held position and compensation during muted verification | `MutedPositionEffects`, using the sole checkpoint on the existing probe and existing authorization scopes |
| Loaded-book candidate evidence | `RouteHeardOwnership`, preserving the router's typed provenance |
| Progress, history, sleep, rewind and profiles | Existing domain/repository collaborators; accepted atomically by `BookChanges` |

There is no new independent playback authority, player, session, focus manager,
command counter, effect actor or Gradle module. Item, command, profile, selection,
preference-effect and gate-episode identities protect different boundaries and
are deliberately not collapsed into a single generation.

## Completion criteria

1. Real controller Seek/Stop/media replacement and notification skips revoke
   suspended continuity even when the paused raw player emits no transport callback.
   Automatic raw Play retains its own awaiting-audible diagnostics.
2. A slow media callback cannot publish a session, History or timer state after
   newer command/profile/player ownership wins. Final acceptance rechecks after
   suspending bookkeeping. Superseded callbacks finish as cancelled futures.
3. All controller queue-opening paths use preparation followed by guarded
   acceptance. Idle-holder materialization installs its item in that accepted turn;
   cold playback keeps authoritative start positions and normal freshness checks.
4. Browse/profile/resumption protocols have coherent helpers. Permission checks,
   same-process URI trust, metadata-only no-open semantics, profile no-op behavior,
   passive sleep intent, error redaction and dispatcher confinement remain intact.
   Failed or stale cold-resumption futures cannot trigger Media3's fallback prepare/Play;
   valid compound delivery remains passive and later genuine Play remains explicit.
5. Presentation activity identifies measured track, predicted policy, selected
   preference or unmeasured origin. Only a current playing AudioTrack can authorize
   guarded unmuting. A superseded queued safety check cannot pause a newer choice.
6. Existing S01–S22 contracts, direct accepted-one-Play/muted-probe tests, cancellation
   races, and new service command/callback tests pass. Targeted regression checks
   must fail with their relevant guards removed. Full local verification and normal
   PR-triggered CI must pass on the final recorded SHA.

## Earlier refactor checkpoint — 27 October 2026

The suppressed cold `MediaButtonReceiver` request now reaches Media3's safe rejected-session
shutdown through `PlaybackService.onGetSession`. Its process-scoped gate remembers the current live Bluetooth appearance tokens and prevents
service recreation from suppressing every later deliberate toggle. Newly enumerated appearances rearm cold protection without assigning a physical key origin. This addresses the observed
#295 sequence; physical Android 16 startup deadlines and other empty/locked/failed/cancelled
explicit resumption paths remain unverified. The merge hold is retained for acceptance.

`HeadsetRouteGuard` owns the temporary endpoint guard, disappearance grace, attachment-specific
listeners, probe jobs, actual-track verification and restoration of the volume it muted.
`AudioOutputRouter` retains live endpoints, listener selection and preferred-device ordering.
The existing preference epoch authorizes the guard's effects; no new generation or player is added.

Retired callbacks cannot affect a replacement or reattached player. A policy reassertion cannot
make an unknown playing track audible by retiring an old probe: current-route safety is checked
before volume restoration. New listener intent during that measurement wins. Deterministic
router/continuity tests exercise these paths and preserve exact-route-only unmute, one accepted
Play, cancellation and duplicate-callback contracts.

HTTP 401 progress recovery is implemented separately in [draft #296](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/296).
Its captured-profile network request owner preserves durable progress/outbox acknowledgement;
physical/server retesting remains separate from this playback refactor. #276/#277 identity and
headset handoff remain deferred.
## Review and remaining acceptance

The current-implementation review found and corrected four concrete ordering defects:

| Finding | Correction and behavioral evidence |
| --- | --- |
| Seek/Stop/custom skip could leave suspended recovery eligible while already paused | Shared command fanout; real service forwarding-player and custom-skip tests |
| Slow media callbacks published live book state before final ownership checks | Prepare-only resolution followed by `BookChanges.acceptBook`; generation/profile/player/lock races and batch preparation tests |
| Media3 attempted fallback Play after a cancelled cold future | Scoped listener delivery and forwarding effect checks; real MediaSession cancelled/valid cold replay and later explicit Play |
| Old queued collision safety work could pause a newer output choice | Recheck existing effect generation, player, guard and selection before and after measuring the track |

Controlled guard-removal runs reproduced **22 test failures**: five for cold fallback
and listener delivery, five for service command/deferred-work invalidation, seven for
queue claims plus one for cancellation during acceptance, and four for queued route
safety. Every temporary mutation was restored.
The restored full suite and final exact-head CI are the acceptance gates, not those
deliberately failing runs. Typed provenance tests additionally exercise the actual
router's measured/predicted/preferred precedence and unknown fallback.

The completion review must inspect current source and tests, not infer correctness
from extraction size or a code-intelligence score. Repowise has partial test
reachability and a mock semantic embedder; its three-index limit also prevents a
new branch index in this session. GitHub source, direct source review and executed
tests provide the exact-revision evidence. See ADR-0030 and ADR-0031 for the decisions.

S01–S22 retain their physical/inconclusive outcomes in the scenario ledger. Full
Hilt service lifecycle, OEM focus/projection timing, human-heard output and physical
headset-button attribution require a device/head unit. #276/#277 retain the known
headset lock/handoff product work; #288 has a separate implementation in draft #296, with device/server acceptance pending. These are
Phase 4/separate-defect acceptance, not unimplemented Phase 3 extraction work.

Existing source-sensitive safety assertions remain unless equivalent production
behavior is demonstrated. Broad removal of characterization tests is unnecessary
for this completion and must not weaken the safety contract.
