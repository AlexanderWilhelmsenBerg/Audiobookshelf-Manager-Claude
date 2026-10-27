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
- Restart loses the old process's in-memory event-log buffer and classifier history.
  Recording begins again at the new process boundary; old bounded Audio Route Lab file
  may be recovered. Never merge times from different process runs without checking timestamps.

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
