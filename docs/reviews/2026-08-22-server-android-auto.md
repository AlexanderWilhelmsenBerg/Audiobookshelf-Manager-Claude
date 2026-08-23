# Server and Android Auto review — 2026-08-22

Scope: server-contact policy and adapters, authentication/session lifecycle, synchronization, playback's
exported Media3 surface, Android Auto browse/resume behavior, and missing production coverage. This is a
read-only review of `main`; the findings below were not silently folded into the visual/genre change.

## Release blockers

### P0 — An external Media3 controller can choose where the active bearer token is sent

`PlaybackService.onGetSession` accepts every controller, and `LibraryCallback.onAddMediaItems` /
`onSetMediaItems` pass through a caller-provided `MediaItem` when `MediaItems.isReadyToPlay` sees any local
configuration. A URI alone therefore qualifies. The streaming client inherits `AuthorizationInterceptor`,
which adds the active token without verifying the request origin.

- `playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt` (`onGetSession`,
  `onAddMediaItems`, `onSetMediaItems`, `onConnect`)
- `playback/src/main/kotlin/com/example/shelfplayer/playback/MediaItems.kt` (`isReadyToPlay`)
- `core/network/src/main/kotlin/com/example/shelfplayer/core/network/http/Interceptors.kt`
- `playback/src/main/kotlin/com/example/shelfplayer/playback/di/PlaybackModule.kt`

Because the service must remain exported for Android Auto and platform controls, the fix is a capability
boundary rather than a manifest permission: only BookWave's package **and UID** may submit pre-resolved
stream items; external controllers may submit only app-issued `book/…` / `at/…` browse ids. Bind every
ambient bearer to its issuing server's scheme/host/port. Prove it with a second-UID instrumentation client
and two `MockWebServer` origins, then rerun the Desktop Head Unit (DHU) browse/play path.

### P0 — Profile switching is not a playback context transaction

`SwitchProfileUseCase` changes the active profile and credential without pausing, flushing, or clearing the
old Media3 session. Progress and bookmark repositories resolve the mutable active profile at write time.
The old book can consequently journal into the new profile; subsequent old-stream range requests can also
inherit the new profile's bearer.

- `domain/src/main/kotlin/com/example/shelfplayer/domain/usecase/SwitchProfileUseCase.kt`
- `data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultPlaybackRepository.kt`
- `data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultBookmarkRepository.kt`
- `playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackController.kt`
- required ordering: `PRODUCT_SPEC.md`, section 6.5

The switch boundary must synchronously pause, snapshot/flush and close profile A before activating B, then
restore B's prior session paused. Playback/session writes should carry an explicit `ProfileId`. Test exact
ordering and rollback, plus a device test switching between two server origins while A is buffered.

### P0 — Privileged HTTP behavior is not contract-proven

Cover upload, metadata embedding, and user activation are live production writes without captured request /
response contracts. More broadly, there is no adapter-level `AbsManagementContractTest`; shape and payload
tests do not prove Retrofit paths, headers, queries, response decoding, or error mapping.

- `core/network/src/main/kotlin/com/example/shelfplayer/core/network/api/ManagementService.kt`
- `core/network/src/main/kotlin/com/example/shelfplayer/core/network/api/AbsManagementApi.kt`
- `scripts/capture-contracts.sh`
- `core/network/src/test/kotlin/com/example/shelfplayer/core/network/CapturedShapesTest.kt`
- `core/network/src/test/kotlin/com/example/shelfplayer/core/network/MetadataPayloadTest.kt`

Capture the three missing operations against an approved server and add load-bearing `MockWebServer`
contract tests before treating those writes as release-ready.

## High-priority server/session findings

1. `DefaultAuthRepository.restoreSession` clears a real `requiresReauthentication` mark merely because an
   old token still decrypts. Only successful renewal, sign-in, or a server-authorized account read should
   clear the mark.
2. `renewSession` turns every refresh failure—including timeout, TLS/network failure, 429, and 5xx—into
   permanent reauthentication. Only definitive credential refusal belongs there.
3. AUTH-004 renewal/replay is implemented in a small number of sync callers rather than at the shared
   authenticated-call boundary. Playback, bookmark, download, management, cover, media, and realtime paths
   do not apply one consistent 401 policy.
4. Management permissions have no fetched timestamp, no common five-minute freshness guard, and no shared
   “refresh after any 403” invalidation path.
5. `LibrarySnapshotWriter` can overwrite newer unsynced Room progress during expanded item/metadata writes;
   successful outbox upload does not clear the corresponding progress row's pending flag.
6. Durable playback outbox replay is not wired to cold start or network reconnection, and offline bookmark
   mutations have no replay worker/coordinator.
7. Required envelopes for libraries, search, users, and offline-session batch results can become successful
   empty values instead of `AppError.ApiCompatibility`.
8. Retry handling ignores HTTP-date `Retry-After` and can retry before a server-requested long delay.
9. Cancelling the last realtime collector can leave its WebSocket open because cancellation exits before
   `socket.cancel()`; socket lifetime needs `try/finally` coverage.
10. The current-user disable safeguard is UI-only and currently dead because `signedInAs` is never populated.

Relevant implementations:

- `data/auth/src/main/kotlin/com/example/shelfplayer/data/auth/DefaultAuthRepository.kt`
- `data/auth/src/main/kotlin/com/example/shelfplayer/data/auth/DefaultProfileConnectionResolver.kt`
- `data/library/src/main/kotlin/com/example/shelfplayer/data/library/LibrarySnapshotWriter.kt`
- `data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultSessionSyncRepository.kt`
- `data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultBookmarkRepository.kt`
- `core/network/src/main/kotlin/com/example/shelfplayer/core/network/realtime/AbsRealtimeConnection.kt`
- `app/src/main/kotlin/com/example/shelfplayer/feature/users/ServerUsersViewModel.kt`

Positive checks: no trust-all TLS or hostname-verifier bypass was found, release cleartext denial remains in
place, bearer tokens are not put in query strings, and default network logging uses the redacting logger.

## Android Auto and Media3 gaps

1. Resumption paths call `openQueue(..., startAt = null)` and can prefer stale server progress over newer
   unsynced local progress. Reconcile progress centrally before `BookChanges.onBookOpened`.
2. A legacy global car auto-play switch coexists with the per-device policy, bypassing its warning/default
   and racing it to open duplicate sessions. Migrate the old value and keep one serialized policy path.
3. Cold media-button playback can race asynchronous token restoration: `/play` can authenticate from disk
   while the first media range GET still sees an empty in-memory token.
4. `OutputDeviceWatcher` exists only while `PlaybackService` already exists, so a warm app that has never
   played cannot learn about a newly connected device.
5. The browse tree is never invalidated with `notifyChildrenChanged`, allowing stale shelves/progress or the
   prior profile's titles to remain in a connected host.
6. Browse-row artwork is incomplete. Bookmark is handled as a custom command but not exposed in the normal
   button list; mark-finished and download commands required by the specification are absent.

The merged manifests and automotive descriptor are otherwise sound, and the one-book/one-window Media3
queue follows ADR-0016. The main risk is coverage: `:playback` has no `androidTest` source set and no service
callback/Binder tests.

Required DHU coverage: discovery, recent root, continue/history/chapters, search and voice search, artwork,
completion progress, controller rejection, profile-change invalidation, and supported custom buttons.
Physical hardware remains required for Bluetooth/wired routing, noisy/focus behavior, OEM cold-start
behavior, and the datastore Keystore tier.

## Broader missing-functionality check

- Performance profiling has not started: there is no benchmark/baseline-profile module and none of the
  startup, browse, playback-start, or long-session budgets in PRODUCT_SPEC 17.3 has been measured.
- The only instrumented source set is the datastore Keystore tier. UI, playback service/Binder behavior,
  migrations on an installed upgrade, release R8 output, and the API-level device matrix remain JVM- or
  build-only evidence.
- Author portrait URL construction follows the published OpenAPI and is gated by the captured
  library-author `imagePath` flag, but the available capture contains no portrait. There is therefore no
  successful `GET /api/authors/{id}/image` fixture or end-to-end request/authentication assertion yet;
  retain the cover fallback and capture that path on an approved server before calling portrait delivery
  hardware-verified.
- ROUTE-002's `Ask` behavior currently amounts to arming a paused media notification, not a distinct
  dismissible prompt. Advanced PLAY-006 buffer values remain presets rather than individually editable.
- User deletion/library-access editing, arbitrary SAF download folders, managed-device CI, and several
  later-version administration features remain deliberately deferred rather than accidentally absent.
- The About text still described three already-completed gaps (profile lock, download pause, metadata
  embedding). That user-facing documentation drift is corrected in the accompanying change.

## Safe bulk genre-edit methodology

There is no verified true bulk metadata endpoint in this repository. The safe composition is one fresh read
and one existing per-item metadata write at a time:

1. Select matching books from Room, case-insensitively.
2. Reload each item using the existing expanded item read.
3. Re-check the source genre against the fresh value.
4. Replace only that value, preserve unrelated genres, and de-duplicate case-insensitively.
5. PATCH only `BookMetadataField.Genres`; do not send unrelated metadata.
6. Report each conflict/failure and never blindly retry an ambiguous write.

This matches the repository's existing `PATCH /api/items/{itemId}/media` contract and the upstream
[Audiobookshelf OpenAPI](https://github.com/advplyr/audiobookshelf/blob/master/docs/openapi.json). It must not
be confused with batch metadata embedding, which modifies source-file tags and is a different operation.

Before release, capture one approved genre mutation and add an adapter-level HTTP contract test for the
exact genres payload and response.
