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

The buffer target is the phone's decoded PCM queue. Take-back on uses the existing Android 14+
system transfer experiment and requires Nearby devices access. Each comparison takes precedence over
the saved take-back switch while active. Open **Other output tests**, select **Normal capture and buffer** and apply it to
restore normal playback and the saved switch. A new service starts in normal mode. The earlier
capture-blocking and 250 ms cases are also available. Blocking capture can disable internal-audio
recording and live captions for BookWave.

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
