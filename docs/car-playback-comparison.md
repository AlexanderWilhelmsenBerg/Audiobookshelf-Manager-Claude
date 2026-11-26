# Car playback comparison for PLAY-002

Open Settings → About → Advanced & diagnostics → Debug console → Car playback test suite. The same suite is available in Playback diagnostics. Keep the car parked for setup. Start with the car off, connect one headset and leave the phone volume and connection method the same across runs.

| Case | What the listener plays | What the experiment changes |
| --- | --- | --- |
| A · Current BookWave player | The current book, using the usual Play control | Reference for the existing service, session, routing, focus settings and progress tracking |
| B · Hold the playing headset | The same book and controls as A | During car binding or projection, keep the player running silently for up to two seconds while waiting for the original headset. Restore the listening position and verify the actual output again before releasing the mute. |
| ExoPlayer | A repeating spoken count from one to forty | Media3 with a small player outside the BookWave recovery logic |
| Android MediaPlayer | The same count | Android's platform player |
| LibVLC | The same count | VideoLAN's native playback engine |
| MediaCodec + AudioTrack | The same count | Decode AAC to mono PCM before playback, then repeat it in a static AudioTrack |
| MediaPlayer · prefer starting headset | The same count | Compare with Android MediaPlayer to measure the effect of requesting the starting headset as preferred output |
| MediaPlayer · pause for focus loss | The same count | Compare with Android MediaPlayer to measure responding to focus loss. Transient loss or duck pauses the count. Gain can resume it while the headset is present. Permanent loss stops the sample and keeps the car cycle recording. |

1. Start the reference and play your book in the headset. Wait until playback is audible, start the car, wait for projection to connect, then stop the car and wait for disconnection.
2. Record what you heard under **Connect** and **Disconnect**. Multiple observations can belong to the same phase. A complete run can still be annotated until the next run starts.
3. With the car off again, tap **B · Hold the playing headset** and repeat the cycle. Keep the same focus setting and permissions. B can identify the exact Bluetooth headset when Nearby devices access is already granted. The JSON records `headset-armed` when B measures the playing headset, `hold-started` if it intervenes, and the proof outcome. Missing identity or car evidence uses the usual guard.
4. Finish the run or tap another case. A second tap on the same case allocates a fresh run ID. **Finish run** also works in the foreground notification. Finishing the reference ends observation and leaves the regular book controls in charge of playback.
5. After the last run, stop capture and choose **Save JSON**. A run also persists when the sheet closes or the screen locks.

Both real-book cases record audio-write timing alongside the existing Android AudioTrack presentation timestamps, frame counts and underruns. On an accepted write, `gapUs` measures time since the previous accepted write on that output, `delegateUs` sums time spent in the intervening write calls, `outsideWriteUs` covers time between calls and `zeroWrites` counts retries that accepted no bytes. These clocks cannot measure sound at the headset.

Under **Other player samples**, pause your book before tapping a case to begin the repeating spoken count. The four unpinned engine cases request focus at start and log subsequent callbacks. If another book starts, a sample yields. Samples stop when Android reports the starting headset removed. The platform player and AudioTrack additionally stop when they report a competing actual output. ExoPlayer and LibVLC in this harness report the sample route as `unreported`. Record **Wrong output** if you hear audio in the car or speaker.

The export includes up to 48 runs in `playbackTestSuite.runs`. Each includes its case, engine, focus policy, start/end, end reason, redacted device changes, projection, position samples and listener observations. Bounded event lists report discarded events. Timeline, incident and problem rows within a run also carry its case and run ID. A process interruption ends the run at the last observed event. A running player clock alone cannot establish what was audible.

## Recording the system side

With USB debugging authorized, use the serial of the test phone from `adb devices -l`. Run these commands from the repository. Python 3 and Android platform-tools are required. Set `--adb` before the action if adb is outside PATH.

```text
python scripts/device-test/car-system-trace.py --serial SERIAL arm --seconds 300 --scheduler
```

Save the filename printed by the command. Unplug if needed. Tap A within 15 minutes, let the book play in the headset for several seconds, then connect and disconnect the parked car. Mark what you heard after each transition. Repeat A or B inside the capture window. Each tap gets a fresh run ID. The first tap starts the system trace. Later taps mark runs inside the same recording. The phone finishes recording independently after the selected duration or 192 MiB. Finishing a suite run leaves time for further cases inside that window.

Reconnect the same phone, export the BookWave JSON as usual, and retrieve the matching trace. To finish the system capture early, use `stop` first. To repeat after expiry, arm a new file. Older app builds or devices that cannot emit the named trigger can use `arm --start-now` and unplug once recording starts.

```text
python scripts/device-test/car-system-trace.py --serial SERIAL stop
python scripts/device-test/car-system-trace.py --serial SERIAL pull FILENAME --output .
```

Open the trace locally in Perfetto. Search for `BookWave/<runId>/<caseId>/`. Align the exported run's `startElapsedRealtimeNs` and event `elapsedRealtimeNs` with the trace boot clock. Markers on queued log rows reflect when the recorder consumed them. Use the original JSON timestamps, the audio-write clock and the native audio tracks for sub-second ordering. `systemTraceTrigger` reports whether the app signalled Perfetto. Confirm actual capture via the named trigger in trace metadata and matching app markers. A missing or short file can indicate an expired arm, a size cap or an interrupted capture.

System audio, process metadata and the phone's Bluetooth producer (when advertised) share the trace. Optional scheduler data helps distinguish a runnable audio thread starved of CPU from an idle thread waiting on the sink. The local system trace can identify other running processes. App markers use event names and run IDs only. The recipe omits microphone audio, packet snooping and general logcat. Keep the raw trace local when possible and share only the relevant interval. A listener observation supplies the audible result.

For an intermittent failure, use `arm --on-incident --seconds 300 --scheduler`. The phone starts collecting immediately into a 16 MiB ring. A route-safety pause, stalled frame counter, position compensation, player error, unexpected sample stop or listener-reported problem during a run signals the incident trigger once for that run. Tap the symptom while the run is still active if only you can hear it. Perfetto retains the available lead-up and five more seconds. A busy system can shorten the prehistory. A trigger timeout without an incident can produce an empty file. Use the full-window mode when comparing multiple faults or both ends of a car connection. [Perfetto trigger modes](https://perfetto.dev/docs/concepts/config#triggers)

## Wireless Android Auto and the headset

The two November 25 runs used a Galaxy S24 Ultra, wireless projection and the same Bluetooth headset. In **both** runs the exact headset still appeared in the Bluetooth A2DP and hands-free profile lists while its media endpoint count fell to zero. Its A2DP stream stopped. The car entered A2DP, Android's audio service changed the active device, and BookWave's playing AudioTrack reported the phone speaker. The app then issued its route-safety pause. This establishes loss of the usable media path. A complete headset radio disconnect is unnecessary to explain either pause.

| Measured interval or event (local time) | A | B |
| --- | --- | --- |
| Audio service began switching the Bluetooth output | 19:53:20.396 | 19:54:18.383 |
| BookWave paused on the speaker route | 19:53:20.760 | 19:54:18.573 |
| Audio service reported headphones again | 19:53:22.679 | 19:54:21.116 |
| BookWave issued Play | 19:53:22.683 | 19:54:21.128 |
| Same-output verification completed | 19:53:22.829 | 19:54:21.201 |

B's focus loss arrived at 19:54:21.709, after Play and output verification. A dashboard Pause at departure is a separate event and has its own controller/input markers. Android's report of a speaker output alone cannot establish that anyone heard sound through the speaker.

Google documents Bluetooth pairing at wireless startup and 5 GHz Wi-Fi support for projection. These are separate stages. In the Android Bluetooth reference implementation, connecting an A2DP device can select it as active, depending on audio mode, enabled profiles and other connected devices. Samsung documents car media priority during Android Auto. This explains how the handset can keep the headset connected yet briefly expose a different output before the car controller binds. The captures identify the order and the affected peers. They do not disclose which private Samsung or head-unit rule initiated the switch. [Google setup](https://support.google.com/androidauto/answer/6348029) · [Wireless requirements](https://support.google.com/androidauto/answer/6348019) · [AOSP active-device selection](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/7aaee9915d61e3c8dc7bc81b3d4604c165a9cc5f/android/app/src/com/android/bluetooth/btservice/ActiveDeviceManager.java) · [Samsung's car routing guidance](https://www.samsung.com/us/support/troubleshoot/TSG10013031/)

### What the app can control

| Code path reviewed on the comparison branch | Consequence |
| --- | --- |
| `MeasuredHeadsetProtection.protect` and `AndroidLocalOutputAdapter.requestPreference` | BookWave identifies a playing headset, then asks Media3 for that live device. Android selects the actual route. Once the endpoint leaves the inventory, there is no live handle for the adapter to request. |
| `AudioOutputRouter`, `BookHeadsetComparison.hold` and `HeadsetRouteGuard` | B's silent two-second hold requires prior car binding or projection. In these runs the media switch preceded both. Entering the hold earlier could protect position, but cannot make a silent interval audible. Disabling the guard could instead send the book to the speaker. |
| Debug `SystemRouteHeadsetTakeBack` and `PlatformSystemRoutePort` | When the option, permissions and eligibility checks permit it, BookWave can request one system transfer to a previously verified, transferable headset. Android must advertise and accept the route. This is recovery after displacement. A transfer can also change the system's destination for other media. |
| `CarLabService` sample cases | Each sample deliberately stops if Android removes its starting output handle. Those sample stops by themselves cannot establish an engine limitation. Use the real-book case, exact-peer Bluetooth evidence and actual AudioTrack output to evaluate a stable route. |

The public audio routing API distinguishes a preferred device from an actual routed device. MediaRouter2 can offer output selection or request a transfer to an available route. Audio focus governs cooperation between players. The communication-device API serves call and communication use cases. None provides an ordinary audiobook app with an exclusive lease on a Bluetooth media endpoint across a system policy change. [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting) · [MediaRouter2](https://developer.android.com/reference/android/media/MediaRouter2) · [Audio focus](https://developer.android.com/media/optimize/audio-focus) · [AudioManager](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))

### Acceptance during ordinary wireless Android Auto use

Use the listener's existing phone, headset and car configuration. The same test applies across makes and models. A trace or debug case is optional for collecting evidence. Ordinary playback must provide the protection on its own.

1. With the car parked and off, play a familiar passage through the headset. Start the car as usual and let wireless Android Auto connect automatically. Listen through the transition without pressing a media control.
2. While speech is still playing through the headset, turn off the car as usual. Record arrival and departure separately. For each, note any silence, skipped or repeated words, sound from another output, or need to press Play.
3. Test the reverse direction separately. Start playback deliberately in the car and turn the car off. The expected result is a pause at the heard position, with the phone speaker and any connected headset silent until the listener requests playback again.

Continuous speech through the original headset at both boundaries passes the headset test. If Android temporarily withdraws that headset, record the gap even if BookWave returns to the correct word. Evaluate recovery separately. The car and phone speaker should stay silent, and audible playback may resume only on the verified original headset within the existing recovery window. A listener Pause cancels recovery. Report any skipped words or audio on another output as a failure.

The November 25 runs establish a headset interruption during arrival on one Galaxy S24 Ultra and one 2026 Toyota bZ4X using built-in wireless Android Auto. Both recovered. Gapless arrival and behavior on other hardware remain unverified.
