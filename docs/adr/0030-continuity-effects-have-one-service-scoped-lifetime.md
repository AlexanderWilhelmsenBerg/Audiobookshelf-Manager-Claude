# ADR-0030 — Continuity effects have one service-scoped lifetime

**Status:** Implemented in the unmerged Phase 3 branch for issue #289; acceptance requires exact-head CI.
**Requirements:** PLAY-001, PLAY-002, PLAY-008, ROUTE-001, ROUTE-002.

## Context

The service launched arrival, departure and focus recovery independently. An older suspended route wait
could finish after a newer episode became Ready, then clear the newer gate claim. Targets with identical
route, book generation, selection and phase also compared equal across separate lifecycle intervals.
Moving the transaction out of the service alone did not resolve that ownership ambiguity.

## Decision

`CarContinuityResumeEffects` owns one pending service-scoped coroutine for route holding or recovery.
Controller and projection boundaries and platform interruptions enter that owner on the existing Media3
application dispatcher. A repeated Ready callback reuses its pending transaction; a newer episode cancels
the old wait. An ordinary later controller bind cannot replace an already-current Ready transaction.
The handle is installed before immediate dispatcher execution, including synchronous player callbacks.

The existing `CarArrivalResumeGate` remains the sole episode authority. Its issued `Target` instance is
the opaque one-shot claim identity, in addition to the existing book and listener-selection checks.
Equal route fields do not allow an old callback to borrow a new episode. Completion and cancellation
discard only their own claim. Rejection cleanup clears only its matching diagnostic record; a successful
Play retains awaiting-audible confirmation across its own synchronous cancellation. Global invalidation
remains reserved for actual listener, book, queue and service boundaries.

The service cancels pending effects on controller transport requests (including duplicate Pause), raw
player transport transitions, accepted explicit output choices, book/queue replacement and destruction.
`RouteHeardOwnership` reports whether it accepted the router's existing selection sequence so the service
does not add a copied listener sequence. Cancellation is checked again after synchronizing listener
intent, before any headset protection or reassertion. Active Media3 Play intent preserves the exact
headset guard without reasserting the preferred device or issuing another Play.

`AudioTrackRouteMonitor.currentRouteIds` snapshots its existing observer epoch before reading the active
track. State, playback-state and route-query failures return no measurement. Replacement/release during
the query invalidates its result, even when the successor uses the same endpoint or track object.
An old playing AudioTrack cannot authenticate unmuting its successor.

## Consequences and verification

There is no new player, session, focus owner, playback state machine, route policy, device identity,
book generation or effect queue. `DeferredPlaybackWork` retains durable media installation ownership;
`RoutePreferenceEffects` retains preferred-device write ordering. The new Job owns only the lifetime of
the existing continuity effect transaction. `AudioOutputRouter` retains muted-probe and measured-route
verification, and Media3 retains audio focus.

Production-path tests reproduce stale claim completion, equal-value episode reuse, cancelled holds,
immediate self-Play callbacks, accepted one-Play/timer effects, replaced player access after suspension,
and exact/unknown/wrong/multiple AudioTrack evidence. Route-query tests use the actual monitor with
platform shadows. Reverting the relevant claim/query guards reproduces their failing tests.

PR #294 is the selected recovery implementation; sibling #293 is an alternative, not its parent.
Hardware headset-button attribution and destination handoff remain #276/#277. HTTP 401 remains #288.
Software verification does not establish physical audio acceptance.
