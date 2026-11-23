# Playback diagnostics

Open **Settings → About → Advanced & diagnostics → Playback diagnostics**. Start recording with the current player in
place, including while audio is playing. BookWave attaches the passive AudioTrack observer when the
player is created. Starting or clearing a capture samples the current track without recreating it
or changing playback. Detailed recording is opt-in and the choice survives ordinary process recreation.

Use **Clear all** before a fresh run. It clears the history, first incident, recent app warnings and
errors, diagnostic counters and stored crash report. It discards previously queued events, awaits
deletion of the private retained trace and keeps an active capture running. Current route, output and
input snapshots are observations of the connected system and remain available while recording.

For a parked Bluetooth or Android Auto test:

1. Start with narration audible in the intended headset. Connect and disconnect the car normally.
2. Use the listener markers for silence, skipping or an unexpected destination. Button A and B mark an
   upcoming press for timing comparison. They establish temporal correlation only.
3. Use **Save JSON file** or **Copy JSON**. Stop recording when done. The report stays on the device
   until the listener exports it. Saving uses Android's document picker and needs no storage permission.
4. The debug build's **Route comparison tools** still provides the automatic headset take-back switch
   and the opt-in system route experiment. Settings that affect routing are separate from capture.

## Reading the export

`schema=bookwave.diagnostics` and `schemaVersion=2`. Version 1 files remain readable as historical
exports with a different shape.

- Build, device Android API, app target API, export time and recording state identify the capture.
- `current` holds the latest track, route, input and classified output observations.
- `timeline` combines playback and system changes, sorted by UTC with a source and event name.
  Stable inventory callbacks do not produce repeated summary rows. Native timing is sampled in a
  bounded burst and raw timing rows are recorded at roughly one-second intervals, plus detected
  state changes. Useful native evidence includes frame position, timestamp, speed and underruns.
- Each `track-timing` row pairs native audio timing with Media3's position within the current queue
  item, buffer and playback state when a player is attached. `route-boundary` marks a route-safety pause
  or confirmed Auto departure and retains timing rows up to four times per second for three seconds.
  `player-discontinuity` records the public reason code and the numeric positions on either side.
  Use the item index and book generation before comparing position deltas across rows.
- `firstIncident` retains the first frame stall, Media3 stuck reason, or listener-marked silence
  or skipping and its context. The retained recorder rows are app private and expire after 24 hours.
- `problems` contains up to forty recent redacted warnings and errors. A sanitized `previousCrash`
  is included when present. The ordinary Event Log and Debug Console remain available for non-playback
  support.

Match `actual-track` aliases with the system output `trackAlias`. A policy prediction is advisory.
Remote submix and frame movement cannot prove which physical device was heard. Virtual input events
identify the sending software where available and cannot prove a physical button or headset identity.
Output and input aliases are local to the process or capture. The report excludes raw addresses,
names, descriptors, credentials and media metadata by default.

To investigate the build 2308 startup pause and disconnect skip, clear the capture immediately before
the parked run. Play the same downloaded chapter through the headset throughout one ordinary car
startup and shutdown. Mark **Silence** or **Skipping** where heard. A seek reason with a matching
position jump shows a Media3 transport discontinuity. Steady player progress with a native frame or
timestamp step points toward the rendering path. If both clocks jump together, examine the reason code
and buffer state before attributing the jump. A frame step alone does not establish lost audio.
An optional second run with **Automatic headset take-back** off isolates the existing system-wide
recovery request. Keep the headset lock and focus settings fixed between runs.

Android 17 can block playback, volume and focus through background audio hardening. The app records
the OS and target API for context but Android's raw focus owner, focus result and hardening decision
are unavailable through the normal app diagnostic path. The optional private
[`capture-audio-focus.ps1`](../../scripts/device-test/capture-audio-focus.ps1) capture includes
`dumpsys audio`, AudioFlinger and the `AudioHardening` logcat tag. Start it before testing with the
screen locked when ADB is available. Raw system captures stay local.

Android 17's public written-frame counter could distinguish data submitted to AudioTrack from
the measured playback head. It is a candidate for a later diagnostic revision when BookWave compiles
against API 37. A measured frame counter provides timing evidence without changing playback or routing.

## Daily Android Auto capture

In a debug build, open **Settings → About → Advanced & diagnostics → Android Auto diagnostics** and enable
**Record car connections** once. BookWave records when its playback service observes a car-class
hands-free connection, an Android Auto controller, or projection. A hands-free-only candidate that
never reaches a controller or projection is marked `connection-unconfirmed`. Projection departure
ends a confirmed attempt, with final controller departure as the fallback when projection was never
visible. The optional Nearby devices permission enables the earlier car Bluetooth signal. Use general
Playback diagnostics alongside it when a test needs AudioTrack timing bursts or events before connection.

**Save JSON file** exports the retained attempt history. `attemptIndex` lists attempt numbers, connection
times, headset presence and failure counts. A verified held headset or an identified wearable output
counts as present. An ambiguous Bluetooth output or enabling mid-connection can yield unknown.
`failedHeadsetReconnects` lists attempt and reconnect numbers for returns that needed a subsequent Play
or went 30 seconds without verified output. Attempts that close at a connection boundary before those
30 seconds show `unverified` in their reconnect details. Exports apply this distinction to retained v2
records as well, while keeping their earlier verified recoveries.
Look up those numbers under `attempts` for the full events. The button caller identifies software and
does not identify a physical device. `headsetRecoveryMs` reports count, average, minimum and maximum
separately for automatic returns and returns after an observed Play. Timing starts at the interruption
and ends when the original headset is verified on the playing, unmuted AudioTrack, including for
an explicitly selected headset. A listener Pause or book change excludes the interrupted recovery
from those measures. Reports retain older events and show the number of older attempts excluded
from the corrected recovery totals in `legacyAttemptsExcludedFromRecoveryStats`.

The history stays in private storage until exported. It is limited to 100 attempts from 30 days and
keeps the opening events and recent events in each attempt. `droppedEvents` counts older body events
rotated out of a busy connection. **Clear car history** clears only this report and keeps an active
attempt recording with its current number. Test consecutive connects with and without a headset, a
session without physical button presses, and Play/Pause while projection is active. Compare the
verified returns to what was audible in the headset.

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

Renderer failures include bounded codec cause classifications, numeric format/error fields and
recovery flags without exception messages, codec names or media metadata. See the
[decoder/route regression and device procedure](2026-11-02-decoder-route-failure.md).

- JVM regression: cold virtual toggle vs explicit Play, car, loaded queue, warm
  state, absent Bluetooth and physical key, plus the inherited route lab tests.
- KtLint, Detekt, Android Lint, Robolectric and `verifyDebug` must pass on the exact
  combined head.
- For hardware acceptance, start and clear the capture while narration is playing. Confirm the book
  keeps playing and the saved timeline begins with fresh evidence from the same track. Check a cold
  headset Play/Pause, Android Auto Play and absence of speaker leakage while parked.
