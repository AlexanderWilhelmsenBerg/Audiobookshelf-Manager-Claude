# Realtime progress and resume reference review — 2026-09-06

Issues: #90, #91  
Reference client: `pounat/absorb`  
Server: `advplyr/audiobookshelf`

## Current Audiobookshelf contract

The public API documentation describes media progress as a row identified by a library item, with
`currentTime` and `duration` in seconds and `lastUpdate` as an epoch-millisecond timestamp. The individual
Socket.IO event is not part of the public REST/OpenAPI documentation, so its current contract was checked
against Audiobookshelf's server source instead of guessed.

`server/managers/PlaybackSessionManager.js` emits `user_item_progress_updated` after both normal session sync
and local-session sync when progress changes. The envelope is:

```text
{
  id: <media progress id>,
  sessionId: <playback session id>,
  deviceDescription: <human-readable device>,
  data: <MediaProgress.getOldMediaProgress()>
}
```

`MediaProgress.getOldMediaProgress()` supplies, among other fields, `libraryItemId`, `duration`,
`currentTime`, `isFinished`, `lastUpdate`, `startedAt` and `finishedAt`.

BookWave intentionally models only the `data` progress row plus `sessionId`. `deviceDescription` may contain
private device naming and is not needed for correctness. Keeping `sessionId` matters to #91 because it can
identify an echo from BookWave's own open ABS playback session rather than treating every socket push as
another device moving the book.

Audiobookshelf's web client also subscribes to `user_item_progress_updated` and commits the pushed progress
into its user-progress store. Historical `user_updated` support remains useful for compatibility and for
whole-account changes, but it is no longer sufficient coverage for current playback progress.

## How Absorb handles it

Absorb's `SocketService` subscribes to `user_item_progress_updated`, extracts the nested `data` object and
hands that progress to its library provider. It also performs a REST progress catch-up after socket
authentication/re-authentication: entries whose server `lastUpdate` is newer than the locally held value are
replayed through the same remote-progress handler. This is a good pattern because Socket.IO events are not
replayed after backgrounding, disconnects or process death.

Absorb also distinguishes active from paused playback. A pushed update never continuously chases the server
while the local player is actively listening. When the same item is loaded but paused, Absorb may seek the
paused player directly when the remote timestamp is newer and the position differs by more than about five
seconds.

BookWave should copy the **evidence/catch-up principle**, not that final direct-seek implementation. Device
testing around #89 already exposed position ownership races, and BookWave has multiple control surfaces
(app, notification, Bluetooth/headset and Android Auto). #91 therefore puts the decision in one serialized
playback-layer resume coordinator. A realtime update may be a fresh candidate for the next Play, but it must
not independently move the player.

## #90 boundary

#90 is deliberately small:

- understand the current `user_item_progress_updated` envelope;
- keep `sessionId` and the typed progress row;
- apply that row through `LibraryRepository.writeProgress`, preserving the existing unsynced-local and
  timestamp conflict rules;
- never seek the live player from the socket event;
- keep historical `user_updated` support.

A reconnect catch-up can use REST, but any player adoption remains #91's responsibility.

## #91 target architecture

The current #89 freshness check lives in `PlayerViewModel.onTogglePlayPause`, so only the in-app Play button
uses it. MediaSession controllers can resume without the same decision. The replacement should move the
policy to the playback/media-session boundary and serialize every paused/armed -> playing transition that
BookWave can mediate.

Inputs to one decision:

1. the acknowledged `ResumeBaseline`;
2. a profile/book-scoped realtime candidate from #90 when it is demonstrably fresh and not merely this
   BookWave session echoing its own sync;
3. the existing one-book REST progress check as fallback whenever realtime evidence is missing or cannot be
   proven complete.

Every explicit local movement (seek bar, chapter, skip, History/bookmark return, auto-rewind), book/profile
change, stop/reopen or superseding Play/Pause must invalidate an older in-flight decision. Active playback
must never be auto-seeked by a socket push.

The implementation should log a redacted decision generation, evidence source and movement category so a
future unexplained position change can be attributed without exposing titles, URLs, device names or server
identifiers.

## Other Absorb patterns worth considering separately

This review also found several features/patterns Absorb has that are not advertised by BookWave's current
README and may be useful future work rather than #90/#91 scope:

- socket catch-up plus broader realtime invalidation for item/series/collection/playlist changes;
- local-server versus remote-server reachability switching with hysteresis and health checks;
- custom reverse-proxy headers and OIDC/SSO;
- settings/account backup and restore;
- Chromecast, home-screen widgets, equalizer and an in-app car mode;
- podcast and ebook support;
- autoplay-next for series/podcast episodes;
- playlists/collections, notes, richer listening statistics, Audible/Audnexus enrichment and future-book
  discovery;
- server task-progress and server-log socket streams.

These are observations, not requirements. Each should be checked against BookWave's product direction and
existing implementation before being turned into roadmap work.
