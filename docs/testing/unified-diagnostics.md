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
  item, buffer and playback state when a player is attached. `route-boundary` reads the current
  native track's sink and playback head immediately before a route-safety pause or departure
  handling. Timing rows for the next three seconds can also include the measured sink at up to
  four samples per second.
  `player-discontinuity` records the public reason code and the numeric positions on either side.
  Use the item index and book generation before comparing position deltas across rows.
- The audio-write probe keeps up to six in-memory windows with up to six seconds before and two
  seconds after a measured route change, route safety pause, confirmed Auto departure, or
  **Skipping** or **Silence** marker. Busy windows also have a row cap. Export while BookWave is
  still running. These windows join the same timeline and survive stopping the capture until
  Clear all or process exit. Use **Save JSON file** for reports that are too large to copy.
- In those windows, `audio-write` reports `ptsUs`, the offset into the buffer, the bytes accepted
  by Media3's output, and the corresponding whole PCM frames when known. Retries of a partially
  accepted buffer reuse its presentation timestamp. Encoded output reports bytes, and a missing
  timestamp is reported as null. One zero-byte row marks a run of writes that accepted no data.
  `audio-output-flush`, `audio-output-open`, `audio-output-release` and
  `audio-output-route-callback` use the same monotonic clock and track epoch as the timing rows.
  Compare accepted audio with the native playback head and measured sink before attributing an
  audible skip to a playback clock, buffer clear or output transition.
- `firstIncident` retains the first frame stall, Media3 stuck reason, or listener-marked silence
  or skipping and its context. The retained recorder rows are app private and expire after 24 hours.
- `problems` contains up to forty recent redacted warnings and errors. A sanitized `previousCrash`
  is included when present. The ordinary Event Log and Debug Console remain available for non-playback
  support.

Match `actual-track` aliases with the system output `trackAlias`. A policy prediction is advisory.
At a `RouteSafetyPause`, compare `trackRoute` with the headset's last `trackAlias` even if the output
list already dropped that alias. At departure, compare the sink and advancing `headFrames` on the
same track epoch across timing rows. An unknown or paused track supplies no measured sink.
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

For the disconnect Pause, record two parked runs through the same headset and leave **Automatic
headset take-back** off. In the first, shut the car off without touching playback controls. In the
second, deliberately press Pause on the dashboard while still connected. In both runs, check the
`input-transport` row for `caller` and `relayedKeyCaller=AndroidAuto`, then compare its order with
projection and Bluetooth disconnect events. `relayedKeyCaller` carries the explicit Pause key's
verified software source into the matching notification-controller request in the same looper turn.
The marker cannot identify who initiated the key or settle cases with interleaved transport. A direct
dashboard command can report `caller=AndroidAuto` without the relay field. Also verify Pause on the
headset and phone, which should leave the relay field absent.

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

Drive history is newest first. **With headset**, **Without headset**, and **Unknown** filter the
available drives. **Share latest drive** and **Save latest JSON** use the first visible record.
To share the first, third and fourth drives, tick those three rows, then tap **Share selected (3)**
or **Save selected JSON (3)**. **Select all shown** includes the whole filtered list. Changing the
filter clears the selection. The Android sharesheet grants read access to the selected JSON. Saving
uses the Android document picker. Both take a snapshot when tapped. Recording continues during export.

`attemptIndex` lists the exported drive numbers, connection
times, headset presence and failure counts. A verified held headset or an identified wearable output
counts as present. An ambiguous Bluetooth output or enabling mid-connection can yield unknown.
New drives retain separate observations at the first car signal and at Android Auto confirmation. A
positive headset observation at either boundary keeps the drive in **With headset**. An unconfirmed
hands-free connection and a recording interrupted by service or process shutdown have distinct states.
The recorder can observe connections while BookWave's playback service is running or bound by Auto.

Each new drive carries its own `nativeRouteSamples` from the real player. The opening and recent
samples survive trimming on longer drives. Compare `preferred`, `actual`, the track epoch, and the
native playback head frames on the same track. Ten-second arrival and five-second departure samples
complement native callbacks during the drive. A `player-discontinuity` event gives Media3's reason,
before/after positions and same-item flag. Wait until **Finishing disconnect capture** clears if
sharing a disconnect incident. An immediate export includes the available tail and labels it in
`departureObservation`. A new drive cuts the preceding observation short. No manual test, Bluetooth
settings change or USB connection is required. Older drive files keep the evidence available when
they were recorded.

In drives recorded with the flush probe, `audioSinkFlushes` lists the first call to Media3's
`AudioSink.flush()` and the first call during the five seconds after disconnect. If the first call
occurs after disconnect, it appears once with `phase = departure`. An empty array means the
recorder observed no call. Drives from older builds omit the field.

Each entry gives the caller's BookWave or Media3 class and method in `callerStack`, nearest first.
Match `elapsedRealtimeNs` and `trackEpoch` against the native route samples. `precedingWrites`
contains up to eight writes accepted by that player's AudioTrack output, oldest first. While recording
is enabled, up to one second of recent pre-connection levels can carry into a drive that starts
mid-playback. Each write reports its age at the flush, accepted bytes, and RMS and peak in dBFS
across all channels for its final 20 ms of PCM or the whole write if shorter. Zero dBFS is full scale.
`digital-silence` means every measured sample was zero. Encoded output and incomplete or invalid
PCM have explicit statuses. An empty write list means there was no eligible measurement. The probe
stores numerical levels and code symbols. Headset audibility still requires a listening observation.

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
