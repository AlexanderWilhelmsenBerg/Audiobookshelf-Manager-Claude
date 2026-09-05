# Restored paused playback freshness

Device testing while validating the realtime socket lifecycle exposed a separate SYNC-002 gap.

After switching profiles, BookWave restores the incoming account's last book paused. The restored item is opened through `/play`, so the server supplies its current start position. The previous implementation nevertheless discarded the old `ResumeBaseline` on the media-item transition and established no replacement because the restored item never went through a play -> pause transition.

If another device then moved the book while BookWave's realtime socket was reconnecting, the next in-app Play saw no acknowledged baseline and skipped the server freshness check. The device run observed BookWave resume the older restored position and begin syncing it back after History showed the newer remote session.

The fix keeps the `/play` start position as staged evidence until Media3 actually transitions to the incoming item. At that transition it becomes the acknowledged baseline for the restored paused item. A local play, seek, pause, or later item transition retains the existing invalidation rules. R-61's single-file fallback stages no baseline because its player position is file-relative while the server position is book-relative.

This defect is independent of the realtime socket lifecycle change that exposed it: a socket cannot replay an event that happened before it authenticated, so the playback fallback must remain correct when realtime misses an event.

## 2026-09-05 device retest: the freshness decision passed

The physical-device retest after rebasing #89 onto the current `main` proved the part this PR was written to fix.

The restored paused book held an acknowledged baseline at about **56:43** (`3,403,222 ms`). Another Audiobookshelf client moved the same book to about **3:53:42** (`14,022,513 ms`). On the next in-app Play BookWave made exactly one one-book progress request and logged:

- `server=14022513ms baseline=3403222ms verdict=ahead`;
- the service-owned player reported the adoption seek landed exactly at `14022513ms`;
- `Resumed on a position adopted from another device ... outcome=Resumed`;
- the next interval sync accepted `14024733ms`, proving audio/progress had continued from the adopted server position rather than from the restored 56-minute position.

That closes the original #89 failure: a restored paused item now has enough acknowledged evidence for the next Play to discover and adopt progress made elsewhere.

## A second movement happened afterwards, but the log changes the diagnosis

A few seconds after the successful adoption the player moved back near **56:36**, and later returned to about **3:53:42** before the tester's real pause. At first glance this looked like the atomic adoption being undone by buffering.

The combined Event Log + History evidence says something more specific:

- History contains a local **Seek** row from roughly `3:53:48 -> 56:36`;
- it then contains another local **Seek** row from roughly `57:01 -> 3:53:42`;
- the actual user pause is later and is independently logged as `playWhenReady=false reason=userRequest`;
- the service's internal remote-adoption operation records `RemoteProgress`, not `PlaybackEvent.Seek`;
- `PlaybackEvent.Seek` is recorded by `PlaybackController.seekTo`, whose app-facing route is `PlayerViewModel.onSeekTo`;
- the full player wires that route to its seek controls, and the player History sheet wires the same route to `onReturnTo`;
- every History row is currently clickable and `RemoteProgress.returnTo` deliberately points at the position this device held **before** the remote move.

So the second movement is **not evidence that the confirmed adoption seek spontaneously fell back**. The strongest code-level evidence is that a UI-level seek request reached `PlaybackController`. Because the History sheet was being used during the test and its entire rows are seek/undo targets, an unintended History return is the leading explanation. It is not treated as proven until the retest below removes that ambiguity.

This matters because patching ExoPlayer's buffering/seek-confirmation path for a movement that actually came through the UI would hide the real interaction bug and make the resume path more complex without protecting anything.

## Immediate #89 follow-up

For the retest, History navigation is made explicit rather than making the whole row an invisible seek target:

- reading/scrolling a History row must do nothing to playback;
- only the trailing undo/return control may invoke `onReturnTo`;
- the remote-adoption row keeps its intentional undo semantics, but taking that undo now requires an explicit press on the undo control.

The acceptance run should avoid opening History until after the remote-adoption playback has been allowed to run and then paused. If the position remains at the adopted server point, the previous rollback was a History interaction and the resume fix itself is sound. If the position moves without any explicit seek/undo action, capture the new log before changing the atomic-resume path; that would be a distinct player/service defect.

## The larger freshness problem is real and separate

Even with #89 correct, BookWave does not yet have one resume-freshness policy across every control surface.

Today the one-book REST check lives in the app player's `onTogglePlayPause`. Notification Play, Bluetooth/headset Play and car/media-session controls can resume without going through that ViewModel. Realtime has the opposite limitation: it can reduce latency while connected, but it is intentionally foreground-scoped and missed Socket.IO events are not replayed.

The intended architecture is therefore:

1. **ResumeBaseline** — the last position this device and Audiobookshelf demonstrably agreed on;
2. **realtime progress** — an optional low-latency, profile/book-scoped candidate while the socket is connected;
3. **one-book REST progress** — the authoritative fallback whenever realtime cannot prove freshness;
4. **one playback-layer resume coordinator** — the single policy used by app Play, notification, Bluetooth/headset and Android Auto before a paused/armed item starts audio.

Realtime must never directly seek active playback. A push received while this device is playing is information for storage/conflict handling, not permission to move the live player. Issue #90 owns support for Audiobookshelf's current `user_item_progress_updated` event; issue #91 owns the shared resume policy and control-surface integration.

One product rule also needs to be settled there rather than accidentally inherited: the current REST comparison treats a difference over about five seconds as a remote move, while the earlier media-button requirement discussed ignoring small drift and adopting only a materially different position (about two minutes). That threshold must become one explicit rule with tests, including intentional remote rewinds.