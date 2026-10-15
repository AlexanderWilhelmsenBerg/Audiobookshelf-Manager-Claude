# Issue #257 — deferred playback work safeguards

Owner: Playback & Lifecycle Agent. Scope: PLAY-001, PRODUCT_SPEC 6.4, ROUTE-002.

## Invariants

- Error retry: same player, loaded item, controller/item/profile generations; still idle
  and errored after the delay. A new error, successful Play or item transition cancels the
  previous delay.
- Series advance: the item emitting STATE_ENDED is captured before launch; after next-book
  lookup and /play the original item must still be ended, and no newer controller/profile
  intent may have taken ownership.
- Output auto-load: require an empty player before and after suspension, unchanged
  controller/item/profile generations, and the same unlocked active profile. Returning
  to empty after manual selection does not revive a stale automatic request.
- Both automatic installs use prepareQueue and existing BookChanges.acceptBook /
  SessionSyncCoordinator.acceptSession. Only accepted candidates publish a new playback
  session or update sleep/history/resume metadata.

## JVM / Robolectric regression commands

```bash
./gradlew :playback:testDebugUnitTest --tests '*DeferredPlaybackWorkTest'
./gradlew :playback:testDebugUnitTest --tests '*PlaybackRecoveryTest'
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true
```

Tests suspend queue resolution and guarded session acceptance with coroutine
CompletableDeferred and exercise real Robolectric ExoPlayer item replacement. A tiny
fake Player drives deterministic virtual-time error recovery. Existing normal
pull-request-triggered GitHub Actions runs the project's verifyDebug gate.

## Device smoke checks

Connect Android Auto while a phone-selected book is playing, change book/profile
during restore, and confirm newest manual selection wins. Connect a configured
Arm/Auto-play device and select a different book during automatic loading; the
automatic request must not replace it. Manually choose a book near the end of a
series item and confirm the older automatic series advance is ignored.

Settings → About → Recovery Test (debug builds) probes token-expiry/renewal
handling. It does not inject or deterministically verify these three coroutine
interleavings, so it is only supplementary.

No device or DHU acceptance run is claimed here.
