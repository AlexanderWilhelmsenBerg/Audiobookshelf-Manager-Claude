# Phase 2 Garmin bridge and Loopbound APK recovery

**Classification:** Source-scoped automated delivery evidence and physical-test inventory.
**Date:** 2026-10-07. Requirements AUTH-002/003, PLAY-001/004/005/007, LIB-003; PRODUCT_SPEC17/19/21.
Owner authorized completion, merging after green CI and logging physical tests without holding merge.
The owner primary feature/refresh-progress-on-play checkout and its WIP are preserved.

## Source and implementation

Android starts at main de56857d (documentation PR239) and incorporates the original candidate
6239a148 with its nine commits. Garmin starts at main9ecfa372 (documentation PR4);
[PR5](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/5) supplies the counterpart.
This is snapshot transport only; existing Android playback/profile/ABS/progress owners remain.

The actual Media3 queue projects its captured profile owner. Unknown/mismatched/locked/reauthenticated
ownership exposes no metadata. New stream/profile generation requires durable clear acknowledgement.
Strict envelopes, sequence/correlation, duplicate rejection, one pending message, three ten-second
attempts and fresh state requests handle paused delivery and reconnect. SDK callback lifecycle/device
tokens reject old events. Arbitrary remote error text never reaches logs. The foreground watch validates
and persists PHONE snapshots and uses a redaction tombstone for clear; failed writes never ack success.

## Automated evidence

- Before fixes, all three GarminBoundaryRegressionTest methods failed on candidate behavior: fractional
  major/negative timestamp, malformed correlation/non-string keys and missing captured playback owner.
- After correction, focused Garmin tests and real Media3 ExistingSessionAttachmentTest pass. Bridge
  lifecycle tests drive the actual observers/SDK interface, including synchronous conflated-generation
  rejection, lock while pending, stale ack, no owner/no book and stop/restart. Delivery tests cover
  capability failure, durable clear gate, wrong/malformed acks, lost/failed send, paused retry, latest rewind,
  device/reconnect and bounded attempts. Playback cadence accounts for speed.
- Formatter runs. The new SDK's AAR/POM SHA256 values are pinned in strict verification metadata;
  no verification bypass/trust wildcard is introduced. Forced full verifyDebug is required and its result
  is recorded below before completion.
- Garmin PR5 run37630137658 passes repository guardrails, both fenix targets, test-enabled compilation
  and package export. The first compile failed on callback typing and was corrected. Six new Run No Evil
  methods are compiled, not executed. A local simulator/SDK is unavailable; execution remains NOT RUN.
- Caller audit: ShelfPlayerApplication starts GarminBridge; Hilt binds GarminMobileSdk and the read-only
  Media3 source; the bridge consumes codec/projector/privacy/delivery policies. Watch App starts/stops
  PhoneTransport; receiver reaches validators/order/store and requests a view update. No playback command.

## Loopbound failure and recovery

Original checked main run37623427096 failed at private source authentication and skipped APK packaging.
The GitHub repo and exact pinned source7e24529b2a9e419218d2a1423d832b1bf587c8ef existed. The owner
replaced LOOPBOUND_READ_TOKEN with a GitHub fine-grained Contents:Read token scoped to Loopbound.
Attempt2 passes private checkout, Loopbound validation/tests/build, verifyDebug and signed packaging.
It produces APK2199 from de56857d with Loopbound. No secret value was read or recorded.
The final bridge APK will use the same ordinary Loopbound pin; identity/signing/checksum is recorded
below. No phone installation or physical result is claimed.

## Physical/simulator obligations — all NOT RUN in this slice

| Case | Execution required |
| --- | --- |
| G-02-01 | Execute common envelope/model methods in simulator: strict type/bounds, optional unknown duration, Unicode, >24h and Long epoch precision. |
| G-02-02 | Missing/outdated Garmin Connect, no device, app absent, compatible/incompatible handshake; SDK available differs from persisted ack. |
| G-02-03 | Actual book/chapter/progress/playing-paused reception, accepted storage ack and watch restart restore. |
| G-02-04 | Disconnect/reconnect and watch foreground close/reopen, phone/watch restart; truthful stored/waiting state and no automatic Play. |
| G-02-05 | Wrong/stale/duplicate/reordered envelopes and acks, lost/failed send, durable storage fault and bounded recovery. |
| G-02-06 | Profile lock/switch/A-B-A/removal/reauth while connected/offline; no stale metadata after acknowledged reconnect clear. Document unavoidable retained offline snapshot before delivery. |
| G-02-07 | Book/chapter/pause/seek/rewind/no-book/duration/speed transitions; current captured owner remains the source. |
| G-02-08 | SDK/listener shutdown/restart, multiple devices and app/service lifecycle; heard Android audio, timer and local/server progress remain continuous. |
| G-02-09 | Visual readability/truth on target firmware, long Unicode titles, clipping and meaningful connection/error text. Owner judgment only for visuals/sensory checks. |
| G-02-10 | BLE counts, active/paused/disconnected battery, foreground heartbeat and bounded soak; no per-second transport. |
| Q-01/A-08 | Final signed APK in-place upgrade/data/settings retention, source/About/Loopbound first-page identity and ordinary playback/download smoke. |

Record exact Android/watch SHA, APK checksum/signer, Garmin Connect version, watch model/firmware and
fixture before testing. Existing G-01 acceptance under Garmin#2 remains NOT RUN; #3 and Android#119
remain open. Commands, event reconciliation/Force sync, complication and other surfaces are deferred.

## Verified credential-recovery artifact

APK2199: org.homebord.bookwave.debug, version0.10.6.1, minAPI26, source de56857d.
APK SHA256: 685502b5acc69f0bfd2c6079fd75f58a4b300131af23386e6b90e650bb104e1e.
apksigner verifies certificate SHA256 c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c.
The APK contains assets/loopbound/index.html and BOOKWAVE_LOOPBOUND_VERSION equal to the exact pin
7e24529b2a9e419218d2a1423d832b1bf587c8ef. The Actions artifact is the APK itself here; archive extraction
alone cannot verify its signer. The final bridge artifact is a separate build below.
