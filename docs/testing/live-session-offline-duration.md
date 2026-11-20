# Listening time after a live session loses its server connection

Requirement PLAY-005. This record covers duration accounting for a server-opened session that falls
back to the offline outbox. The locally opened session total has its own regression coverage.

## Reproduced on Android 16

BookWave source `a12d3c52c5e6e78210c5cc740667132e531c8897` ran on the API 36 emulator with a
generated audiobook and disposable Audiobookshelf 2.36.0. A loopback observer let one live sync
through, then returned 503 to subsequent live writes before forwarding them. The offline endpoint
continued to reach the server.

| Operation | What reached Audiobookshelf |
| --- | --- |
| Live interval of 29 seconds | Accepted with HTTP 200 |
| First live interval of 30 seconds after the interruption | The proxy held the live request. The offline batch sent 30 seconds and returned 200. |
| Second live interval of 30 seconds | The proxy held the live request. The offline batch sent 30 seconds and returned 200. |
| Pause with 1 second, followed by a zero-delta sync | The proxy held both live requests. An ensuing offline batch sent 0 seconds and returned 200. |

The account history endpoint returned a recent remote row with 128 seconds at position 124.78 and a
newer fallback row at position 187.20 with no `timeListening` value. The 128 seconds include listening
recorded before this probe. The two missed 30-second intervals produced no durable 60-second total.

## Acceptance for the accounting fix

1. Start with 10 seconds acknowledged on a fresh live session. Submit another 30 seconds. In one
   run, drop the request before forwarding. In the other, let Audiobookshelf commit it and withhold
   the successful reply. Listen 12 more seconds, then close with a zero delta.
2. Read account history by the exact remote session ID and BookWave's stable offline UUID. Expect
   10 + 42 seconds when the request was dropped and 40 + 12 when the reply was lost. Both histories
   should total 52 seconds after retry, a process restart, and a repeated offline upload.
3. Preserve the last acknowledged live duration and track the uncertain interval separately from
   subsequent offline listening. Require evidence that resolves the original write before crediting
   its interval to the offline row. Missing history, an absent `timeListening`, and a potentially
   stale history read all leave the result unresolved.
4. Verify an upgraded copy of the existing outbox, and preserve account boundaries when switching
   profiles while a write or reconciliation is pending.

Audiobookshelf adds live deltas and replaces the `timeListening` total for an existing offline UUID.
Those operations require separate accounting, even when both records describe one listening session.
[Live delta contract](https://github.com/advplyr/audiobookshelf/blob/v2.36.0/server/managers/PlaybackSessionManager.js#L358-L359) · [Offline replacement contract](https://github.com/advplyr/audiobookshelf/blob/v2.36.0/server/managers/PlaybackSessionManager.js#L166-L194)
