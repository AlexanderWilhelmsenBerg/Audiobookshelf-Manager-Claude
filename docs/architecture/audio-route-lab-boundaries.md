# Audio Route Lab boundaries

Ownership: **Android System & Auto** (primary), **Playback & Lifecycle** (read-only integration
review), **Test & Acceptance** (acceptance evidence), **Security & Privacy** (redaction review).

One playback owner remains `PlaybackService`. `AudioRouteLab` is an injected application
singleton with no `Player` reference, no `AudioManager` mutations, and no routing or
audio-focus commands. The existing `AudioOutputRouter` remains the sole owner of
selected/preferred route changes.

Flow: redacted `EventLog.events` → allow-listed `AudioRouteLabEvidence` →
bounded opt-in recorder → private no-backup file and `StateFlow` →
`AudioRouteLabViewModel` → `AudioRouteLabPanel`. A real
`PlaybackService.onCreate` instantiates the recorder when the media service
launches, allowing an opted-in recording to resume after process restart.
This trace is independent of the existing in-memory Auto race capture.

Separation of evidence:
1. User's selected output (preference)
2. Connected outputs and Android policy-active indicators (prediction)
3. Media3 `isPlaying` (player state)
4. Track-level routed output (unavailable until observed)
5. Listener's manual auditory confirmation (human observation)

No section should silently substitute one evidence class for another. Route-
identity equality cannot be inferred from a MediaSession controller package,
`KeyEvent.deviceId` or raw Bluetooth A2DP product name.
