# PR #271 — Unified diagnostics and Bluetooth cold-start safety

## Scope

PR #271 incorporates PR #272's existing passive route lab, output classifier, updated About screen,
and event-log enhancements. It adds one opt-in recording session and one machine-readable JSON
clipboard report. No remote uploads, raw Bluetooth identities, server URLs, or media titles.

## Hardware recording protocol

1. Park the vehicle; pause playback and open Settings → About → Debug → Start diagnostics / JSON report.
2. Tap **Start diagnostics**. This simultaneously enables the **Audio Route Lab**, **Output
   Classifier**, and event-log playback-race capture. The opt-in is saved synchronously.
3. Confirm **Restart BookWave** to install the AudioTrack observer when the next Media3
   ExoPlayer is created. Restart is explicit because an existing player cannot receive a newly
   injected `AudioOutputProvider` and modifying playback mid-book violates continuity.
4. On Android API 28+, the restart trampoline is private, runs in a separate process, closes only the app's main
   process and reopens the launcher. If the OS does not report the main process or refuses
   restarting it, the app asks the tester to close/reopen BookWave manually. Diagnostic
   opt-in survives. On API 26/27, Start diagnostics retains the opt-in but asks for manual close/reopen instead
   of starting a second app process. Main-process-only initialization uses a PID-based compatibility fallback.
   This remains a hardware acceptance requirement, not a promise about OEMs.
5. Open Debug → Unified diagnostics after reboot. The lab's status must say
   `Installed for this player`, `AudioTrack attached`, or a measured playing route
   before a test can claim AudioTrack coverage. `Not installed` means no route observation.
6. Perform Bluetooth A/B and Android Auto arrival/departure tests. Mark physical headset
   key presses and human-heard destination in the separate Audio Route Lab sheet.
7. **Stop diagnostics** then use **Save JSON file** to choose a location in Android's
   system document picker (for example, Downloads), or **Copy complete JSON report**
   if a clipboard copy is preferred. Both actions produce the same versioned report
   with redacted Event Log entries, playback-race events, Audio Route Lab rows,
   Output Classifier rows/snapshots, and Debug Console text. Saving is explicitly
   user initiated, requires no storage permission, and writes the entire UTF-8 report
   without a clipboard-size limit. Cancellation creates no file; write errors are
   reported rather than claiming success. The app never uploads the report.
   **Clear all** deletes the lab file and clears in-memory captures/logs.

## Provenance and privacy

- `schema=bookwave.diagnostics`, `schemaVersion=1`. Each event log entry is a JSON
  object with UTC timestamp, level, tag and already-redacted line.
- Route Lab and Output Classifier use process/session aliases rather than hardware
  addresses or names. Diagnostic metadata must never elevate A2DP or remote submix to a
  uniquely identified headset, car, or physical loudspeaker.
- The full Event Log is a bounded, in-memory ring; lab retains a private no-backup tail
  for at most 24 hours; classifier is process local; race capture is bounded.
- Structured JSON includes raw diagnostic lines **as strings within arrays**, not as
  executable JSON. Do not treat a guessed source as verified.
- The optional additive `audioRouteLab.inputStatus` and `inputDevices` fields retain the latest
  input-capability inventory, including after Stop. `input-key`, `input-transport` and input lifecycle
  rows are in the existing bounded Route Lab events. Schema version1 remains compatible; older reports
  omit these new fields. Device IDs are runtime input handles; descriptor ordinals are local to the
  accompanying `inputCapture`, never a Bluetooth identity or audio-output mapping.
- Restart loses the old process's in-memory event-log buffer and classifier history.
  Recording begins again at the new process boundary; old bounded Audio Route Lab file
  may be recovered. Never merge times from different process runs without checking timestamps.

## Input and Auto capability capture

Start unified diagnostics and perform the explicit restart above for complete AudioTrack coverage.
Input observation itself needs no player recreation or new permission. Confirm that exported
`audioRouteLab.inputStatus` says input callbacks are being watched, or record its explicit unavailable
status. `inputDevices` contains at most64 entries. Do not infer physical identity from an empty list.
Note the phone Android version, Android Auto app version and wired/wireless connection in the test
notes. `controllerVersion` in this export is the Media3/legacy controller-library version, not the
installed Android Auto app version.

Perform one action at a time with a stationary car and note the heard destination:

1. Without Auto, mark ButtonA and press headset Pause/Play. Record heard HeadsetA, Car, Phone or Silence.
2. Connect Auto without touching controls. Mark AndroidAuto and record any pause and destination.
3. Repeat the marked headset buttons; then mark CarPlay and use the dashboard Pause/Play controls.
4. With Car selected, repeat ButtonA/headset Pause/Play, then the explicit Headset action. Compare sound,
   not just dashboard state. Repeat the explicit Car action and steering-wheel controls separately.
5. Note a timed phone/notification Pause/Play control as a comparison. These can also produce generic
   session commands. User markers and time adjacency are temporal evidence only.
6. Pause, turn off/disconnect the car, and confirm no unwanted resume. Repeat reconnect and, where
   available, a second headset, including one with the same display name. Test notification/background
   entry and cold receiver entry separately. Capture unsupported/permission-denied outcomes honestly.
7. Stop diagnostics and save the complete JSON. Check exact build/source against issue #289. Clear
   removes the retained lab capture; a later capture's aliases must not be joined to the old ones.

Interpret `entry=ReceiverStart` separately from `SessionCallback`; these may be two deliveries of one
key. `keyFlags`, `repeat`, `heldMillis`, `deviceQuery`, `virtual`, `external`, `deviceSources` and
`descriptorMatches` describe capabilities and transformations. A shared descriptor can represent
indistinguishable devices or separate collections. `association=Unproven` stays authoritative.
`caller=BluetoothService` identifies software forwarding; `caller=AndroidAuto` does not establish a
physical dashboard gesture. `packageVerified` records whether Media3 verified the reported package;
controller trust is a separate software-access property. Controller/interface versions distinguish
legacy/platform adapters. None of these fields establish a physical input-to-output association.

Match `actual-track` output aliases with classifier `trackAlias`, including RemoteSubmix. A mix remains
an observed system route rather than proof of the final car speaker. Use durable `output-proof`,
`car-route-intent`, `car-controller-unbound`, generation/selection and position-correction rows to
correlate effect ordering. `proofEpoch`, `requestGeneration` and `requestSelection` describe the proof's
captured preference/transfer scope; `none` means no transfer request was present. Raw identifiers,
descriptors and names are never needed in a public report.

`proof-prepared` distinguishes Initial/Final compensation stages and records `bufferedSeek` with
captured proof/request scope. `car-intent-retired` records the departed Car request and replacement
selection sequence. A final-stage overshoot still fails closed. These rows diagnose timing and state
retirement; they do not establish physical button identity or human-heard audio.

See [platform/related-issue research](../architecture/playback-input-capability-research.md). If the
app still receives only virtual events, an immediately collected **private** Android bug report may
help locate where origin is lost; it does not guarantee an app-accessible physical source.

## Cold-start virtual toggle guard (#273)

Physical 2233 logs showed `KEYCODE_MEDIA_PLAY_PAUSE (85)`, input device -1, as the playback
service was created after headset power-on, followed by an external Play request and
Media3 cold-book resumption. Neither the event nor the input name proved whether a
person pressed the button.

`ColdStartToggleGate` consumes **only the first** virtual/unattributed MEDIA_PLAY_PAUSE
DOWN/UP per current set of live Bluetooth appearances in the first 3 seconds of an empty Media3 queue with an enumerated Bluetooth
output and no detected car session. It is process-scoped so a rejected cold
`MediaButtonReceiver` start cannot recreate the three-second window on every service
restart. New appearances rearm the gate using the existing live endpoint tokens, without attributing a physical button. Media3 identifies receiver starts in `onGetSession`; BookWave returns no
session only for the same gated event, allowing Media3's `stopSelfSafely()` path to
complete the foreground-service start without playback. Explicit MEDIA_PLAY (126),
physical-attributed keys, established queues, warm service controls, and detected car
requests are not gated. The one-shot safety trade-off must be tested and reviewed
before merging.

## CI/hardware gates

- JVM regression: cold virtual toggle vs explicit Play, car, loaded queue, warm
  state, absent Bluetooth and physical key, plus the inherited route lab tests.
- KtLint, Detekt, Android Lint, Robolectric and `verifyDebug` must pass on the exact
  combined head.
- The process-restart trampoline, AudioTrack observer activation, intentional
  headset PLAY_PAUSE during cold start, Android Auto Play, headset A/B media controls
  and absence of speaker leakage require stationary **physical** acceptance.
- Do not merge #271 or #272 or represent the new behavior as hardware-accepted on CI alone.
