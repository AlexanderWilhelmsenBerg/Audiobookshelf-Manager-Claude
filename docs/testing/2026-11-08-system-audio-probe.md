# Public Bluetooth and system audio evidence

Requirements: PLAY-002, ROUTE-002, PRODUCT_SPEC section 14.5. PR306 continuation, diagnostic-only.

## Purpose and limits

Distinguish a Bluetooth profile connection, an Android audio endpoint and actual BookWave output.
These are different facts. A new output handle may represent the retained peer, a different peer,
or an endpoint whose identity Android does not expose. The probe observes these differences without
changing the registry, selecting an output or authorizing Play. It cannot supply a global audio-focus
owner, identify arbitrary physical media buttons or keep playing through platform focus loss.

`OutputClassifier` owns the probe's capture lifetime. Unified diagnostics already starts/stops this
classifier and Clear all removes its rows. No new settings, permissions, persisted identity or player
are introduced. Nearby Devices / BLUETOOTH_CONNECT is still optional: absent permission reports
`permission-missing`; audio-mode and public playback observations remain available. After granting
permission, refresh or restart capture to obtain fresh profile observations. Permission is checked
again on each sample; denial clears profile proxies and yields no endpoint identity.

## Evidence

| Record | Meaning | Not established |
| --- | --- | --- |
| `bluetooth-profile` | Public A2DP/HFP and API33+ LE Audio connected-peer snapshots, including pending/unavailable/read-failed states | Physical audibility, a car role, or focus ownership |
| `bluetooth-peer` | Capture-local peer alias, A2DP streaming flag where available, current endpoint match count | The originating button, which app is streaming, or permission to resume |
| `endpoint-system-evidence` | A2DP address capability and exact relation to the selected or last-heard retained identity | A usable AudioTrack or a successful handoff |
| `system-audio` | Audio mode, visible playback-configuration count and callback availability | Player/package identity or focus requester; both remain explicitly unavailable |

`targetMatch` is `matches`, `different`, `ambiguous`, `unavailable` or `no-target`. Only unique exact
process-private identity matches qualify as `matches`; there is no name or single-device fallback.
The diagnostic target is read from existing selection/heard owners for each snapshot. The probe never
retains another playback intent. LE profile observation does not extend A2DP endpoint correlation to
LE addresses, groups or hearing aids. Those routing capabilities remain unchanged.

Bluetooth profile proxies query Android's existing service; they do not open a Sony/vendor socket,
scan, pair, connect a device or set an active device. Protected Bluetooth broadcasts only wake a fresh
query; their extras do not become evidence. Queued callbacks are fenced by capture lifetime, callbacks
are coalesced on main, and all observers/proxies are released on stop. There is no timed polling loop.
Profile reads are bounded to 32 peers and alias memory to 64 keys. Exports contain no address, name,
private digest, native handle, media metadata or raw exception message. Existing classifier output
aliases join these rows to route inventory; monotonic timestamps join them to focus/track events.

## Regression and acceptance

Regression coverage includes exact identity across a changed handle, different/redacted/duplicate
identity, permission denial and revocation, profile read failures, late service callbacks, older API
fallback, real AudioPlaybackCallback delivery, no focus acquisition, and unified capture/clear reachability.
The live endpoint registry remains unchanged after probing. Counterfactual verification removes the
classifier's probe start hook and must fail the unified-capture reachability assertion.

Local verification (2026-11-08, JDK21): `ktlintFormat`, strict
`verifyDebug -Pshelfplayer.warningsAsErrors=true` and `:playback:testReleaseUnitTest` passed.
Reports contain 3,044 debug/JVM and 1,208 release playback tests, zero failures/errors/skips, including
twelve new regressions. Removing the production start hook made the unified-capture assertion fail;
the hook was restored before the full gate. Initial checks found a test string assertion, redundant
API26 guards and SDK34 fixture annotations; these were corrected without changing production lint
thresholds, adding permissions or weakening assertions. Exact-head CI/APK results belong in PR306.

For stationary hardware acceptance: enable Nearby Devices if desired, clear diagnostics, enable unified
capture, play in one headset, connect Auto and leave controls untouched until recovery. Export promptly.
Compare peer aliases and `targetMatch` around endpoint disappearance/reappearance, alongside profile
streaming, audio mode, Media3 loss/suppression and actual-track proof. Repeat with permission denied,
explicit Pause during connection, two headsets and capture off. Verify routing remains unchanged and
that Clear all removes probe rows. These tests are pending; unit tests do not establish physical audio.

Public APIs cannot replace authorized ADB/system evidence when the missing fact is the focus requester.
Use [the existing focus capture procedure](2026-11-08-focus-diagnostics.md) for that separate question.

References: [Bluetooth profiles](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getProfileProxy(android.content.Context,android.bluetooth.BluetoothProfile.ServiceListener,int)),
[A2DP streaming observation](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp#isA2dpPlaying(android.bluetooth.BluetoothDevice)),
[AudioManager callbacks](https://developer.android.com/reference/android/media/AudioManager),
[focus ownership](https://developer.android.com/media/optimize/audio-focus).
