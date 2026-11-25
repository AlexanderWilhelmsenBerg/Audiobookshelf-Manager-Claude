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
