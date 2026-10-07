# Android ↔ Garmin integration status

**Classification:** Current cross-repository boundaries and candidate integration plan.
**Reconciled:** 2026-10-07. Requirements: AUTH-002/003, PLAY-001/004/005, LIB-003;
PRODUCT_SPEC17/19/21. This adds no version-1 release requirement.

| Area | Source and status | Next obligation |
| --- | --- | --- |
| Android main | `36c25043`, through PR #238; no Garmin transport | Retain Android reliability priority and existing playback/profile/progress owners. |
| Garmin Companion | [PR #1](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/1), main `d8de6fb8` | Build/export/test-compilation PASS; simulator methods and physical acceptance NOT RUN under [#2](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/2). |
| Android bridge | [branch](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/tree/mcp/garmin-phase-2-mobile-bridge), `6239a148` | Owner-confirmed candidate. No open PR or head Actions result returned at audit time. Eight test methods exist; execution/strict gate unverified. |
| End-to-end transport | Not delivered | Garmin has no receiver/transport permission/ack counterpart. [Garmin #3](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/3) tracks contract and integration. |
| Offline audio | WatchShelf/Sidecar coexistence is the initial policy in Garmin's agreement | No BookWave Audio Provider/helper replacement or independent watch ABS access is delivered. |

Android owns Media3/playback, profile identity/locking, ABS access, Mobile SDK adapter, command execution,
progress reconciliation and Settings Force sync. Garmin owns Monkey C, watch persistence, compatibility,
transport counterpart and future complication/watch face/Data Field. #119 is the Android umbrella.
The Garmin `plan.md` owns its internal phases; Android `roadmap.md` orders Android work.

## Candidate code already present

The bridge injects SDK/codec/projector/privacy/send/ack policies, starts from ShelfPlayerApplication and
observes existing PlaybackController and profile/lock repositories. It adds Connect IQ Mobile SDK 2.4.0
and package visibility for Garmin Connect Mobile, not a Room migration or new ABS endpoint.
It projects bounded metadata, unknown duration, position and PHONE source; sends state transitions,
seek deviations and a playing refresh at 30 seconds; tracks correlated acks with bounded history.
These are source observations, not simulator/device or full lifecycle verification.

Candidate message types: hello, hello_ack, snapshot, snapshot_ack, state_request, clear_state,
clear_ack and error. Proposed envelope keys: `v` major, `t` type, `id` message identity,
optional `r` correlation, optional `ts` epoch milliseconds, `p` payload. Major1 is proposed.
Garmin Phase 1 `SnapshotCodec` instead accepts the snapshot dictionary directly; the envelope must be
decoded before reusing it. Shared field bounds agree (IDs96, title/chapter160, author120).
Matching constants/app ID do not establish wire interoperability or a released protocol.

## Bounded next review and integration

1. Open/review the Android candidate separately; rebase/check current main without absorbing owner WIP.
   Run formatter and `verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks` after the SDK/classpath
   change. Audit real startup/listener/message/privacy callers; helper tests alone cannot exercise them.
2. Jointly document envelope/handshake/capabilities, field validation/size, ordering/correlation, rejection,
   integer/timestamp precision, ack/retry and privacy clearing. Add common golden fixtures in both repos.
3. Implement/review Garmin transport separately, preserving rejected-update/last-valid persistence and
   truthful disconnected state. Update Phase 1 permission guardrails deliberately for the actual new boundary.
4. Execute G-02-01–10 from the [Garmin inventory](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/testing/phase-2-transport.md),
   keeping SDK availability, handshake and accepted persisted watch state distinct. Android playback must
   continue if Garmin services fail; locked/stale profiles must not expose private metadata.

Review gaps include async send failure/ack loss, stop/restart and reconnect ordering, no-current-book
clearing, profile changes while disconnected, stale callbacks/messages, duplicate/wrong-type acks,
watch integer sizes and bounded event timestamps. In the candidate, `updatedAt` is projection wall time;
it is **not yet a durable legitimate listening-event timestamp** for future multi-source reconciliation.
Do not use it as a conflict winner without designing that later phase. Reconciliation preserves deliberate
rewinds, never uses `max(position)` and never starts playback.

Commands through #114, reconciliation/Force sync, complication, watch face and Running Data Field remain
later phases. A Companion snapshot saying playing does not mean watch-local audio exists. No runtime
change or phone/watch acceptance is performed by this reconciliation.
