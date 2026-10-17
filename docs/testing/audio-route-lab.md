# Audio Route Lab — diagnostic-only hardware evidence (SET-004)

**Status:** draft implementation, not a headset-continuity fix. The definitive playback
policy remains in `:playback`. PR #271 must not become a dependency of this branch.

## Why

Repeated Android Auto traces reported `isPlaying=true` but a human heard audio in the
car, while `AudioManager.getAudioDevicesForAttributes()` returned either an empty
list or an unconfirmed headset preference. Media3's focus loss and noisy auto-pause
arrive before projection connects. The in-memory race capture was lost on process
death in a separate reproduction. We need durable, versioned evidence rather than
attempting another route switch based on a policy-level hint.

## V1 capability and explicit exclusions

The About → Debug Console → Audio Route Lab action is opt-in and read-only:

- Records allow-listed, pre-redacted controller, Media3, projection and route-policy
  events. Unknown log fields are never copied into persistent storage.
- Shows selected output **role**, connected output role counts and **policy-active**
  role counts. No name, Bluetooth address, route ID or media metadata is exported.
- Hardware media buttons record the key, action, input device ID/source, and an
  origin classification (virtual/system, unresolved or physical input unverified).
  Device-ID-to-A2DP-output matching is **not** claimed or attempted.
- Manual test markers: baseline headset A; car Bluetooth connect; Android Auto
  start; headset A/B physical Play; car Play; and human-confirmed output.
- Preserves the last 350 bounded records in app-private `noBackupFilesDir` across
  a process crash, only while explicitly enabled. A stale incident file is removed
  when it is older than 24 hours on next instantiation. Stop retains the evidence;
  Clear disables the recorder and deletes the file.
- Copies a text report with commit/version supplied by the app, policy classifications,
  and timeline. No background upload, special permissions or microphone capture.

**Important:** The V1 `actualTrack=unavailable` output is deliberate. Policy-active
output is NOT a verified audio sink. Do not interpret `isPlaying`, preferred device
success or connected A2DP as proof that sound reached a specific headset.

## Track-level observation — deferred to a follow-up controlled slice

Android API 24+ provides `AudioTrack.getRoutedDevice()`; API 36 provides
`getRoutedDevices()`, and `AudioRouting.addOnRoutingChangedListener` can observe
route changes. These readings are valid only while the track is playing.

BookWave's Media3 ExoPlayer hides its concrete `AudioTrack` behind
`DefaultAudioSink`. Inserting a new renderer, sink or provider into
`DefaultPlayerFactory` could itself change startup, offload, buffering and routing.
Before adding one, verify the exact Media3 1.11.0 provider contract and test creation,
replacement, release, and callback thread confinement. Only accept a read-only observer
that preserves the default output path and fails open without ever stopping playback.
Until then, surface **unavailable**, not guessed routes.

## Stationary test protocol

1. Confirm diagnostic report's `app:` commit/build before a run.
2. Start recording while parked. Mark Baseline and start listening on headset A.
3. Mark Car Bluetooth, then connect the vehicle link; confirm where sound was heard.
4. Mark Android Auto, start projection with host autoplay off; confirm route and pause.
5. Mark Headset A Play, press the actual headset key; note where audio went.
6. Mark Headset B Play with both A/B connected; note where audio went.
7. Mark Car Play and press on the car head unit; note where audio went.
8. Copy the report. Stop and Clear after evidence has been saved.

Do not run these tests while driving. A green JVM/Robolectric test is not evidence
that Android 16 supplied an attributable Bluetooth source or that the sound physically
reached the expected ear.

## Verification

- JVM: privacy allow-list and route-role classification; no raw names/IDs.
- CI: warnings-as-errors `verifyDebug`, KtLint and static analysis.
- Hardware: verify exact build and complete route events across app process restart.
- Required follow-ups: actual AudioTrack observation; strict 24-hour per-record
  lifecycle/rotation validation; comparison renderer for annotated test outcomes;
  full system-level media-input origin constraints, without guessing A/B.
