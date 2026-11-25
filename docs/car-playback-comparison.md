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
3. With the car off again, tap **B · Hold the playing headset** and repeat the cycle. Use the same focus setting and grant Nearby devices access for exact Bluetooth headset identification. The JSON records `headset-armed` when B measures the playing headset, `hold-started` if it intervenes, and the proof outcome. Missing identity or car evidence uses the usual guard.
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

### Parked tests for manual control

Change one setting at a time. Use case A throughout, confirm the book is audible before connecting, and restore the original settings between tests.

**2026 Toyota bZ4X with built-in wireless Android Auto.** On the parked Toyota display, look under Settings → Bluetooth & Devices → your Galaxy. Toyota documents separate controls for the phone, media and Android Auto, with an important interaction. Enabling projection changes which conventional Bluetooth functions can be selected. Turning Android Auto off can enable the phone function. The dashboard's media switch may therefore disappear or change when projection starts. A phone-side Media audio setting is a different control. Toyota Norway confirms built-in wireless Android Auto. The control behavior is documented in Toyota's bZ4X multimedia manual for the Japanese market; Norwegian labels and installed firmware can differ. [Toyota Norway bZ4X equipment](https://www.toyota.no/nybil/bz4x/bz4x-modellgrader) · [bZ4X Bluetooth settings and interactions](https://manual.toyota.jp/bz4x/2510/bev/ja_JP/contents/whr1675855174146.php) · [Toyota Norway manual selector](https://www.toyota.no/customer/manuals)

For the most informative Toyota run, first turn **Use for Android Auto** off while parked. Check the resulting phone and media switch positions. With the car off, start BookWave in the headset, then start the car and wait on Toyota's native screen until Bluetooth settles. Log what you hear. Next enable **Use for Android Auto** for that same phone and log the second transition separately. Opening the Toyota home screen while projection is already running would leave the connection active. On a separate run restore normal wireless startup, change only the Galaxy's car Media audio setting, and test whether the interruption disappears while maps and a deliberately started car media app still work. [Toyota wireless projection procedure](https://manual.toyota.jp/bz4x/2607/bev/ja_JP/contents/qla1730367349199.php)

1. **Disable Media audio on the paired car only.** On the Galaxy, open Settings → Connections → Bluetooth → gear beside the car. If offered, turn off Audio or Media audio and leave Calls enabled. Leave phone Bluetooth and Wi-Fi enabled. Start wireless projection, verify the map and controls work, and listen through connect and disconnect. Also deliberately play another media app in the car if that is required for daily use. Check whether the car re-enables the switch on reconnect. This experiment targets the car's observed A2DP claim. Some head units may ignore the choice, lose media functionality or refuse projection. [Google Bluetooth profile settings](https://support.google.com/android/answer/9769184) · [Wireless setup](https://support.google.com/androidauto/answer/6348029)
2. **Try Samsung Separate app sound.** Under Settings → Sounds and vibration → Separate app sound, select BookWave and Bluetooth as its output. Confirm which physical device is selected in Quick panel → Media output, with the car as the main destination if available. Samsung requires the separate and main outputs to differ. Test whether the headset survives the initial switch and whether car media remains usable. A generic Bluetooth choice can follow the newly active Bluetooth device. Samsung does not promise that this feature overrides projection priority. [Separate app sound](https://www.samsung.com/us/support/answer/ANS10003294/) · [Galaxy Media output](https://www.samsung.com/us/support/answer/ANS10001355/)
3. **Separate Bluetooth arrival from dashboard launch.** If the head unit offers manual projection launch, start the car, wait with the dashboard still on its native screen, then launch Android Auto from its icon. Mark when the sound changes. A pause before tapping the icon implicates Bluetooth arrival. If the headset is listed in Quick panel → Media output after projection, select it once and check whether playback stays there. This tests manual recovery. The dashboard and available choices vary by car. [Google's dashboard launch procedure](https://support.google.com/androidauto/answer/6348029) · [Galaxy Media output](https://www.samsung.com/us/support/answer/ANS10001355/)

For an uninterrupted pass, require continuous audible speech, wireless projection working, and the same headset as actual output through arrival and departure. A pause followed by a successful headset return is a recovery result. If car-media suppression preserves the headset, preventing the initial A2DP takeover is the useful lever. If both devices remain offered but the preference changes, test an explicit user transfer. If the exact headset becomes unavailable under all settings, preserve position and resume only after exact-headset proof. Another player or a larger buffer cannot deliver audio to an unavailable destination.
