# Audio Route Lab boundaries

Ownership: **Android System & Auto** (primary), **Playback & Lifecycle** (read-only integration
review), **Test & Acceptance** (acceptance evidence), **Security & Privacy** (redaction review).

One playback owner remains `PlaybackService`. `AudioRouteLab` is an injected application
singleton with no `Player` reference, no `AudioManager` mutations, and no routing or
audio-focus commands. The existing `AudioOutputRouter` remains the sole owner of
selected/preferred route changes.

Flow: redacted `EventLog.observeWrites` (non-blocking per-write subscriber) → bounded
1024-entry queue with explicit loss counter → allow-listed `AudioRouteLabEvidence` →
bounded opt-in recorder → atomic private no-backup file and `StateFlow` →
`AudioRouteLabViewModel` → `AudioRouteLabPanel`. A real
`PlaybackService.onCreate` instantiates the recorder when the media service
launches, allowing an opted-in recording to resume after process restart.
This trace is independent of the existing in-memory Auto race capture.
The per-write subscriber avoids losing adjacent Play/Pause events when the UI
`StateFlow` coalesces; the observer enqueues only, never writes to disk on the
audio/logging thread. The recorder drains queued events on Stop, tracks overflow
as `events-dropped count=N`, and invalidates old writes on Clear via a
recording-generation fence. The file is replaced atomically; the previous
completed version remains intact if the process crashes during replacement.

Separation of evidence:
1. User's selected output (preference)
2. Connected outputs and Android policy-active indicators (prediction)
3. Media3 `isPlaying` (player state)
4. Track-level routed output (unavailable until observed)
5. Listener's manual auditory confirmation (human observation)

No section should silently substitute one evidence class for another. Route-
identity equality cannot be inferred from a MediaSession controller package,
`KeyEvent.deviceId` or raw Bluetooth A2DP product name.

Opt-in Media3 track observer: `PlayerFactory` asks `AudioTrackRouteMonitor`
for a provider only when recording is already enabled at construction.
It forwards to Media3's default AudioTrack provider, observes Android route
callbacks and releases listeners when the provider ends. For normal playback,
there is no builder provider override at all. Track evidence is advisory and
must never feed `AudioOutputRouter` or the playback resume gates.

Physical output timeline maintains `AudioDeviceInfo.id` only in process RAM,
never the incident/export file. Only aliases d1/d2 and semantic roles are
recorded, with unmatched tracks printed as `unmapped`. Short-lived disappearance
is a diagnostic event, not an automatic playback-policy change.
