# Session progress authentication recovery — GitHub #288

AUTH-004 applies to interval sync, pause/shutdown close and offline outbox upload as well as
session opening and streaming. `DefaultSessionSyncRepository` remains the durable progress and
acknowledgement owner. `SessionSyncRequests` owns only the authenticated write attempt: the
captured profile, session and payload are reused after one renewal through the shared
`AuthRepository`. A second authentication rejection requires reauthentication. Other failures
are not retried by this boundary. Coroutine cancellation propagates.

A failed or cancelled request retains its durable outbox position. Success is acknowledged only
through the existing server-session response or per-row offline acceptance verdict. A profile
switch does not retarget work to the newly active account. No new endpoint, DTO or token store
is introduced.

## Deterministic software evidence

`SessionSyncAuthRecoveryTest` exercises the production repository and request owner with real
Room and controlled gateway/auth results: 401→renew→success, denied renewal, second 401,
cancellation, close and offline-batch upload. The retry preserves the captured profile/session/
progress even when active selection switches during renewal. Existing outbox and full progress-
chain scenarios share a fixture and retain their assertions. Existing auth/coalescing and
network contract tests cover token rotation, explicit profile credentials and server origins.
These compositional checks are not one end-to-end HTTP/device acceptance test.

The Windows-only `AtomicFile.rename` test shadow is the identical adjustment already on #294.
It models Android/Linux replace-existing rename while retaining real AtomicFile transactions,
recorder privacy assertions and the stronger committed-file recovery assertion. Production
recorder code is unchanged.

## Stationary device retest

1. Use a build containing this fix. Record its source SHA and enable privacy-safe diagnostics.
2. Play a downloaded/server-backed book with a server session, then use the existing debug
   **Expire the access token** control while retaining a valid refresh token. Avoid unrelated
   account refresh/browse activity while waiting for the next periodic session write.
3. Confirm a rejected session write causes one refresh exchange and the same operation succeeds
   with renewed credentials, without requiring `/api/authorize`, a new Play or a profile switch.
   A stream-range renewal can legitimately win the shared race; identify which request renewed
   before attributing this result specifically to session sync.
4. Pause and confirm the final position is acknowledged; repeat after leaving queued offline
   sessions and reconnecting. Preserve accepted/declined server-position semantics and history.
5. With controlled test accounts, switch profiles during recovery and confirm the original
   session remains under its original account. No credentials or raw server/media metadata
   should appear in the shared diagnostic report.
6. A refused refresh or second 401 must retain local progress and surface sign-in-required state
   without a refresh loop. Do not invalidate a production refresh token just to stage this case.

No device, physical playback or server acknowledgement acceptance is claimed by JVM/CI results.

## Android 16 and Audiobookshelf 2.36.0 acceptance

The API 36 emulator ran a local BookWave debug build (source revision unknown) and played a
generated audiobook on a disposable Audiobookshelf 2.36.0 server. Once playback was underway,
the **Expire the access token** action replaced the app's access token while keeping its refresh
token. Playback continued without a seek, profile switch, or new Play request.

A loopback HTTP observer forwarded each request to Audiobookshelf and retained only method,
route category, status, and whether the retried bytes matched the rejected write. The server
returned these results in order.

| Action | Server result |
| --- | --- |
| Periodic session sync with the expired access token | HTTP 401 |
| Refresh exchange | HTTP 200 |
| Retried periodic sync | HTTP 200 with the identical request body |
| Final progress sync after Pause | HTTP 200 |

The interval replay completed without an intervening authorize request. The tests below cover
refused refresh, offline replay, and profile switching in separate runs.

### Refused refresh

In a separate run, the loopback observer substituted an intentionally invalid fixture
credential only for the refresh exchange. Audiobookshelf itself returned HTTP 401. The
prior build made four refresh requests as periodic sync and offline uploads continued to
encounter authentication errors.

With credential retirement enabled, the periodic write returned 401, the refresh exchange
returned 401, and the next observation window contained one refresh request in total.
The repository regression also checks that a restore keeps the profile marked for sign-in
and that a temporary network failure preserves the saved credential. The progress repository
tests cover retention of unsent writes across the refusal.

The phone displayed its sign-in-required message and the last synced library after the
refusal. Signing in to the same fixture account succeeded and opened the existing library.

### Offline replay after reconnect

BookWave played a downloaded fixture and paused while Audiobookshelf was unreachable.
Its diagnostics identified the listening session as local. After the loopback connection
returned, the offline-batch endpoint returned 200, and BookWave recorded that the server
accepted a queued row.

With the same Android 16 emulator and Audiobookshelf 2.36.0 fixture, a second run expired the
access token while the server was unreachable and the downloaded audiobook was paused. After the
connection returned, the queued batch itself received HTTP 401. One refresh returned HTTP 200,
and the batch retried with the identical body and returned HTTP 200. BookWave recorded one row
sent, one accepted, and zero still queued. No authorize request intervened in the replay.

### Profile switching during a held refresh

On Android 16 with the stack through `b4da989c` and an Audiobookshelf 2.36.0 fixture, two
synthetic accounts shared a server. The original account, A, played a book before the debug
control expired its access token. After a brief outage the app had pending progress, and the
profile switcher was open. A loopback observer held the first refresh from A while the tester
selected B through the app.

| Observation | Result |
| --- | --- |
| Original progress write | HTTP 401 |
| A's refresh | Held before reaching Audiobookshelf |
| B's authorization during the hold | HTTP 200 using B's credential |
| A's refresh after release | HTTP 200 using A's refresh credential |
| Original progress retry | HTTP 200 using A's new credential, with the identical request body |
| Active account and library after the retry | B remained selected and authenticated library requests as B |

The observer recorded route categories, HTTP statuses, fixture account aliases, and retry-body
equality. BookWave's active account was checked separately through the profile switcher.
