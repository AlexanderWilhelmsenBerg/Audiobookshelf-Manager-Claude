# Car playback comparison for PLAY-002

Open Settings → About → Advanced & diagnostics → Debug console → Car playback test suite. The same suite is available in Playback diagnostics. Keep the car parked for setup. Start with the car off, connect one headset and leave the phone volume and connection method the same across runs.

| Case | What the listener plays | What the experiment changes |
| --- | --- | --- |
| Current BookWave player | The current book, using the usual Play control | Reference for the existing service, session, routing, focus settings and progress tracking |
| ExoPlayer | A repeating spoken count from one to forty | Media3 with a small player outside the BookWave recovery logic |
| Android MediaPlayer | The same count | Android's platform player |
| LibVLC | The same count | VideoLAN's native playback engine |
| MediaCodec + AudioTrack | The same count | Decode AAC to mono PCM before playback, then repeat it in a static AudioTrack |
| MediaPlayer · prefer starting headset | The same count | Compare with Android MediaPlayer to measure the effect of requesting the starting headset as preferred output |
| MediaPlayer · pause for focus loss | The same count | Compare with Android MediaPlayer to measure responding to focus loss. Transient loss or duck pauses the count. Gain can resume it while the headset is present. Permanent loss stops the sample and keeps the car cycle recording. |

1. Start the reference and play your book in the headset. Wait until playback is audible, start the car, wait for projection to connect, then stop the car and wait for disconnection.
2. Record what you heard under **Connect** and **Disconnect**. Multiple observations can belong to the same phase. A complete run can still be annotated until the next run starts.
3. Pause your book before a sample case. Tap the case to begin the count automatically. Repeat the same car cycle. Keep listening through forty if the transition lasts longer. The return to one is a normal fixture loop.
4. Finish the run or tap another case. A second tap on the same case allocates a fresh run ID. **Finish run** also works in the foreground notification. Finishing the reference ends observation and leaves the regular book controls in charge of playback.
5. After the last run, stop capture and choose **Save JSON**. A run also persists when the sheet closes or the screen locks.

The four unpinned engine cases request focus at start and log subsequent callbacks. Android still controls routing, mixing and muting. If another book starts, a sample yields. Samples stop when Android reports the starting headset removed. The platform player and AudioTrack additionally stop when they report a competing actual output. ExoPlayer and LibVLC in this harness report the sample route as `unreported`. Record **Wrong output** if you hear audio in the car or speaker. Only the real-book reference exercises dashboard transport, audiobook progress and production recovery.

The export includes up to 48 runs in `playbackTestSuite.runs`. Each includes its case, engine, focus policy, start/end, end reason, redacted device changes, projection, position samples and listener observations. Bounded event lists report discarded events. Timeline, incident and problem rows within a run also carry its case and run ID. A process interruption ends the run at the last observed event. A running player clock alone cannot establish what was audible.
