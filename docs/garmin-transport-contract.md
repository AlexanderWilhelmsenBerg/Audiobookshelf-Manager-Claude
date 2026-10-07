# Phone ↔ watch transport contract — Phase 2

This is the first delivered transport contract, capability `ordered_state`, protocol major 1.
It extends the unreleased Android candidate; Phase 1 snapshot persistence remains readable.
Both repositories carry this document. Changes require updating both copies.

The Companion application ID is `6f5b4fa4a2db4d42a9d41ee67df34d11`.
Android pins Garmin Mobile SDK 2.4.0. Garmin Connect Mobile provides wireless transport.
The foreground Companion uses only the Communications permission, API 6.0.0 and the existing
fenix843mm/fenix847mm targets. No background receiver is promised while the watch app is closed.

| Key | Type and requirement |
| --- | --- |
| `v` | Integer 1; fractional/non-integral values rejected. |
| `t` | Message type. Unknown types have no action. |
| `id` | Non-empty string, at most 64 characters. |
| `r` | Optional bounded string, exact request correlation for acknowledgements. |
| `ts` | Positive integer/Long Unix epoch milliseconds; not a reconciliation event timestamp. |
| `p` | Dictionary with string keys, at most 16 fields. Unknown compatible fields ignored. |
| `s` | Watch foreground-session nonce, bounded string, required for state and acks. |
| `n` | Positive integer/Long delivery sequence, required for state and acks. |

Messages: `hello`, `hello_ack`, `snapshot`, `snapshot_ack`, `state_request`,
`clear_state`, `clear_ack`. Other packets never execute playback or server actions.
`hello.p` contains `majors:[1]` and `caps:[...,"ordered_state"]`.
Android probes with hello on app availability, at most three attempts ten seconds apart.
Watch sends its own hello nonce on foreground startup and in response to the phone probe.
Android hello_ack correlates `r` and `s` to that watch hello ID and advertises selected:1,
compatible:true and ordered_state. Watch accepts only its latest nonce. Unsupported capability
fails visibly without private state delivery. Old hello_ack packets cannot rebind the new watch session.

After negotiation, Android sends clear_state before any snapshot and waits for clear_ack accepted:true.
Every lock/no-current-book/profile-generation change discards pending private payloads and requires
another clear. The watch redacts memory immediately and atomically replaces persistent metadata
with a redaction tombstone. Failed storage returns accepted:false; success is never inferred from
BLE enqueue/completion. Offline watches retain their last stored snapshot until a clear can arrive;
remote deletion is impossible during a disconnect. Reconnect requires handshake and clear first.

State payload is the existing PlaybackSnapshot dictionary with source PHONE. Android requires the
Media3 queue's captured profile owner to equal the current unlocked, authenticated profile.
Snapshot identity bounds are 96 characters, title/chapter 160, author 120; position >=0, duration
unknown/absent or positive with position<=duration, updatedAt positive Long and playing Boolean.
The watch validates before replacement; rejected snapshots preserve the last valid one.

Android has one in-flight state message and one latest desired snapshot. State sequence increases
within a watch nonce. Watch rejects old sequences/streams; a duplicate accepted ID+sequence returns
the same successful ack without a second write. Acks carry matching r,s,n and Boolean accepted.
Wrong/malformed/duplicate/stale acks cannot consume pending state. Retries use the identical envelope,
at most three attempts ten seconds apart, including paused snapshots and clears. A watch state_request
can recover exhausted delivery; disconnect/stop discards pending state. History is bounded to one ack.

Snapshots follow book/chapter/play-pause/seek/duration/speed transitions and at most a normal 30-second
refresh while playing. Phone cadence accounts for playback speed. Watch asks for state every 30 seconds
while foreground; after 60 seconds without accepted state it displays stored/waiting. This is recent
state receipt, not proof of continuous connection or actual watch audio playback. No per-second BLE.

No ABS credentials/hosts/full objects, watch commands, force sync, progress conflict resolution,
complication, watch face, Data Field or independent offline-audio provider are in this slice.
WatchShelf + Sidecar remain the initial offline-audio path. Transport updatedAt must not be used
as a listening-event timestamp for a future newest-legitimate-event reconciliation policy.
