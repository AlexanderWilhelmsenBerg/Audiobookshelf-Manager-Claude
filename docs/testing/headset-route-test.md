# Headset route test

For automatic daily recording and selective drive sharing, use **Android Auto diagnostics → Record
car connections**. The [drive-history procedure](unified-diagnostics.md#daily-android-auto-capture)
includes the native outputs and the disconnect observation. Use the manual test below for numbered
listener notes and optional Perfetto triggers.

To compare the passive recorder with build 2321, use the same headset and settings. A recording
with spoken numbers makes a missing or repeated passage easier to identify. While parked, listen
through a normal connection and disconnect. Note any silence, repeated number, missing number
or audio heard in the car. Share the drive after **Finishing disconnect capture** clears.

Use the phone, car, headset and BookWave playback settings you normally use. While parked with the
car off, play a book until you hear it through the headset. Open **Settings → About → Advanced &
diagnostics → Debug console → Car playback test suite**. Playback diagnostics also contains the
same controls.

### Compare headset output settings

Finish any previous run and pause the book with the verified headset still connected. Choose one
case and tap **Apply to paused book**. Check the active test label, press Play, listen for narration
in the headset, then tap **Start headset route test**. Repeat with the same book and headset.

| Comparison | Cases | Settings shared by both cases | What to listen for |
| --- | --- | --- | --- |
| Car disconnect | 500 ms and 1,000 ms | Normal playback capture, automatic headset take-back off | At car shutdown, listen for silence, repeated speech or a jump forward. |
| Car connection | Take-back off and take-back on | 500 ms buffer, normal playback capture | At car startup, listen for the gap until narration returns to the same headset. |
| Car connection · early headset take-back | Early take-back · 500 ms and Early take-back · 1,000 ms | Normal capture, identical early take-back, same headset | Does the extra queued audio help you hear uninterrupted narration when the car starts? |

The buffer target is the phone's decoded PCM queue. Take-back on uses the existing Android 14+
system transfer experiment and requires Nearby devices access. Each comparison takes precedence over
the saved take-back switch while active. Open **Other output tests**, select **Normal capture and buffer** and apply it to
restore normal playback and the saved switch. A new service starts in normal mode. The earlier
capture-blocking and 250 ms cases are also available. Blocking capture can disable internal-audio
recording and live captions for BookWave.

The two early cases require Android 15 or later and Nearby devices access. Use **Early take-back ·
500 ms** first, finish the run, then repeat with **Early take-back · 1,000 ms**. For a timing
comparison at the same buffer size use the existing **Take-back on** 500 ms case. No phone or car
routing settings need to change. Each car-class hands-free connection opens one 30-second window.
Once the verified headset becomes transferable and BookWave observes displacement or that headset's
A2DP stream stopping, it can request one system transfer. Android decides whether to honor it.
The test records these separate milestones:

| JSON outcome | What the timestamp measures |
| --- | --- |
| `Transferable` | First observation of the reference headset in the system controller's transferable list, including a fresh offer if the route disappears and returns. |
| `Requested` | BookWave issued the transfer request. |
| `Selected` | Android selected the requested route. |
| `TrackReturned` | BookWave measured its playing AudioTrack on the reference headset after displacement. |
| `StreamStopped` / `StreamReturned` | Android reported the matching physical headset's A2DP streaming state. The listener's Connect note records when narration was audible. |

In a drive export these appear as `headset-take-back` rows with `stage=Early` and milliseconds
since arming. A manual run also stores `early-take-back-…` events with UTC and boot-clock timestamps.
An absent `Requested` can be informative. Compare the offer, stream state, and route proof.

Repeat the same connection and disconnect for each case. Listen with the screen off for several
minutes before connecting, using your usual speed and EQ. Try a known spoken sequence to identify
a missing or repeated number. Also check deliberate **Play in car**, a real Pause, and unplugging
the playing headset. Wireless projection alone must leave a healthy headset playing. The position
correction is active in every case.

Each manual run saves its starting output settings and a final sample. Each recorded drive includes
`headset-output-test` events on opening, on a test change, at departure and after the observation
window. Compare the case, requested capture policy, effective take-back switch and native capture
policy. Divide native buffer frames by sample rate to obtain the effective buffer duration in
seconds. Each PCM target respects Android’s device minimum. Compare underrun counts only within the
same track epoch. Missing track readings are marked unavailable.

1. Start the car normally and let wireless Android Auto connect. Turn off the car and listen through
   disconnection. Mark what you heard for **Connect** and **Disconnect**. A recovery after a gap
   counts as an interruption.
2. Tap **Finish run**. Repeat with the same book for another run if desired. Each start gets a new ID.
   After the last run, stop capture and tap **Save JSON**. ADB and a system trace are optional.

## Reading the export

Find the run with `caseId = HeadsetRoute` under `playbackTestSuite.runs`. Its `nativeRouteSamples`
array contains paired reads of the current AudioTrack. The playing headset you hear at the start
identifies the reference output. Each side has a status and a list of outputs. Run-local aliases
such as `out1` identify native ports. A Bluetooth port can acquire a new alias when Android
recreates it. The alias does not identify a physical headset across reconnections.

| Field | How to read it |
| --- | --- |
| `preferred` | Native `getPreferredDevice()` readback. `default` means the track has no explicit preference. A preference can still name a port after it stops receiving audio. |
| `actual` | Native `getRoutedDevices()` on Android 16 or later, or `getRoutedDevice()` on earlier versions. `not-playing`, `unreported`, `no-track` and `query-failed` are distinct. |
| `sequence`, `trackEpoch`, `elapsedRealtimeNs`, `readEndElapsedRealtimeNs` | Ordered observations, track generation and boot-clock bounds around both reads. Compare observations on the same track. |
| `change` | `actual` or `preferred` identifies the first difference observed since the preceding reading. `both` leaves their order unresolved. A new track starts a new comparison. |
| `source` | Run baseline, periodic observation, track lifecycle, car hands-free connection when reported, native routing callback, before/after a Media3 preference write, or a playback safety boundary. |

For example, if the preferred alias stays `out1` while the actual output becomes the speaker,
Android displaced the stream while the track retained its preference. If the preference first
becomes `default` while the headset is still the actual output, examine the preceding preference
write or track replacement. A native reading establishes the OS route at observation time. Listener
notes establish what was audible.

The probe samples every 100 ms during a run and records changes, callback boundaries and a
five-second heartbeat. Events between reads can be missed. Each run keeps its first sample and a
bounded recent history. Check `discardedNativeRouteSamples` and sequence gaps before inferring order.
Existing comparison runs from earlier builds remain readable in the same export.

For a matching system trace, optionally arm `python scripts/device-test/car-system-trace.py --serial
SERIAL arm --seconds 300 --scheduler` before the run. Starting the test signals Perfetto. Use the
script's `pull` action with the printed filename afterwards. `arm --on-incident` keeps a rolling
window for a single incident. Use the run ID and boot-clock timestamps to align the files.

## Reduce a drive capture on the computer

Place the selected BookWave drive or test exports (`bookwave*.json`), `btsnoop_hci.log`, its
optional `.last` rotation, and any `*.perfetto-trace` files in one folder. Copy
`scripts/device-test/summarize-drive-traces.py` into that folder, open PowerShell there and run:

```powershell
python .\summarize-drive-traces.py
```

Share **bookwave-drive-summary.json**. The script reads the top-level folder and leaves the original
captures available for deeper analysis. It uses Python 3.11+ and a local Perfetto Trace Processor for
the system trace. It detects the executable on PATH or in the standard Perfetto prebuilt cache.
Use `--trace-processor PATH` for a different location. With only the BookWave export it still produces
a report and labels each missing source.

Each window starts three seconds before BookWave's connection or disconnect signal and ends
16 seconds after the last related connection signal, or five seconds after departure. The report
lists the case and drive/run IDs when present in the export. It preserves take-back milestones,
the largest outbound Bluetooth packet gap, accepted AVDTP suspend/start exchanges and first
outbound media after an accepted start. System evidence includes mixer changes, observations of
zero ready frames, and up to five BookWave playback-loop calls lasting at least 50 ms per window.
The hardware capture measures packets leaving the phone. Mark what you hear in the run separately.

Perfetto timestamps use the trace's wall-clock snapshots. Bluetooth time is matched against
independent BookWave and HCI connection events. If multiple offsets fit, packet timings are left
unaligned. Supply `--hci-offset-seconds N` only if you independently know the seconds to add to the
raw HCI clock to obtain UTC. The report distinguishes partial trace coverage and ambiguous streams.
