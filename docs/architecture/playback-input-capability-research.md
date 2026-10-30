# Media input identity and Android Auto routing

**Research checked:** 30 October 2026. **Requirements:** PLAY-002, ROUTE-001/002, SET-002 and
PRODUCT_SPEC security/privacy and diagnostics requirements. Implementation is a read-only extension of
the existing Audio Route Lab in draft #300. Exact delivered source, tests, CI and APK are pinned in
[issue #289](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6448375926).

## Observed problem and distinct identities

The owner's APK2243 test retained headset output through Auto arrival and ordinary headset Pause/Play.
Auto Pause/Play selected Car. Later headset Pause/Play resumed Car, while the explicit Headset action
returned to a verified mapped Bluetooth endpoint. The capture contained ten key down/up rows with
input ID -1, source 0 and VirtualOrSystem provenance. This is configuration-specific evidence, not
proof about every phone, headset, head unit or Android version.

Three identities must remain separate:

| Identity | Public evidence | Limit |
| --- | --- | --- |
| Input event/device | KeyEvent, InputDevice, InputManager callbacks | May be virtual, missing or indistinguishable; not an audio-output identity |
| Requesting controller | Media3 request context and controller metadata | Identifies requesting software, not necessarily the physical gesture |
| Playing audio output | Current AudioTrack route and live output appearance | Does not identify the button that requested playback or prove human-heard sound |

Changing an output identifier cannot reconstruct a source that was removed before a media key reached
the app. A stable input descriptor also supplies no general public join to an A2DP audio endpoint.

## Platform handler research

The pinned AOSP modular Bluetooth implementation's
[`AvrcpTargetService.sendMediaKeyEvent`](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/42338891fb61e46b7ea096c2c529771e9f5cf838/android/app/src/com/android/bluetooth/avrcp/AvrcpTargetService.java)
has Bluetooth-device context separately, then constructs a generic KeyEvent before dispatching it.
The examined framework
[`KeyEvent` constructor](https://android.googlesource.com/platform/frameworks/base/+/a175a5b/core/java/android/view/KeyEvent.java)
assigns the virtual keyboard ID; that constructor does not set an event source. This explains how the
observed -1/0 shape can arise without a physical InputDevice being delivered to BookWave. It is an
explanation supported by AOSP, not proof that this OEM ran that exact revision or that every virtual
event came from a headset. No Bluetooth implementation code is copied into BookWave.

The same pinned service's `setActiveDevice` path can update the platform's active A2DP peer in response
to a remote Play. That platform operation and the app's AudioTrack preference/projection routing are
different layers. The new capture compares resulting output inventory, prediction, actual-track and
session-command order; it does not query privileged Bluetooth internals or change the platform peer.

[`InputDevice`](https://developer.android.com/reference/android/view/InputDevice#getDescriptor())
documents that runtime IDs may change after reconnect/reconfiguration. Descriptors are intended to
persist, but multiple indistinguishable inputs or separate collections can share them. Descriptor
equality therefore remains a diagnostic hint. InputDevice externality is available from API29; the
diagnostic reports unknown on API26-28 instead of assuming external or internal.

[Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
describes controller-package context, including Auto and Bluetooth callers. The diagnostic classifies
known caller packages and records controller/interface versions, package-verification and trust status. An unfamiliar OEM
package stays External. Media3 trust is controller trust, not proof of a physical key origin.
A caller category derived from an unverified package remains a reported software label, not identity
authority. Proof logs include their preference epoch and transfer-request generation/selection where
available; an absent transfer request remains `none` rather than borrowing a newer request's scope.

## Android Auto control and route handling

[Android for Cars playback controls](https://developer.android.com/training/cars/media/enable-playback)
reach the media session. Phone-based Android Auto projection must not be confused with the separate
[Android Automotive OS audio/zone model](https://source.android.com/docs/automotive/audio).
BookWave keeps its existing one-player/session and Media3 audio-focus ownership. This extension changes
no destination, output preference, focus, volume, progress, sleep or resumption policy.

In the existing BookWave handler, car binding alone does not select Car. A qualifying new Auto
controller Play can select Car only through the current route policy; a generic controller cannot
supersede an explicit lock. A virtual headset Play uses the already-owned destination, while explicit
Headset/Car actions provide a destination. These are policy decisions through one OutputIntentOwner,
not physical input attribution. The diagnostic records those boundaries instead of adding a second
device handler that could compete with the owner.

Communication routing is a different use case:
[`setCommunicationDevice`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))
is documented for voice/video communication. Replacing the media route path with communication/SCO
mode is not a solution to audiobook button attribution. BookWave retains Media3's media audio path.

[`AudioRouting`](https://developer.android.com/reference/android/media/AudioRouting) distinguishes
preferred output from the actual route and restricts route measurement to a playing track. BookWave's
existing production AudioTrack observer remains the measurement source. Android's predicted media
destination, selected preference and current track route remain separately labeled.

[`TYPE_REMOTE_SUBMIX`](https://developer.android.com/reference/android/media/AudioDeviceInfo#TYPE_REMOTE_SUBMIX)
is framework mix rerouting, with uses beyond Auto. It neither uniquely identifies Auto nor names the
physical car speakers. Multiple remote-mix handles can coexist. The diagnostic now assigns aliases to
all observed output handles, including otherwise-unselectable mixes, so `trackAlias` in Output
Classifier can correlate with `actual-track` rows. This does not add these handles to the router's
selectable inventory or change `LiveOutputEndpointRegistry`/`OutputTargetProof`.

## Comparable reported issues

These reports identify useful failure surfaces, not the cause of the owner's headset-button result.
Statuses describe the research date and must be refreshed before relying on an upstream fix.

| Primary report | Relevant evidence | Applicability limit |
| --- | --- | --- |
| [Media3 #2080: observing current audio output](https://github.com/androidx/media/issues/2080) | Open/enhancement after maintainer reopening. The collaborator cautions that MediaRouter is not universally reliable and points to the actual audio sink's routed device. | Route observation, not physical key attribution; historical discussion is not a claim about every newer Media3 API. |
| [Media3 #3158: Auto bind/unbind service loop](https://github.com/androidx/media/issues/3158) | Reports lifecycle churn and legacy controller attribution. Maintainer discussion mentions an Auto companion fix being prepared. | Does not establish an installed fix or headset routing cause; later reports include non-Auto cases. |
| [Media3 #2915: playback failure on a physical Auto head unit](https://github.com/androidx/media/issues/2915) | Reports a stream failing on real head units while the emulator does not reproduce it, without a useful Logcat error. | Stream/item behavior, not evidence of this key-identity failure; reinforces the need for physical acceptance. |

## Diagnostic implementation boundaries

Audio Route Lab owns the opt-in lifecycle. `MediaInputDiagnostics` observes public input APIs only while
that lab records. `PlatformMediaInputSource` adapts InputManager; `MediaInputTimeline` tracks bounded
input appearances; `MediaInputAliases` converts descriptors to capture-local ordinals using an in-memory
random HMAC key. Raw descriptor bytes, digest and key are never exported or persisted. Names, Bluetooth
addresses, vendor/product identifiers and private media/server metadata are not requested by this input
adapter. A new capture has a new namespace; descriptor aliases are not persistent identity.

The current inventory is bounded to64 unique runtime input handles; descriptor alias storage to128
entries; oversized descriptors are unavailable rather than copied. Duplicate runtime IDs are omitted.
Unavailable APIs/permissions and missing event devices are distinguished without logging exception
text. Stop unregisters the callback, and late callbacks from an older capture cannot write into a new
one. Clear uses the existing lab clear/24-hour retention boundary.
Disk persistence uses one conflated snapshot signal, rather than one queued IO coroutine per row.
The bounded in-memory event sequence is not coalesced. A delayed signal flushes current state and
cannot replace a newer generation with a stale token or recreate an empty cleared capture.

Cold receiver entry and warm media-session delivery are labeled separately; one platform key may pass
both entry points. Normalized Play/Pause records the software caller independently. Flags, repeats and
key-held duration help explain transformations without treating temporal proximity as attribution.
Selection/book/command generations, controller departure, Car intent, route proof and position-correction
events are added to the durable allow-list. Existing output policies and physical-origin rules remain.

Every input row says `association=Unproven`. Captured equality, a Bluetooth-service caller, trust,
one connected A2DP candidate or a user test marker cannot authorize automatic physical-device transfer.
If the upstream handler has collapsed origin, the correct result of the capability investigation may
remain unsupported on that configuration. A different product policy would need an explicit decision.

## Next physical investigation

Use the [input diagnostic procedure](../testing/unified-diagnostics.md#input-and-auto-capability-capture).
Record audible destination separately from dashboard state. A private Android bug report collected
immediately after a labeled sequence may expose Bluetooth active-device and media-session dispatch
context missing from app callbacks. Such reports can contain addresses/private information and must
not be pasted into public issues. Platform evidence can explain the loss of origin without exposing a
public app API to recover it. No root access, hidden API, permission expansion or background upload is
part of this implementation.
