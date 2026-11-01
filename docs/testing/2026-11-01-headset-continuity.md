# Delayed Android Auto arrival and headset identity

Requirements: PLAY-002/004/008/009, ROUTE-001/002. Candidate on PR300, integrated testing on PR301.
Physical acceptance remains pending. APK2249 failed arrival continuity; earlier green software gates do
not accept the replacement candidate. Exact commits, gates and artifact are recorded in issue #289.

## Evidence and policy

The 1 November capture shows a measured speaker route followed promptly by safety pause. Auto's first
bind arrived about 10.6 seconds later and projection about 14.6 seconds later, outside the old six-second
pairing interval. Headset Play was denied because the retained endpoint was absent and no Bluetooth
identity had been established. Explicit destination buttons subsequently worked. This capture has no
native-speed distortion evidence; it does not resolve the earlier 177ms handoff-bound refusal.

The tested APK had no BLUETOOTH_CONNECT permission. Android may anonymize Bluetooth addresses without
it. Permission is necessary on affected devices, but does not guarantee discovery or route control.
The old validator also applied an Ethernet unicast-bit rule that is not a Bluetooth address rule.
References: [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions),
[AOSP address anonymization](https://android.googlesource.com/platform/frameworks/av.git/+/7747edd6a55ae5f153e51f345021c899190b647d%5E%21/),
[Bluetooth address validation](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothAdapter.java).

## Implementation boundaries

- The phone chooser offers optional Nearby devices access, an explanation and app settings. Neither the
  service nor Auto prompts. No scan, location or advertising permission is added. Denial leaves basic
  playback and explicit routing available. Permission changes refresh discovery and never issue Play.
- The existing endpoint registry owns process-private correlation. Permission loss, duplicate identity
  and contradictory current-handle evidence revoke retention. Permission transitions preserve a
  continuously observed live appearance. A grant cannot make masked cached data trustworthy.
  Addresses and salted identity keys are never persisted or exported. Capability reasons are public;
  current track proof remains separate. Unsupported transports keep appearance-only identity.
- An actually playing, uniquely measured headset receives one preferred-device request per current
  claim/native handle. It does not pause, mute, seek, rebuild the track or certify success. A newer
  destination/attachment invalidates queued effects. Existing wrong/unknown-output protection remains.
- The existing recovery gate owns one qualified arrival episode: identity must have been trusted when
  the headset was actually heard, remain trusted, and pair with an actual Auto boundary. One absolute
  30-second deadline starts at the earliest interruption. It replaces the competing six-second/two-second
  bounds only for that qualified arrival. It is a maximum wait, never a settling delay. Ordinary focus
  and departure retain their conservative policy. Inventory changes wake recovery immediately.
- One claim may start one muted Play. The existing probe must obtain fresh actual-track proof and retain
  generation, listener selection, permission and deadline validity through unmute. Expiry/refusal cannot
  retry automatically. Pause, Stop, seek, book/profile or attachment changes invalidate old work.
- The session forwarding player derives pending Play state from its delegate instead of publishing an
  optimistic Play while authorization is pending. Standard Play, Pause, empty-session resumption and
  seek still enter the existing owners. Completion diagnostics distinguish a request from audible success.

One ExoPlayer/session, Media3 focus ownership, OutputIntentOwner, RouteHeardOwnership and the existing
probe/checkpoint/volume owners remain. Unknown headset keys do not gain physical provenance from this
permission: while Car is selected, an unattributed Play keeps Car selected. Use explicit Headset to switch.

## Verification and device procedure

Focused regressions cover public Player and real MediaController pending Play; valid and masked identity;
permission denial/grant/revocation; duplicate identity; repeated preference callbacks; delayed Auto arrival,
late endpoint return, deadline and intent invalidation; and stale probe proof. The full strict gate must
retain S01–S22 and the existing position/history/profile/sleep/50ms handoff limits. Record actual gate
results and counterfactual failures below; do not infer a pass from the test inventory.

Before each hardware run, clear all diagnostics, enable recording and retain APK/source identity.
Record phone/Android version, headset, car/head unit, and wired versus wireless Auto.

1. Deny Nearby devices. Check ordinary playback and explicit Car/Headset routing; no automatic guess.
2. Grant through the chooser while paused. No sound may start. If discovery remains masked, record the
   waiting state and reconnect the headset deliberately; do not infer identity from its display name.
3. With identity available, play the headset and connect Auto ten times, including slow connections.
   Record audible gap for each cycle, dashboard/raw state and the measured output. Target no gap; less
   than one second is the acceptance limit. OS focus/transport gaps still count as end-to-end failures.
4. Pause during arrival and while recovery waits. It must stay paused. Repeat near the deadline and with
   permission revoked or a different/same-name headset returning. No stale Play or speaker/car spill.
5. Explicit Car→Headset→Car, running and paused, at 1x and 1.25x. Check first-attempt success, stable speed,
   no jumbling/skipped narration and accurate paused progress. Unknown keys must preserve selected output.
6. Repeat with diagnostics off and after background/recreation. Check notification and sleep behavior.

## Software checkpoint

The feature strict `verifyDebug --continue -x assembleDebug -Pshelfplayer.warningsAsErrors=true` gate
passes 2,884 tests /350 suites, including 1,059 playback tests, with zero failures/errors/skips.
Formatter, lint, Detekt and coverage pass. Earlier gate attempts passed runtime tests but identified
structural and test-annotation problems; these were fixed without changing production lint/static rules.
The Robolectric SDK annotation follows the existing JVM-test convention; no test is skipped.

The corrected counterfactual run disables the pending-Play placeholder, initial preference, qualified
arrival window and probe deadline/commit guards: 10 of 50 tests fail, then exact source bytes are restored.
The Bluetooth address regression separately fails before removal of the incorrect unicast-bit rule.
The final native-handle race test covers replacement between scheduling and applying a preference;
removing only its native-handle guard makes that test fail, and exact source bytes are restored afterward.
Public Player and real MediaController tests pass with pending authorization; this is not Auto hardware
acceptance. The measured-headset operation now has one scoped collaborator; the router is 958 physical
lines, down from 998 at the preceding feature head. Existing route/intent/position owners are retained.

Combined gate, exact-head CI and signed artifact are recorded in issue #289 after confirmation.
No physical PASS is claimed. The under-one-second audible-gap target and the earlier 177ms transfer
refusal remain hardware acceptance items.
