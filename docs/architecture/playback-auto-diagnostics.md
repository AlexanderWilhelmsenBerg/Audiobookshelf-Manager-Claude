# Android Auto diagnostics ownership — Phase 3

This document describes the stacked, unmerged Phase 3 changes in
[draft PR #291](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/291).
Its base is the Phase 2 scenario suite in draft PR #290.

## One trace authority, no playback authority

`AndroidAutoTraceRecorder` now owns the mutable Android Auto correlation sequence,
current trace, bounded 10-second focus/arrival reuse window, and privacy-safe
formatting of elapsed-time fields and sampled media rows. These were all previously
owned by `PlaybackService`.

`PlaybackService` continues to own Media3, audio focus, car recovery, request/Play
decisions, the real player/sleep/progress timeline, and the **read-only snapshots**
that are supplied to the recorder. The recorder cannot change a route, authorize
a recovery, select an output, create a player or ascribe a virtual media key to a
physical headset.

A trace records `source` and `afterTrace` with a process-local `auto-N`
correlation ID. A second event inside the inclusive 10-second window reuses that
ID, preserving the previous behavior. After that window it starts a new trace.
Only a known Android Auto controller package may attach to an already-active
trace. Without a trace, logging is uncorrelated rather than inventing identity.

Media-row diagnostics are bounded by
`AndroidAutoDiagnostics.MAX_MEDIA_HANDOFF_ITEMS` and use
`AndroidAutoDiagnostics.itemFields` and `metadataPresenceFields` for
privacy-safe projections. Never place private item titles, server hosts,
credentials, or raw Bluetooth names in a `LogField.Public`.

The recorder is a **diagnostic projection**, not a state owner for listener
intent or verified output. Recording a preferred output or a controller Play
still cannot demonstrate the actual playing AudioTrack or human-heard headset.

## Regression protection

`AndroidAutoTraceRecorderTest` checks the correlation window, unique sequence,
controller package filter, uncorrelated events, bounded media rows and exclusion
of private titles/identifiers from public log fields. The existing Android Auto
Robolectric service tests and Phase 2 S01–S22 scenario matrix continue to verify
callback reachability, focus/car event ordering and route-evidence precedence.

## Next extraction boundaries

- System/MediaSession raw input versus interpreted transport intent must remain
  separate from actual route destination evidence.
- Generation-aware asynchronous effects must execute against the single
  Media3 application dispatcher and retain the newest legitimate listener
  intent. Avoid a new playback state machine or duplicate ExoPlayer.
- Keep existing fail-closed physical AudioTrack verification and progress/profile
  ownership guards intact; do not address #276/#277 handoff by inferring headset
  identity from virtual button metadata.
