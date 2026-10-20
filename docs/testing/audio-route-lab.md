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
  events. Unknown log fields are never copied into persistent storage. A
  per-write subscription preserves adjacent events despite a conflated UI log
  snapshot; any bounded-queue overflow is explicitly reported with a count.
- Shows selected output **role**, connected output role counts and **policy-active**
  role counts. No name, Bluetooth address, route ID or media metadata is exported.
- Hardware media buttons record the key, action, input device ID/source, and an
  origin classification (virtual/system, unresolved or physical input unverified).
  Device-ID-to-A2DP-output matching is **not** claimed or attempted.
- Manual test markers: baseline headset A; car Bluetooth connect; Android Auto
  start; headset A/B physical Play; car Play; and human-confirmed output.
- Preserves the last 350 bounded records using `AtomicFile` in app-private
  `noBackupFilesDir` across a process crash, only while explicitly enabled. A stale incident file is removed
  when it is older than 24 hours on next instantiation. Stop retains the evidence;
  Clear disables the recorder and deletes the file.
- Copies a text report with commit/version supplied by the app, policy classifications,
  and timeline. No background upload, special permissions or microphone capture.

**Important:** The V1 `actualTrack=unavailable` output is deliberate. Policy-active
output is NOT a verified audio sink. Do not interpret `isPlaying`, preferred device
success or connected A2DP as proof that sound reached a specific headset.

## Track-level observation — opt-in experimental integration

When the recorder has already been enabled BEFORE the next `PlaybackService`
constructs its ExoPlayer, `DefaultPlayerFactory` supplies a
`ForwardingAudioOutputProvider` around Media3's default
`AudioTrackAudioOutputProvider.Builder(context).build()`. The forwarding
provider delegates audio-output creation and format support to Media3 unchanged,
then attaches a read-only Android `AudioRouting` listener to each created
`AudioTrack`. Normal sessions (no recording before player construction)
use the **original ExoPlayer.Builder path with no provider override**.

On API 36+, a playing track's `getRoutedDevices()` reports multiple output
handles; earlier API levels expose the single `getRoutedDevice()` fallback.
The report shows device aliases and roles, or explicit unknown/paused states.
The provider is released with the player and the listener is detached when
the track is replaced. Diagnostic samples **never** request a route, audio
focus, additional track, or new MediaSession. Android may report a route
not matching what a listener physically hears; human confirmation remains
authoritative for acoustic results.

**Critical:** Starting recording while the current player already exists does
not install the provider on that running player. Leave recording enabled and
restart the existing playback service naturally before the next hardware test.
Do NOT force-release the currently playing service solely to change the
diagnostic mode. The UI explicitly reports observer installation status.
This opt-in hook is experimental until tested on real hardware; check for
startup, buffering, offload or route differences from the normal path.

The Android Route Lab also tracks short-lived, process-scoped aliases
(`d1`, `d2`) for individual Android `AudioDeviceInfo.id` handles, detecting
connection loss/reappearance and cases where multiple distinct handles share
a single BookWave logical output ID. It never logs underlying IDs, addresses
or product names; aliases are reallocated after process death.

Headset A/B test buttons **arm** the *next* physical press: tap the marker
BEFORE pressing the actual headset button, within 12 seconds. Subsequent
media-button down and transport events are correlated only by elapsed
timestamps and labelled `attribution=temporal-only`; a virtual Android input
is not transformed into a verified physical headset identity.

## Technical limitations of the observer

Android API 24+ provides `AudioTrack.getRoutedDevice()`; API 36 provides
`getRoutedDevices()`, and `AudioRouting.addOnRoutingChangedListener` can observe
route changes. These readings are valid only while the track is playing.

BookWave's Media3 ExoPlayer hides its concrete `AudioTrack` behind
`DefaultAudioSink`. Inserting a new renderer, sink or provider into
`DefaultPlayerFactory` could itself change startup, offload, buffering and routing.
A green CI check validates compilation and deterministic unit tests but does
not prove byte-for-byte equivalence of the opt-in provider and default Media3
sink across offload, short tracks or concurrent outputs. The provider only
supplies stronger *platform* evidence, not guaranteed human-heard sound.
Acceptance requires stationary A/B/Auto hardware tests and comparison to
an opt-out session.

## Stationary test protocol

1. Confirm diagnostic report's `app:` commit/build before a run.
2. Start recording while parked. Mark Baseline and start listening on headset A.
3. Mark Car Bluetooth, then connect the vehicle link; confirm where sound was heard.
4. Mark Android Auto, start projection with host autoplay off; confirm route and pause.
5. Tap **Arm A Play**, then press the physical A key within 12 s, and mark where audio was heard.
6. Tap **Arm B Play**, then press the physical B key within 12 s, and mark where audio was heard.
7. Mark Car Play and press on the car head unit; note where audio went.
8. Copy the report. Stop and Clear after evidence has been saved.

Do not run these tests while driving. A green JVM/Robolectric test is not evidence
that Android 16 supplied an attributable Bluetooth source or that the sound physically
reached the expected ear.

## Verification

- JVM: privacy allow-list and route-role classification; no raw names/IDs.
- JVM/Robolectric: direct event-write subscription, rapid burst, recorder stop,
  recovered incident from a new singleton instance, clearing, and no private
  names in report.
- CI: warnings-as-errors `verifyDebug`, KtLint and static analysis.
- Hardware: verify exact build and complete route events across app process restart.
- Required follow-ups: physical AudioTrack route callback verification on API 36,
  independent headset A/B tests and provider offload comparison, plus
  full system-level media-input origin constraints without guessing A/B.

## Observer activation triage (18 October 2026)

The 2230 physical run captured `actualTrack: Not installed`, but no player-construction timestamp. Since the player is created in `PlaybackService.onCreate`, starting recording from the already-running Debug Console cannot retrofit the provider. This is the leading explanation, not yet proof of the creation order on that phone. The next build records allow-listed `observer-player-create recording=true/false`, `observer-provider-installed`, `observer-provider-fallback`, `observer-audio-track-attached`, `observer-audio-track-released-or-replaced`, and `observer-provider-released` events while recording is enabled. Absent those lifecycle events, do not interpret route absence as an Android routing result.

Park the vehicle. Start recording, then allow the playback service to terminate **naturally** (do not force-stop or reconstruct playback solely for a diagnostic). When a new player naturally starts, check the lifecycle events. If it is already active, the UI now explicitly says it is awaiting the next player. Only then test headset A physical Play, mark heard destination, and compare measured `actual-track` with the device timeline. `keyInputId=-1` remains temporal-only attribution. If `provider-installed` appears without `audio-track-attached`, investigate Media3 output type/offload before claiming a route. If `audio-track-attached` appears but only paused/no route events follow, ensure real playback started and inspect the next `onIsPlayingChanged` sample. Never interpret `d2`→`d3`→`d4` as the same headset without independently confirmed identity.

## Observer diagnostics added after build 2230

The new instrumentation records allow-listed observer creation and release, whether Media3 returns an observable AudioTrack, listener-attachment failure, first playing-track sample and route-change callback. Reporting distinguishes **provider installed**, **AudioTrack attached** and **actual route measured**; neither installation nor attachment alone proves hardware routing. A callback from a released/replaced track is discarded with a generation guard. Repeated identical route samples are suppressed. When not recording at player creation, the standard Media3 builder path remains unchanged.

For the next stationary device run, leave recording enabled across **natural** service recreation; do not terminate playback to install a diagnostic. Expect `observer-player-create recording=true`, `observer-provider-installed`, `observer-audio-track-attached`, then `observer-audio-track-playing` and `actual-track playing-to=... measured=AudioTrack` (or a precise unavailable/unknown marker). `provider-output-unobserved` means Media3 did not expose an AudioTrack to this wrapper. `audio-track-attach-failed` means listener installation failed; neither is proof of route selection. With headset A and Android Auto connected, arm A, press physical Play and immediately mark the destination heard. A separate later APK build must be approved and must match the tested CI commit.

## Hardware diagnosis — 19 October 2026, signed build 2231

The owner confirmed headset A audible at 06:24:13 UTC while the experimental track observer reported `playing-to=d3:Ambiguous measured=AudioTrack`. Android Auto arrival around 06:24:31–38 caused focus loss, recovery `Reasserted` and `Secured` despite `preferred-output-request honoured=false`, and then the explicitly requested headset preference was cleared back to `Automatic` as `d3` disappeared. At 06:24:35.513 the actual track read `playing-to=unmapped:Other`, which proves the track routed through a device the current enumerator cannot classify, **not** that it is a positively identified car route. The headset A physical Play at 06:24:50 was correlated by timestamp 1,729 ms after arming, with `keyInputId=-1`; the resumed track still reported `unmapped:Other`, and the owner marked `user-heard=Car` at 06:24:52. The problem is routing, not missing transport or a Media3 play-state failure.

Follow-up instrumentation now allows only fixed transport/type labels for otherwise unclassified platform AudioTrack outputs, including `RemoteSubmix`, `BluetoothSco`, `Hdmi`, `Telephony`, and `Earpiece`, with `Unclassified` as the safe fallback. No raw Android type number, hardware name, address, or platform ID is added. These labels cannot establish physical headset A vs B or prove car ownership. `AudioOutputRouter.refresh()` currently resets a selected logical output to `Automatic` when its ID disappears. A routing-policy change belongs to the separate PR #271 and requires explicit review; do not change that policy in this diagnostic branch.
