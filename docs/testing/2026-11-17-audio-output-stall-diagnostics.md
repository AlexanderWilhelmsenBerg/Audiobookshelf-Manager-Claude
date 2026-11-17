# Audio-output stall diagnostic

PLAY-001/002, ROUTE-001/002 and SET-002. Diagnostic follow-up stacked on PR #307 at
`14e04448d5394cc3354bd24e260a0419e91a2e15` (build 2280). No playback recovery, routing protection,
progress-write policy, player/session count or Media3 focus ownership changes.

## Why this capture exists

The build-2280 second attempt had no successful Auto controller/projection connection. The corrected
take-back waited through a stale selected snapshot, requested the original headset, and received a
matching selection callback after 878 ms. Original-headset route proof passed. About 2.5 seconds later,
at the car A2DP teardown, frame consumption stopped while Media3 oscillated READY/BUFFERING at one
position. A phone-app reload created a fresh AudioTrack whose frame counter remained zero; a timeout
followed. Manual headset selection did not help. The tester restored sound by reconnecting the headset.

That is evidence of a stalled output, not proof that the platform alone caused it. System selection,
actual route identity, Media3 isPlaying and acoustic playback are separate observations. We need normal
successful captures as well as any naturally occurring failure; do not force an Auto failure.

## What is recorded

- A bounded 30-second native timing window: 120 samples at 250 ms, then a five-second cooldown.
  Callback storms cannot extend the window. Recording off performs no new diagnostic native queries.
- A main-thread snapshot of current player intent, state/suppression, route mute, numeric position and
  buffered duration, loading, generation and selection. No title, URI or profile identity is copied.
- `track-progress`: Baseline, Advancing, CounterReset or NoFrameProgress. Two seconds without head
  movement while Play is requested is a diagnostic candidate, not acoustic proof or an automatic action.
  Temporary native pauses and READY/BUFFERING changes do not restart that clock. Listener Pause, mute,
  suppression, idle, missing measurements, a new track/context/capture or counter reset start fresh evidence.
- The exact `StuckPlayerException` subtype, fixed reason name and timeout. Exception messages are excluded;
  unknown reasons remain Unknown. These safe fields survive the route-lab export allow-list.
- The first candidate pins preceding route/take-back/proof events, the latest two native samples and
  existing privacy-safe system/Bluetooth observations. A bounded post-incident tail retains later error,
  transport, track replacement and progress transitions. Repeated state events cannot evict initial context.
- JSON adds `audioRouteLab.stallIncident`; the text report includes `[first-suspected-output-stall]`.
  The same private no-backup atomic file retains both the 350-row rolling trace and at most 128 pinned
  rows, with per-row 24-hour expiry. Process recreation preserves them. Clear all removes both and rejects
  late records from the previous capture generation. Empty healthy captures have no pinned incident.

## Ordinary parked comparison

1. Install the diagnostic debug APK. In Audio Route Lab, leave **Automatic headset take-back: on** for
   the first run (the default preserves build 2280 behavior).
2. Clear all diagnostics, start recording, and start one headset. Connect the car normally, leave the
   controls alone and observe for about a minute. Turn the car off and observe again.
3. Export diagnostics even if everything worked. Baseline/Advancing and numeric frame movement provide
   the successful comparison. The snapshot records `automaticTakeBack=true/false` (unknown in builds
   without the experiment).
4. Repeat an ordinary connection with **Automatic headset take-back: off**, selected before connecting.
   This disables only automatic system-route transfer. Identity access, headset protection, manual output
   selection and existing bounded recovery remain. The debug preference persists across app recreation;
   Clear diagnostics does not change the chosen comparison mode.
5. If silence happens naturally, export before reconnecting when practical. Note what you heard and
   any button presses. The pinned incident remains useful after recovery; no failed connection is required.

The comparison control never invokes a transfer by itself. Disabling it prevents deferred transfer on
the next callback and before the platform call; an already-issued system request cannot be undone.
No broader permissions or active Bluetooth scan are added. Release/benchmark keep the existing no-op.

## Optional system capture

The app cannot read AudioFlinger or the Bluetooth stack's internal state without ADB. When a computer
capture is available, use `scripts/device-test/capture-audio-focus.ps1`; it now includes AudioFlinger
snapshots before/after and AudioTrack/AudioFlinger/audio-sink/renderer log tags. Keep the silent state
until the final snapshot if possible, then reconnect the headset. Raw files stay local and are not
uploaded automatically. A wireless link that drops can lose this host-side capture; the app's pinned
incident still works independently. No root or developer-mode dependency is added to the app.

## Regression scope

The original classifier regression fails against unchanged PR307: the Media3 timeout cause is exported
as generic IllegalStateException and loses its fixed stuck reason/timeout. New tests exercise frame
movement and a frozen head through buffering oscillations, ordinary Pause/focus/mute, new track/context,
counter reset, bounded sampling, first-context retention, log flooding, persistence, recreation, per-row
expiry, Clear/late-writer behavior, JSON and event allow-list export, service snapshot reachability and
the debug comparison control. Device acceptance and the underlying stall fix remain separate work.

Local verification: `ktlintFormat`, `verifyDebug -Pshelfplayer.warningsAsErrors=true` and
`:playback:testReleaseUnitTest` passed. Reports contain 685 app debug tests, 1,314 playback debug
tests and 1,282 playback release tests, with zero failures, errors or skips. The fixed-reason classifier
test failed against unchanged PR307 before implementation (six tests, one expected failure). The
expanded PowerShell capture script passed a parser check; it has not been run against a phone.
Exact-head CI and the diagnostic APK are recorded on the follow-up PR. Hardware acceptance is pending.
