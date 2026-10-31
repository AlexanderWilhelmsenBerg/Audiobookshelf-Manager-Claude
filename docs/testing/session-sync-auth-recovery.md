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
