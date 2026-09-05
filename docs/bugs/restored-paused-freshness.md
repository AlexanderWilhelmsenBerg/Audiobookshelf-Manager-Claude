# Restored paused playback freshness

Device testing while validating the realtime socket lifecycle exposed a separate SYNC-002 gap.

After switching profiles, BookWave restores the incoming account's last book paused. The restored item is opened through `/play`, so the server supplies its current start position. The previous implementation nevertheless discarded the old `ResumeBaseline` on the media-item transition and established no replacement because a restored paused item never went through a play -> pause transition.

If another device then moved the book while BookWave's realtime socket was reconnecting, the next in-app Play had no acknowledged baseline and could skip the server freshness check. The app could then resume the older restored position and start syncing that stale position back to Audiobookshelf.

## Fix in PR #89

The `/play` start position is staged as evidence, but is not trusted immediately. It becomes an acknowledged `ResumeBaseline` only when Media3 actually transitions to the incoming item. Staged evidence is consumed once and the existing invalidation rules still apply when the listener moves the player or another item replaces it.

R-61's single-file fallback deliberately does not establish this baseline because that fallback uses a file-relative player position while Audiobookshelf's `startAt` is book-relative. Treating those two coordinates as agreement would create false freshness evidence.

The durable session/outbox transition remains ahead of the player handoff: `BookChanges.onBookOpened` opens the session first, then stages the server start before the item reaches Media3.

## 2026-09-05 physical-device result

The acceptance path this PR was written to fix passed on device.

The restored paused book held an acknowledged baseline at about **56:43** (`3,403,222 ms`). Another Audiobookshelf client moved the same book to about **3:53:42** (`14,022,513 ms`). On the next in-app Play BookWave logged:

- `server=14022513ms baseline=3403222ms ... verdict=ahead`;
- the service-owned player confirmed the adoption seek landed at `14,022,513 ms`;
- `Resumed on a position adopted from another device ... outcome=Resumed`;
- the following interval sync accepted `14,024,733 ms`, proving playback had continued from the adopted remote position rather than the restored 56-minute position.

That verifies the narrow #89 contract: a restored paused item now carries enough acknowledged evidence for the next freshness check to discover and adopt progress made elsewhere.

## Separate uncommanded seek observed during the same run

A few seconds after the successful adoption the player moved from roughly **3:53:48** back to roughly **56:36**. The tester did **not** request that backwards move. History recorded it as an ordinary local `Seek`.

A later move back to about **3:53:42** was intentional: the tester clicked a History entry to return to that position. That later seek is expected behavior and must not be used as evidence of a defect.

The remaining backwards seek is therefore real but not explained by #89's adoption operation:

- `PlaybackController.resumeLoadedAt` records a remote adoption as `RemoteProgress`, not `PlaybackEvent.Seek`;
- an ordinary `Seek` is recorded by the normal controller seek path;
- the backwards destination is close to the pre-adoption baseline, which suggests stale resume/position state may still be participating, but the current trace does not prove which caller initiated it.

Do not widen #89 with a speculative player or History fix. The branch keeps the existing History row interaction unchanged. The uncommanded seek is part of the larger resume-coordination work tracked in #91, where local seeks, auto-rewind, realtime candidates, REST checks and media-session Play requests can be serialized under one policy with explicit invalidation.

## Larger freshness architecture

#89 is a prerequisite, not the full freshness solution.

Today the one-book REST check lives in the app player's `onTogglePlayPause`. Notification Play, Bluetooth/headset Play and Android Auto/media-session controls can resume without going through that ViewModel. Realtime has the opposite limitation: it can reduce latency while connected, but missed Socket.IO events are not replayed after disconnect/background/process death.

The intended architecture is tracked in #91:

1. **ResumeBaseline** — the last position this device and Audiobookshelf demonstrably agreed on;
2. **realtime progress** — an optional low-latency, profile/book-scoped candidate while connected;
3. **one-book REST progress** — the authoritative fallback whenever realtime cannot prove freshness;
4. **one playback-layer resume coordinator** — a single serialized policy for app Play, notification, Bluetooth/headset and Android Auto.

A local seek or other explicit listener action must invalidate/cancel any stale in-flight resume decision before that decision can move playback. Realtime must never directly seek active playback. Issue #90 owns support for Audiobookshelf's current `user_item_progress_updated` event; #91 owns the shared decision and control-surface integration.

One product rule also needs to be settled there: current REST comparison treats roughly five seconds of difference as remote movement, while earlier media-button intent discussed ignoring small drift and adopting only a materially different position of around two minutes. #91 should define one rule and test it in both directions, including intentional remote rewinds.
