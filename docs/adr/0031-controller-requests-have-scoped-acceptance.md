# ADR-0031 — Controller requests have scoped acceptance

**Status:** Implemented in the unmerged Phase 3 branch for issue #289; final acceptance requires exact-head CI.
**Requirements:** PLAY-001, PLAY-002, PLAY-004, PLAY-008, ROUTE-002, AUTH-002, AUTH-005.

## Context

A controller Seek or Stop can arrive while the raw player is already paused. It need not produce a
`playWhenReady` callback. Notification skips bypass the forwarding player entirely. Invalidating only
resume freshness left suspended car recovery eligible to issue Play after these newer commands.

MediaSession queue resolution also suspends while opening a server session. The old queue-opening
helper published History, progress/session, freshness and sleep state before checking whether the
controller request still owned playback. Checking only the returned media was too late.

## Decision

`PlaybackService.onControllerCommand` is the shared fanout for forwarding-player movement and custom
notification skips. It advances the existing command generation, invalidates freshness and sleep work,
and clears pending continuity effects and the existing recovery claim. Book, empty-queue, explicit
output and service teardown boundaries use the same continuity cleanup. Raw automatic Play retains
its different semantics: it cancels pending recovery without erasing its own awaiting-audible record.

The service constructs its actual `ResumeFreshnessPlayer` through `createSessionPlayer`. Tests can
exercise that production wiring with a real player without reimplementing its invalidation lambda.
This creates neither a second player nor a second command authority.

`ControllerQueueResolution` captures the service's existing command/profile generations and player
identity before asynchronous controller work. Queue preparation has no live book authority. The
existing `BookChanges.acceptBook` checks authorization after durable bookkeeping suspends and before
publishing the live book. Superseded requests cancel their future instead of returning an empty
successful playlist that could clear newer playback. Holder materialization uses the same guarded
acceptance with the existing `DeferredPlaybackWork` claim and installs its item in the accepted turn.

`SessionCallbackFutures` completes queue results on the Media3 application dispatcher. Cancellation
propagates in both directions, including cancellation of the coroutine's parent. Error diagnostics
retain only public error codes and exception class names, never messages or private media data.

Pinned Media3 1.11 also attempts prepare/Play when a cold-resumption future fails or is cancelled.
Returning an empty success is insufficient: its success path installs the empty queue and attempts Play.
`submitPlayback` therefore scopes each listener's actual execution, including listeners attached after
completion or queued by an executor. It authorizes only a successful nonempty result whose request is
still current at delivery. The forwarding player rejects effects from failed/stale delivery before
recording controller intent. Permission is captured once for the compound install/prepare/Play and is
restored in `finally`; the delivery's own generation changes cannot revoke its Play, while a subsequent
genuine controller Play remains allowed. Raw service effects bypass this external adapter as before.

The same call-scoped context identifies passive resumption Play. The service captures that classification
before launching freshness work; the old global `passiveResumptionPlayPending` flag is removed. A skipped
or failed framework Play cannot leave a passive token for a later explicit request.

Read-only browse callbacks, serialized profile switching, and cold resumption have dedicated helpers.
The service retains connection/lifecycle adaptation, the single ExoPlayer/MediaLibrarySession,
transport/freshness integration and existing durable owners. Profile switching still uses the domain
lock/flush/pause operation. Cold metadata description opens no playback session.

## Route provenance

`AudioOutput.activitySource` distinguishes a playing AudioTrack measurement, framework media-policy
prediction, selected preference and unmeasured legacy activity. `isActive` retains its presentation
behavior; it is not physical proof. `RouteHeardOwnership` preserves that provenance while retaining
the accepted listener-priority and headset-hold policies. Only the router's current playing-track
proof can unmute guarded playback.

Queued safety work also revalidates the existing preference-effect generation, player and exact
guard on the Media3 dispatcher. A superseded collision callback cannot pause a newer Car choice.

## Consequences

No new playback state machine, focus owner, command counter, route policy or durable session owner is
introduced. Command, item, profile, gate-episode, output-selection and preference-effect generations
retain their distinct meanings; combining them would lose valid cancellation boundaries.

Behavioral tests cover suspended command cancellation, guarded queue acceptance, future completion,
profile changes and route provenance. Existing S01–S22 and source-sensitive safety checks remain.
Physical headset attribution/handoff remains #276/#277, HTTP 401 remains #288, and device acceptance
belongs to Phase 4. Completing this software refactor does not certify physical sound.
