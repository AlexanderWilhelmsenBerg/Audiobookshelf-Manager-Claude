# Headset route test

Use the phone, car, headset and BookWave playback settings you normally use. While parked with the
car off, play a book until you hear it through the headset. Open **Settings → About → Advanced &
diagnostics → Debug console → Car playback test suite** and tap **Start headset route test**.
Playback diagnostics also contains the same control.

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
