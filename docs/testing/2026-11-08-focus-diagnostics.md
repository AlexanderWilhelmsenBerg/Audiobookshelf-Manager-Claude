# Focus interruption evidence

Requirements: PLAY-001/002, ROUTE-002 and PRODUCT_SPEC section 14.5. PR306 diagnostic continuation.
No change to playback policy, route proof, audio attributes, permissions or Media3 focus ownership.

## What the app records

`PlaybackFocusDiagnostics` is a passive listener on the service's existing ExoPlayer. It records a
monotonic timestamp, a listener-local interruption sequence and time since that interruption began.
Repeated focus notifications do not restart the diagnostic clock. User/transport pause, a media
transition, idle or playback becoming active ends correlation. A new player has a new listener.
This is diagnostic bookkeeping, never another recovery or destination owner.

| Observation | Meaning and limit |
| --- | --- |
| `loss-or-denial` | Media3 stopped PlayWhenReady for audio focus. Permanent loss and a denied focus request cannot be distinguished from this event alone. |
| `transient-suppression` | Media3 reports temporary focus suppression. For speech, duck requests can also produce this state. |
| `suppression-cleared` | Media3 no longer reports suppression; this is not a raw Android GAIN callback. |
| `playback-active` | Media3 reports isPlaying; the player may still be muted for route verification. It is not acoustic or output proof. |
| `transport-play` / `transport-paused` | A transport change during the diagnostic interval, not physical-button attribution. |

Every record says `focusEvidence=media3-derived`, `androidFocusChange=unavailable`,
`focusRequester=unknown`, `focusRequesterEvidence=not-exposed` and `audibility=not-measured`.
The pinned Media3 1.11.0 focus manager owns its Android listener internally. There is no additional
focus request, reflection hook, hidden Android API, guessed caller or replacement focus manager.
Raw Android callback type and requester cannot be promised in an ordinary app export.

The existing event log includes these records; the Audio Route Lab privacy allow-list also retains
their safe fields when capture is enabled. Normal car/route proof records supply the surrounding
context. Do not interpret `focusSequence` alone as globally unique across service recreation.

## Capture the missing system evidence

Enable BookWave's unified diagnostics, Audio Route Lab and output classifier before testing, and
leave them enabled until export. Clear old captures first when a clean test is desired.

With an authorised phone attached, run from the repository on Windows:

```powershell
./scripts/device-test/capture-audio-focus.ps1 -Seconds 120
# With more than one device attached, also supply -Serial <adb-device-serial>.
```

Begin with narration playing in one headset. When the script prints that capture is starting,
connect Auto and leave controls untouched through recovery. The script collects a bounded focus
logcat stream, before/after `dumpsys audio`, uptime and host UTC anchors in a unique ignored build
directory. It does not clear logs, start playback, change focus, select a route, install an APK,
start DHU or upload anything. Export the app diagnostics promptly afterward.

System logs can identify focus requests, raw loss/gain types and requester package/UID, depending
on the OEM. They can also contain private app/device information: keep them local and review/redact
before sharing. Empty logs do not establish that focus was retained; a dumpsys snapshot alone can
miss a short-lived owner. Use the device log timestamps to match the app export, rather than assuming
the computer and phone wall clocks agree.

Compare: original focus request/loss -> suppression -> actual focus gain if recorded -> first car
evidence -> Media3 playback-active -> actual output proof. This separates a still-blocked player from
an application recovery delay. Normal notification, user Pause, initially paused playback, car
arrival/departure and headset/car switching remain separate acceptance scenarios.

## Verification

Tests cover evidence ambiguity, transient clearance, duplicate clocks, newer transport/media intent,
recreated listeners, privacy export and real pinned Media3 callback delivery under Robolectric.
They cannot identify the focus requester on the owner's phone or establish physical audibility.
Hardware/system capture remains required before changing recovery policy.

Local validation (2026-11-08, JDK 21): formatter, strict
`verifyDebug -Pshelfplayer.warningsAsErrors=true` and `:playback:testReleaseUnitTest` passed:
3,019 debug/JVM tests and 1,183 release playback tests, zero failures/errors/skips. Ten new regressions
include three real Media3 tests. Disabling focus-loss recognition made six tests fail, including
permanent-loss and initial-request-denial delivery; the implementation was restored before the gate.
The first strict run found an implicit Java platform type in the new Robolectric fixture; declaring
the type fixed it without weakening checks. The PowerShell helper passes parser validation but has
not been exercised against a connected phone. Exact-head CI and APK status are recorded on PR306.

References: [Android focus callback](https://developer.android.com/reference/android/media/AudioManager.OnAudioFocusChangeListener),
[focus management](https://developer.android.com/media/optimize/audio-focus).
