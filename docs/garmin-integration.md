# Android ↔ Garmin integration status

**Classification:** Current cross-repository implementation and acceptance plan.
**Updated:** 2026-10-07. Requirements: AUTH-002/003, PLAY-001/004/005/007, LIB-003;
PRODUCT_SPEC17/19/21. Companion transport adds no version-1 release requirement.

The owner authorized completion of the Android Phase 2 bridge and its Garmin counterpart.
The original candidate `6239a148` is integrated with privacy, retry and lifecycle corrections.
[Garmin PR #5](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/5) implements the receiver;
both repositories carry [the same transport contract](garmin-transport-contract.md).

| Area | Implemented scope | Remaining obligation |
| --- | --- | --- |
| Android | Application startup, Mobile SDK2.4.0 adapter, existing Media3 snapshot projection, profile/privacy guard, ordered delivery and bounded ack retry. | Strict forced gate/PR CI and source-matched phone/watch acceptance are recorded in the [delivery log](testing/2026-10-07-garmin-bridge.md). |
| Garmin Companion | Foreground Communications receiver, nonce negotiation, ordered snapshot/clear handling, valid-state persistence, durable acks and stored/waiting UI. | Target builds/test-method compilation/export pass on PR5; Run No Evil execution and G-01/G-02 hardware remain NOT RUN. |
| Playback and accounts | Existing Android Media3, captured queue owner, profile/lock repositories and ABS/session owners remain authoritative. | Garmin failure must not disturb heard audio or progress; no credentials/hosts travel to the watch. |
| Offline watch audio | WatchShelf + Sidecar remain the initial coexistence policy. | Provider/helper replacement and direct watch ABS authentication are deferred. |

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

Commands through #114, legitimate-event reconciliation/Settings Force sync, complications, watch face
and Running Data Field remain later phases. Projection updatedAt is wall time, not a durable listening
event timestamp. Future reconciliation must preserve deliberate rewind, never max(position), and never
start playback. Garmin does not block an Android release or outrank Android reliability.
