# #91 — unified resume freshness

Status: implementation PR is intentionally **draft** until #81 lands. #81 changes the playback-session
handoff that this coordinator must sit on top of; wiring against the pre-#81 order and then resolving the
same overlap again would make the most timing-sensitive code in the app harder to review.

## Problem

#89 fixed one narrow path: when the in-app Play button resumes a restored paused book, it can compare an
acknowledged baseline with the current ABS progress and adopt a remote move. The decision still lives in
`PlayerViewModel`, so standard Media3 Play commands from the notification, Bluetooth/headsets, Android Auto
and steering-wheel controls bypass it.

The #89 device run also exposed an unexplained move back toward the pre-adoption baseline. The later move
forward in that run was intentional (the tester tapped a History row); the earlier backward move was not.
That is enough evidence to stop adding independent position-changing paths.

## Android Media3 boundary

Android's current Media3 guidance is explicit: standard `Player` commands sent to a `MediaSession` are
forwarded to the player automatically. To customize standard Play/seek behavior, wrap the player in
`ForwardingSimpleBasePlayer` and override the relevant `handle{Action}` methods. This is the correct place to
make app, notification, headset and car Play converge without replacing standard media controls with custom
commands.

After #81 lands, the service will therefore expose a forwarding player to `MediaLibrarySession` while
retaining the underlying ExoPlayer for internal atomic operations.

## Serialized resume algorithm

For every externally requested paused/armed -> Play:

1. Snapshot loaded profile, book and ABS playback-session id.
2. Read the acknowledged `ResumeBaseline`.
3. Allocate a resume-decision generation/token.
4. If #90 delivered a progress event **while this exact baseline generation was current**, and its
   `sessionId` is not this BookWave session, evaluate it with `ResumeFreshnessPolicy`.
5. Otherwise make the existing one-book REST progress check under the two-second latency cap.
6. Apply one shared material-move rule: differences of **two minutes or less continue locally**; larger
   forward *or backward* moves are adopted. Never use `max(position)`.
7. Revalidate token, profile, book and baseline generation after every suspension and before moving the
   player.
8. If adopting, seek/confirm on the service-owned ExoPlayer before audio. If no adoption is required, resume
   the current player normally.

A missing socket candidate never means "current". Socket events are not replayed; REST remains the fallback.

## Invalidation

The forwarding player invalidates any pending resume decision before forwarding explicit local movement:

- seek bar / arbitrary seek;
- skip back/forward;
- chapter jump;
- History or bookmark return (both are seeks at the Media3 boundary);
- stop / replace media / book switch;
- a superseding Play or Pause.

Direct service-owned moves such as auto-rewind also invalidate the decision, except while the coordinator is
performing its own marked remote-adoption seek.

## Realtime evidence

#90 persists a `user_item_progress_updated` row through the normal conflict-safe repository and publishes a
non-replayed in-memory evidence event only when that repository accepted the row. #91 pairs an evidence event
with the baseline generation current at receipt. That means:

- a stale socket push cannot bypass unsynced-local protection;
- a push seen before the current pause is not proof about this pause;
- this BookWave session's own sync echo (same `sessionId`) is not mistaken for another device;
- process death/background gaps naturally lose the candidate and therefore force REST.

## Tests required when wiring after #81

- app Play, notification Play, headset/Bluetooth Play and Android Auto Play all enter the same resume path;
- candidate + same baseline generation + another session + >2 min => adopt before audio;
- candidate <=2 min => resume locally;
- candidate from own session / wrong profile / wrong book / old generation => REST fallback;
- no candidate => REST fallback;
- local seek while REST is in flight invalidates the result;
- profile/book switch while REST is in flight invalidates the result;
- active playback ignores realtime for player movement;
- intentional remote rewind >2 min is adopted;
- #89 restored-baseline stress runs at least 60 seconds without returning to the old baseline;
- logs name generation, evidence source and movement category without media metadata.
