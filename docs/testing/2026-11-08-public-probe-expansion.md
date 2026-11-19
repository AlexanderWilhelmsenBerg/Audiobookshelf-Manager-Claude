# Public probe expansion and research

Requirements: PLAY-001/002, ROUTE-002, PRODUCT_SPEC 14.5. Owner: Android System & Auto.
PR306 continuation from `694b8881e725`; diagnostic-only. Research checked 2026-11-08.

## Implemented scope

The existing opt-in output classifier still owns probe lifetime. No new permissions, background service,
player, focus request, Bluetooth socket, scan, device connection or routing policy is introduced.
Existing optional Nearby Devices access gates Bluetooth identity and broadcast evidence. Audio observations
remain available without it. Clear all stops capture, clears pending events and removes recorded rows.

| Evidence | Meaning and interpretation |
| --- | --- |
| `bluetooth-event` | Protected public ACL, A2DP connection/streaming, HFP connection/audio, adapter state, and API33+ LE connection broadcasts. Only action category, numeric previous/current state and a temporary peer alias are retained. |
| `eventSequence`, `observedElapsedMs` | Receipt ordering and elapsed-realtime timestamp. This is callback receipt, not the radio packet timestamp. Up to 64 queued events survive coalesced snapshot refreshes; overflow is explicitly counted. |
| `bluetooth-profile ... peers=` | Full bounded membership, not merely a count. Same-count peer replacement and removal become visible. A successful empty platform result is still not proof of a physical disconnect: some platform service failures return empty lists. |
| `sampleSequence`, `snapshotCostMs` | Group rows from one public snapshot and expose time spent querying it. Snapshots across Bluetooth/audio services are not atomic. |
| `usageContent` | Anonymous `usage/contentType:count` histogram of up to 32 visible playback configurations. It does not identify packages, focus owners, output devices, or whether sound was heard. Missing access is not reported as zero players. |
| `inputSequence`, `eventUptimeMs`, `receivedUptimeMs`, `deliveryAgeMs` | Capture-local input ordering and delivery delay in Android's key-event uptime clock. Missing, zero, negative or future event time gives unknown age. This clock excludes deep sleep and must not be directly subtracted from elapsed realtime. |
| `precedingKey`, `sinceKeyReceiptMs` | A recent preceding key observation, consumed once by a diagnostic transport observation. Explicitly `temporal-only`; it neither authorizes playback nor proves that key caused that command. Old/future/missing key timing does not correlate. |
| `caller=GoogleApp` | Distinguishes the known Google software relay from generic external software. Like BluetoothService/AndroidAuto, this describes software, not the physical headset. Package-verification status remains separate. |

The Bluetooth event adapter reads only the allow-listed extras. It does not retain whole Intents, names,
addresses, arbitrary payloads or exception strings. LE/ACL peer aliases are diagnostic correlation only;
they do not extend runtime A2DP identity/routing support to LE groups or other transports.

`BluetoothProbeEvents` holds the bounded callback queue. `SystemRouteProbeEvidence` owns capture-local
aliases. `PublicAudioActivity` summarizes public audio attributes. `MediaCommandTiming` owns only input
diagnostic timestamps. None obtains a Player, mutates the live registry, or submits a route/Play request.
Key delivery age starts at Android's KeyEvent timestamp, not the unknown time of a physical headset tap.
There is no timed polling loop. Existing callbacks trigger coalesced snapshots; observed query duration
must be inspected before considering more sampling.

## Why a connected headset can lose its endpoint

AOSP separates Bluetooth profile connections from the active device. Its
[ActiveDeviceManager](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/ActiveDeviceManager.java)
can select a newly connected device. The
[A2DP service](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/a2dp/A2dpService.java)
then notifies the audio framework of old/new active devices. An old headset can remain profile-connected
while no longer having an available media output. This mechanism is a hypothesis to check against each
phone, not proof of an OEM decision or Android Auto's proprietary startup behavior.

The public profile API exposes connected devices and streaming status. Active-device selection and the
active-device broadcast are hidden/system API in the
[BluetoothA2dp source](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothA2dp.java).
The app does not subscribe to hidden action strings or invoke hidden setters. A preferred AudioTrack
device is also not guaranteed to be the actual route. Keep current exact-route verification and Media3
focus ownership; a Bluetooth connection alone cannot authorize recovery.

## Physical input capability

The native [AVRCP device handler](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/profile/avrcp/device.cc)
has the peer address before forwarding the key. The ordinary app callback does not receive that address.
An active/streaming endpoint or a Google relay is not proof of the original sender. Phone controls,
automatic device behavior and another headset must be used as negative controls before interpreting any
new correlation. Existing one-headset fallback remains inference; the optional Sony adapter remains
device-specific and must preserve Sound Connect coexistence. This expansion does not open that adapter.

Public [vendor-specific HFP events](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset#ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
can carry a device, but are vendor AT messages, not a universal AVRCP button subscription. There is no
validated new button signal to add here. Do not add generic vendor sockets, broad payload collection,
Accessibility/notification access or a guessed address association to fill this gap.

## Other rootless probes: assessed priorities

| Probe | Value to BookWave | Decision |
| --- | --- | --- |
| Existing AudioTrack timing/underruns and actual routing | Distinguishes advancing media position from native output starvation, timestamp jumps and wrong output | Reuse `AudioTrackTimingCapture` and `AudioTrackRouteMonitor`; no second silent track. API36 plural routed-device diagnostics are a possible focused follow-up, not new dual-output policy. |
| Media3 analytics: sink/codec errors, underruns, decoder lifecycle | Correlates renderer replacement with a route transition even before a terminal player error | Follow-up if existing `PlaybackErrorDiagnostics` and track timing leave an unexplained gap. Attach to the existing player; sanitize exceptions and media metadata. |
| Power/idle/thermal state | Useful for locked-screen stops or scheduling delays | Next candidate for bounded event/context capture, using public PowerManager capabilities and older-API fallbacks. Not evidence of focus ownership. |
| Connectivity transitions | Separates streaming stalls during wireless projection from local-file route failures | Reuse `AndroidNetworkMonitor`; correlate validated/metered/lost state without SSIDs, addresses or hosts. Do not create another network-policy owner. |
| Process exit and ANR history | Distinguishes process death from paused playback | Already integrated through `CrashReporter`; preserve Clear all's historical cutoff. No new duplicate crash store or raw tombstone export. |
| Perfetto / system tracing | Shows main-thread scheduling, Binder waits and platform work during a long pause | User-started or ADB development capture; not always-on production recording. Availability varies by device and trace category. |
| Focus/Bluetooth/audio-policy dumps and HCI capture | Can expose system decisions or packet sender unavailable to the app | Rootless development evidence where the phone allows it. ADB authorization is separate from app permissions. No automatic upload or production dependency. |

The research deliberately does not turn every available signal into an observer. Add a probe when it
answers a bounded question, with a lifecycle owner, clock, privacy schema, capability status, size limit
and a negative-control test. Distinguish observations from inferences and both from playback authority.

## Rootless system capture

`scripts/device-test/capture-audio-focus.ps1` now includes optional before/after Bluetooth and audio-policy
dumps and Bluetooth/AVRCP/audio-device logcat tags, alongside existing focus logs. Unsupported optional
dumps report a warning and do not prevent focus capture. Logs may still be absent on a production phone.
An AVRCP log naming the active device must not be described as the original packet sender.

The helper only reads system state. It does not enable root, change log levels, reset logs, select routes,
start playback, install software or upload data. Raw files can contain private device/app information and
remain in the ignored local build directory. Inspect/redact before sharing. The expanded helper has not
yet been run against a connected phone.

## Verification and hardware acceptance

Regressions cover coalesced connect/disconnect delivery, previous/current state, private/unknown extras,
queue overflow/clear, equal-count peer replacement, permission revocation, anonymous playback bounds,
input delivery timing, temporal-correlation expiry and production recording/export reachability.
Disconnecting `events.record(intent)` from the real receiver made both receiver and exported-row tests
fail. The hook was restored before final verification. Final gate results belong with the exact PR SHA.

Local validation on JDK21: formatter and strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` passed
with 3,053 debug/JVM tests; `:playback:testReleaseUnitTest` passed with 1,217 release playback tests.
All reports have zero failures, errors and skips. Nine tests were added, with existing privacy/callback
tests strengthened. The strict gate initially caught missing-permission lint in a synthetic-broadcast
test; a method-local annotation documents its explicit Robolectric permission grant. Production permission
checks and lint thresholds are unchanged. The expanded PowerShell helper passes parser validation only;
no connected-phone, HCI, acoustic or Android Auto hardware acceptance is claimed.

Hardware acceptance remains pending. Clear diagnostics, enable unified capture and optional Nearby
Devices, play one headset and connect Auto without pressing controls. Compare callback sequence/time,
profile membership, endpoint identity, focus observation and actual-track proof. Separately test car Play,
headset Play, phone Play, initially paused connection, calls/notifications, headset replacement and Clear
all. For two headsets, deliberately use both and phone controls; temporal coincidence must never upgrade
an unknown physical sender. Verify Sound Connect remains usable and capture adds no audible disruption.

Additional primary references:
- [KeyEvent time base](https://developer.android.com/reference/android/view/KeyEvent#getEventTime())
- [Bluetooth profile API](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp)
- [AudioPlaybackConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackConfiguration)
- [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting)
- [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
- [Media3 AnalyticsListener](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener)
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager)
- [NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
- [On-device system tracing](https://developer.android.com/topic/performance/tracing/on-device)
