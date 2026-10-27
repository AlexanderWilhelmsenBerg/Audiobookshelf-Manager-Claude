# Phase 3 playback refactoring acceptance — issue #289

This is the software completion checklist for the selected unmerged stack
**#294 → #292 → #291 → #290 → main**. Sibling #293 is closed as superseded.
The authoritative repository is GitHub. Phase 3 completion is distinct from merging
these PRs and from Phase 4 device/head-unit acceptance.

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
| Endpoint identity, discovery and listener output preference | `AudioOutputRouter`, `LiveOutputEndpointRegistry` |
| Attached-player guard, probe proof, grace and volume lifetime | `HeadsetRouteGuard`, with router epoch authorization and `AudioTrackRouteMonitor` evidence |
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

## Follow-up safety and ownership slices — 27 October 2026

The suppressed cold `MediaButtonReceiver` request now reaches Media3's safe rejected-session
shutdown through `PlaybackService.onGetSession`. Its process-scoped one-shot gate prevents
service recreation from suppressing every later deliberate toggle. This addresses the observed
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
