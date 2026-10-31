# ADR-0032 — Output intent has one owner and a verified logical lock

**Status:** Implemented candidate; software verification passes and device acceptance remains pending.
**Requirements:** PRODUCT_SPEC PLAY-002, ROUTE-002, PLAY-008.

## Context

Output intent and its sequence were owned by `AudioOutputRouter`, while route evidence separately mirrored
the listener's destination, release target, and last observed sequence. That duplication allowed output
surfaces and service observations to disagree. The owner also needs a user-facing way to keep audio on the
currently measured output when automatic routing or projection changes occur.

Android can report the exact mapped sink of the playing `AudioTrack`, but it cannot reliably attribute a
virtual/system media key to a physical headset or distinguish a classic A2DP headset from a dashboard.
Names, connection state, enumeration order, and predicted media policy are not physical proof.

## Decision

One process-scoped `OutputIntentOwner`, instantiated by the singleton `AudioOutputRouter`, owns explicit
selection sequence, destination/release intent, and the transient user-facing output lock. The service's
`RouteHeardOwnership` reads that same instance while retaining its independent evidence provenance and
existing loaded-book generation. It does not mirror the listener intent or selection counter.

The phone output chooser may arm **Keep audio here** only for a loaded book whose current player is playing
and whose current AudioTrack has one fresh, exact, live mapped output. The user confirmation is destination
intent; it does not identify a physical button or prove an ambiguous A2DP endpoint is a wearable.
Conflicting, missing, predicted, duplicate, or stale route proof refuses the lock. There is no Bluetooth
address storage or permission expansion.

The lock retains a logical target through ordinary Pause/Play and nonempty book/source changes. Each existing
book-generation or player-attachment change invalidates physical proof and queued work, requiring fresh
verification before guarded output effects. Empty queue/explicit Stop, profile handover, and service death
release the runtime lock. Runtime endpoint IDs are not persisted.

Release is represented by absence of a lock after the newer explicit Automatic/Car/output choice or the
runtime boundary. There is no separate mutable Released flag alongside that authoritative choice.

[ADR-0036](0036-explicit-output-transfer-requires-current-track-proof.md) refines the explicit-transfer
commit boundary: Automatic releases immediately; Car/another output supersedes desired intent while
retaining the prior lock only as recovery context until current proof succeeds. Failure retains it Waiting.
The retained prior lock never authorizes an old recovery to override the newer desired destination.

Automatic routing, generic transport, and Android Auto controller binding cannot steal or release the lock.
An explicit Automatic, Car action, or another confirmed output supersedes it. Loss, unknown route, or
mismatch fails closed and retains the logical target; a new endpoint appearance cannot rebind by name or
sole-A2DP status. Reverification does not call Play. If playback is already active it may verify that current
playback; otherwise the lock waits for the listener's next Play.

An explicit Car destination also excludes headset preservation/recovery during a later host rebind.
The former headset may still be the measured output while Android settles or declines the request;
that truthful observation is not authorization to override the newer Car choice. A newer explicit
output selection can make that endpoint eligible again.

One Media3 player/session/focus owner remains. Existing `RoutePreferenceEffects` and `HeadsetRouteGuard` own
serialized platform effects and call-scoped safety-pause provenance; route evidence and car recovery retain
their current scopes. This decision does not add automatic headset-key destination handoff; #277 owns the
explicit verified transfer and rollback transaction, defined in ADR-0036.

## Consequences

- Every app/system observation uses one explicit intent and sequence authority.
- The chooser displays lock state and offers release without becoming a policy owner.
- Disconnect/reconnect and ambiguous inputs can cause a safe pause, never automatic playback or target adoption.
- Android device, projection, and real headset-button acceptance remains required; JVM/Robolectric proof does
  not establish audible physical routing.
