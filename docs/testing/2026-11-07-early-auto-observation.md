# Early Android Auto observation and focus investigation

**Requirements:** PLAY-001/002, ROUTE-001/002; existing S01-S22 and PD-009 remain authoritative.
**Scope:** PR306 continuation. No second player, audio probe, focus listener or permission.
**Status:** Implemented candidate; local strict verification passed. Exact-head CI is recorded in PR306.
Hardware benefit is unproven until provider timing is measured on the test phone.

## Evidence from build 2263

The owner's 2026-11-07 11:05:24 diagnostic records focus-loss Pause at 10:53:17.495 UTC, first car bind
at 10:53:21.417, and app-level audible-route confirmation at 10:53:21.633. The projection broadcast
arrived at 10:53:23.452; the provider query took 40 ms. About 3.92 seconds passed before the first car
boundary, followed by about 0.22 seconds of recovery. The log does not identify the competing focus
requester or establish that the provider would have returned Projection earlier.

The owner reported headset destination retention, car/headset button switching and phone Pause/Play
retaining the last destination. The remaining connection pause is not accepted as solved. Software
`audible-confirmed` records actual-track/player evidence, not an acoustic measurement.

## Candidate behavior and regressions

Only a qualifying arrival focus-loss decision starts up to seven read-only refresh requests in six
seconds. Reads use the existing provider/lifecycle path, with `trigger=interruption-N`, returned state,
reason and query duration in the existing diagnostic log. No focus request is made by the observer.
Repeated callbacks do not extend the window; newer intent, successful connection, Play or teardown
stop it. The existing recovery gate and actual-track verification remain necessary for any Play.

Verify early positive provider state without a broadcast, duplicate later broadcast, Unknown and
unqualified interruption, bounded duplicate requests, cancellation and monitor teardown/recreation.
For service reachability, remove the refresh start call and confirm the early-arrival regression fails,
then restore it before the full strict gate. A provider fixture cannot establish real OEM timing.

Local verification (2026-11-07, JDK 21): `ktlintFormat`, strict
`verifyDebug -Pshelfplayer.warningsAsErrors=true` and `:playback:testReleaseUnitTest` passed.
Reports contain 2,997 debug/JVM and 1,161 release playback tests, no failures/errors/skips, including
nine new regressions. Removing the early-observation start call made the production-path early-arrival
test fail (expected one provider read, observed zero); the implementation was restored before the full gate.

## Next stationary test

Clear diagnostics, start narration in one headset, connect Auto and leave controls untouched until
audio returns or the interruption clearly persists. Export promptly. Compare the first positive
`interruption-N` result, first controller bind, projection broadcast and `audible-confirmed` timestamps.
If every early read remains NotConnected/Unknown, polling has not discovered an earlier usable signal;
do not compensate by guessing car arrival from a generic focus loss.

Repeat with deliberate Pause during the interruption, initially paused playback and a normal focus
interruption without a car. None may acquire new automatic Play authority. Recheck dashboard/headset
buttons and phone transport. Stop the test if routing or narration becomes incorrect.

## Optional USB focus trace

No device was attached during this implementation. To determine who requests focus and when it returns,
capture a short stationary reproduction with an authorized ADB connection. Keep raw output locally in
the ignored build directory; dumpsys can contain other app/device information and must not be posted
unreviewed to a public PR. No root or app permission change is needed for an authorized ADB shell.

In one PowerShell terminal, start the narrowly tagged trace before connecting the car:

```powershell
adb logcat -T 1 -v threadtime -b main -b system -s MediaFocusControl:V AudioService:V AudioFocusManager:V '*:S' > build/auto-focus-logcat.txt
```

Stop with Ctrl+C after the short reproduction. In another terminal, save before/after focus snapshots:

```powershell
adb shell dumpsys audio > build/auto-audio-before.txt
# Connect Auto and reproduce the interruption, then:
adb shell dumpsys audio > build/auto-audio-after.txt
```

Correlate focus request/grant/loss/abandon events with the app's UTC timestamps, including requester
package/UID and gain type where the OEM exposes them. A snapshot alone cannot reconstruct a transient
focus owner, and OEM builds may omit logcat details. Absence of a tag is not proof of no focus loss.
Public app playback-configuration observations do not reliably identify the focus owner; do not add
another focus request/listener or hidden API to obtain it.

## Platform references

- [AndroidX provider observation implementation](https://android.googlesource.com/platform/frameworks/support/+/d76a331e15f0327ab229c41e63085df691b88109/car/app/app/src/main/java/androidx/car/app/connection/CarConnectionTypeLiveData.java):
  initial query and broadcast-triggered refresh; no documented promise that state precedes a broadcast.
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus): retain ExoPlayer focus
  management and respect interruptions; a generic loss does not establish car identity.
