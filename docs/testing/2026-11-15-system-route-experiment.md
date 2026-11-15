# Manual system Bluetooth route experiment

Requirements: PLAY-002, ROUTE-001, ROUTE-002, SET-002. Related to #277; stacked on #307.

This diagnostic spike tests whether the normal BookWave app can move system media back to a connected
headset while the car owns the Bluetooth media route. It does not implement automatic take-back or
early focus recovery. Existing player, session, output selection, safety checks and Media3 focus ownership
remain authoritative. A successful callback reports system selection, never acoustic acceptance.

## Scope and platform choice

The experiment exists only in debug builds, on Android 14+. Release and benchmark builds contain a
no-op UI seam and no scan permission or system-transfer adapter. The owner authorized this manual
experiment on 2026-11-15. `BLUETOOTH_SCAN` is declared with `neverForLocation` in the debug manifest,
and requested together with `BLUETOOTH_CONNECT` only when the listener enables the experiment.
No Bluetooth discovery, active route scanning, hidden API, privileged service or location access is used.
Denied permission leaves ordinary playback and output controls available.

This spike deliberately calls the public platform `MediaRouter2` API behind a narrow port. Android's
[reference](https://developer.android.com/reference/android/media/MediaRouter2) recommends AndroidX
for general application routing. However, AndroidX's current
[MediaRoute2Provider](https://github.com/androidx/androidx/blob/androidx-main/mediarouter/mediarouter/src/main/java/androidx/mediarouter/media/MediaRoute2Provider.java)
filters `route.isSystemRoute()` from its route list. This experiment specifically measures those system
routes; it does not add a new production routing dependency or assume AndroidX exposes inactive peers.
The platform service's
[Bluetooth permission check](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/media/MediaRouter2ServiceImpl.java)
is evidence to test, not a guarantee of vendor behavior. Passive LIVE_AUDIO subscription uses
`activeScan=false` and is removed when the experiment closes.

## Stationary test

1. Use a healthy headset. First resolve any one-earpiece failure that also occurs in other apps.
2. Start unified diagnostic recording, then open Settings → About → Audio Route Lab.
3. Enable the system Bluetooth route experiment and grant Nearby devices access. Confirm that merely
   enabling it leaves narration and the selected destination alone. Names appear only on this local UI.
4. Connect the car while parked. Note which routes Android exposes. If none appear, record that outcome;
   the app must not guess a target from its name or create a fallback speaker destination.
5. While the car owns media audio, explicitly press “Move phone playback to…” for the intended headset.
   This changes the phone's system media routing and can affect other apps. It never presses Play.
6. Export unified diagnostics and note whether sound returned, the perceived silence, and any manual
   Play or output selections. Android's transfer callback is distinct from actual-track route proof and
   audible sound. If BookWave remains paused or safety-mutes, record that and use normal controls.
7. Stop and enable again for another trial. One transfer is allowed per enabled session. A five-second
   timeout is terminal; there is no retry or automatic rollback. Closing stops observation and does not
   reverse an already submitted system request.

## Regression coverage and limits

The policy tests cover observation without transfer, stale/missing routes, speaker/remote/selected
route rejection, permission denial/revocation, non-normal audio mode, single-use requests, mismatched
callbacks, callback duration, timeout finality, old-session callbacks and identifier-free evidence.
Callbacks and UI operations run on the main thread. Only process-local numeric aliases, fixed status
values and monotonic durations enter the existing opt-in incident report. Raw IDs and device names
remain in memory. Enabling is not persisted across app/service recreation.

Hardware acceptance is pending. This change does not establish that inactive Bluetooth routes are visible
on Samsung, that transfer succeeds, that focus is retained, or that any particular silence duration is
achievable. Car HFP connectivity is not used to infer the focus requester or authorize automatic Play.

## Software verification, 2026-11-15

- `./gradlew ktlintFormat verifyDebug -Pshelfplayer.warningsAsErrors=true` passed, including lint,
  detekt, unit tests, coverage checks, debug assembly and release/benchmark compilation.
- 1,239 playback debug tests passed without failures/errors/skips. Nine tests added across
  `SystemRouteExperimentTest`, `PlatformSystemRoutePortTest`, `SystemRouteExperimentControlsTest`
  and the privacy export regression in `AudioRouteLabEvidenceTest`.
- Counterfactual: removing the single-use guard caused the duplicate-request test to fail; the guard
  was restored before the passing gate.
- Merged manifests were inspected: scan permission present with `neverForLocation` in debug, absent
  from release and benchmark. Caller checks follow Audio Route Lab → debug section → ViewModel → port.
- Repowise's incremental review analyzed all 12 eligible code files. Two structural advisories remain
  in the debug controller (`open` complexity 9, `transfer` 11), covering session checks, permission/mode
  validation and expected platform exceptions. Existing code scores did not worsen. This is advisory
  structure evidence; test reachability recommendations are inferred. jCodeMunch's main index supplied
  navigation context; exact candidate files were reviewed locally.

Exact remote SHA and normal PR CI are recorded in PR #308. No hardware, acoustic, TalkBack or physical
large-font acceptance is claimed by these software checks.
