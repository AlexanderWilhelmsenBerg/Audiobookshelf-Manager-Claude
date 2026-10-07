# Android ↔ Garmin integration status

**Classification:** Current cross-repository implementation and acceptance plan.
**Updated:** 2026-10-08. Requirements: AUTH-002/003, PLAY-001/004/005/007, LIB-003;
PRODUCT_SPEC17/19/21. Companion transport adds no version-1 release requirement.

The owner authorized completion of the Android Phase 2 bridge and its Garmin counterpart.
Merged [Android PR #240](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/240) integrates the original candidate `6239a148` with privacy, retry and lifecycle corrections.
[Garmin PR #5](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/5) implements the receiver;
both repositories carry [the same transport contract](garmin-transport-contract.md).

| Area | Implemented scope | Remaining obligation |
| --- | --- | --- |
| Android | Application startup, Mobile SDK2.4.0 adapter, existing Media3 snapshot projection, profile/privacy guard, ordered delivery and bounded ack retry. | Strict forced gate/PR CI and source-matched phone/watch acceptance are recorded in the [delivery log](testing/2026-10-07-garmin-bridge.md). |
| Garmin Companion | Foreground Communications receiver, nonce negotiation, ordered snapshot/clear handling, valid-state persistence, durable acks and stored/waiting UI. | PR5/PR6 merged; target builds/test compilation/export and retained artifact checks pass; Current Run No Evil execution is recorded in the provider delivery log; G-01/G-02 hardware remains NOT RUN. |
| Playback and accounts | Existing Android Media3, captured queue owner, profile/lock repositories and ABS/session owners remain authoritative. | Garmin failure must not disturb heard audio or progress; no credentials/hosts travel to the watch. |
| Offline watch audio | Current Companion remains a display; WatchShelf may coexist during transition. | Owner selected a separate BookWave Audio Provider using existing Sidecar. Queue/inventory/events/settings controls are implemented with physical acceptance pending. |

## Privacy and delivery

External metadata requires a captured Media3 queue owner equal to the current unlocked, authenticated
profile. Unknown ownership fails closed. Synchronous profile-generation/lock reads protect conflated
selection and delayed sends. Book clearing, privacy changes, stop and reconnect discard pending state.
The bridge only observes playback; it has no Play/Pause/seek/server command path.

A new watch nonce negotiates ordered_state. Android sends clear and waits for successful durable
clear_ack before metadata. State sequence and exact r/s/n correlation reject stale/out-of-order packets.
One pending payload and one latest desired snapshot bound memory; three ten-second attempts bound
delivery, including paused snapshots and privacy clears. A later watch state request can recover failure.
Native SDK callbacks are guarded by lifecycle/device-resolution tokens. SDK enqueue/availability are
never counted as successful watch persistence. No remote error text is logged.

The watch receiver runs only while the Companion is foreground. Queued old messages cannot bypass its
latest nonce/clear gate. The watch stores accepted state and labels receipt/stored waiting truthfully;
it does not imply watch-local playback. Offline watches cannot receive remote privacy clears until
reconnect; this limitation is explicit in the physical privacy matrix.

## Next acceptance and deferred work

Run [G-02-01–10](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/testing/phase-2-transport.md)
with exact APK/watch SHAs, Garmin Connect version and watch firmware. [Garmin #3](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/3)
retains interoperability acceptance; [#2](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/2)
retains Phase 1 acceptance. Android #119 stays open as the cross-repository umbrella.

Phone playback commands through #114, watch face and Running Data Field remain later phases. Provider event import, Settings Force sync and complication publishers are implemented. Projection updatedAt is wall time, not a durable listening
event timestamp. Future reconciliation must preserve deliberate rewind, never max(position), and never
start playback. Garmin does not block an Android release or outrank Android reliability.


## Owner-requested device management — 2026-10-07

The owner requested Playback → Devices with an inline watch menu, truthful connection/last-sync state,
Force sync, a watch-download dialog and a picker of fully downloaded Android books. They also require
watch session visibility. These features are not in the merged Phase 2 display bridge.
[PD-008 scope, upstream boundary and full test inventory](garmin-device-management.md) record the next
integration. The owner accepted a separate BookWave Audio Provider using existing WatchShelf Sidecar
([Garmin #7](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/7)). Unmodified WatchShelf has no phone receiver or remote inventory/session
API; Sidecar serves audio/progress, not a watch's stored inventory. The current Companion is a watch-app;
native audio downloads require the separate Audio Content Provider. Provider/control implementation is present; hardware acceptance remains outstanding. Selecting a phone-downloaded book selects its server item, not local byte transfer.

The future watch face will read BookWave-published Garmin Complications from Companion PHONE and
provider GARMIN state, with original event time where known, source/freshness/privacy. See the
[feed plan](garmin-watchface-state-plan.md) and [Garmin #8](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/8). Direct watch-face ABS login/
polling is not selected. The feed publisher is implemented; no watch face or Data Field is started.

## Verified implementation delivery — 2026-10-08

Garmin provider/publishers are merged in [PR10](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/10), commit `19cc9a67db505fb9389a8861373d97b3c037072b`. All nine PR CI checks pass at `e6829ade`; main CI37699273330 also passes. Matching Android device controls pass full strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` locally: 1024 tasks, BUILD SUCCESSFUL in2m50s. All37 Garmin tests and38 Room migration tests pass, with actual-source A-B-A reversion proof. The bridge uses Room22 and adds no external dependency/classpath change. Its implementation branch is `codex/garmin-provider-controls`; GitHub PR delivery remains authoritative.

[Garmin testing artifacts](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37699273330) contain both supported target PRGs and packages. The [installation guide](garmin-install-for-testing.md) and GD/GF register cover testing; all physical cases remain NOT RUN. Final simulator reruns stalled, so the earlier49 native passes do not establish final-source/native or hardware acceptance.
