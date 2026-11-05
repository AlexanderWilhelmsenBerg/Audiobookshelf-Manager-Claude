# Route identity and evidence foundation (issue #279)

## Ownership and identity

The live Android route is not a remembered policy row. BookWave previously keyed live
`AudioDeviceInfo` objects with `OutputDevices.outputOf(...).id`, which is based on
transport/category and product name. Two different A2DP endpoints named "Buds" were
therefore collapsed by `putIfAbsent` even though Android exposed two live handles.

`LiveOutputEndpointRegistry` now assigns each connected Android output an opaque,
process-scoped endpoint ID. Unidentified devices remain appearance-scoped. It:

- keeps distinct same-name A2DP outputs available to routing and the chooser;
- generates new IDs for unidentified appearances, even when Android recycles a handle;
- correlates A2DP handle changes only when Android supplies the same unique, valid unicast Bluetooth
  address. `BluetoothEndpointIdentity` salts its digest with a random process-private value; neither
  the address nor digest is persisted or logged. A bounded registry cache retains at most 32 logical
  tokens. Blank/malformed/placeholder addresses and other transports keep per-appearance behavior;
- revokes retained correlation on duplicate physical identities. Name/type never substitutes for identity;
- uses a random per-process namespace, preventing cold-start alias reuse;
- retains the original name-based `knownDeviceId` only as *legacy saved policy identity*;
- declines to match an unknown platform handle using just a reported name;
- never puts Bluetooth addresses or Android platform IDs into route IDs.

The same registry is injected into `AudioOutputRouter` and
`AudioTrackRouteMonitor`. A measured AudioTrack sink must resolve to a
currently observed exact endpoint. A route that cannot be resolved has no
trusted endpoint ID. Android's media-policy prediction is still available for
display and safety evaluation but is **not** physical AudioTrack proof.

Identity correlation preserves destination intent, not an old measurement. Actual-route lookup requires
the current handle, type, name and identity; an old AudioTrack handle cannot resolve a replacement even
when both represent the same physical device. An inventory gap remains unavailable. Manual/car recovery
must obtain fresh muted proof after a gap. A continuously measured healthy same-device route survives
handle churn without a preference reset, seek or pause. Return callbacks use that same healthy-route
check rather than blindly reasserting a preference. No fixed settling wait is added.

An internal route-safety pause cancels suspended recovery effects but does not clear prior heard evidence
in the existing continuity gate. It does not arm recovery by itself: the existing focus/noisy event,
car-specific boundary, six-second correlation window and generation/selection checks still apply. The
window begins at the earliest internal safety pause when one precedes the platform event; duplicate
safety callbacks cannot extend it. Fresh audible playback clears that interruption timestamp.
Real Pause, newer context and explicit Car/another output retain their existing invalidation rules.

## Trust and safety

`RouteEvidence` represents source and confidence separately: a confirmed actual
AudioTrack may authorize a particular endpoint, while media-policy inference,
listener preference and unattributed media-button input cannot. The router's
silent headset probe consumes this rule and still fails closed on duplicate
saved identities, unknown tracks, conflicting or multiple routes, and stale
user/device generation claims.

The persisted `KnownDevice` format and Room schema are unchanged. Because a
name-based Bluetooth/hearing-aid/USB policy row does not reliably identify
a physical device across a reconnect or process restart, historical
`AutoPlay` for these **unverified identities** is downgraded to safe
`ArmOnly` for automatic connection actions. Explicitly accepted category-wide
wired and car policies retain their behavior. `Never`, ArmOnly and locked
profile protections remain conservative. This intentionally preserves safety
over legacy name-based automatic playback; a future trusted persistent identity
would need an explicit permission/UX design and migration.

Nothing in this change attributes a physical headset to a Media3 media-button
request, adds headset lock or controlled handoff, changes Media3 output creation,
stores a device address, or logs new identifying hardware information.

The address is read through public [AudioDeviceInfo.getAddress()](https://developer.android.com/reference/android/media/AudioDeviceInfo#getAddress()) on API28+, with unavailable/failed
reads treated as unidentified. API26–27 retain appearance identity. No new Bluetooth permission or
scan is requested. OEM address availability and handle churn need hardware acceptance; correlation is
not a promise that Android will keep the transport or audio focus alive.

## Acceptance and tests

`LiveOutputEndpointRegistryTest` exercises two identically named A2DP endpoints,
stable identity within one connection, disconnect/reconnect with recycled OS
handles, process recreation, missing names, duplicate/unknown platform handles,
and strict route-provenance decisions. `UnverifiedDevicePolicyTest` exercises
stored AutoPlay downgrade and category-wide policy exceptions.

`BluetoothEndpointContinuityTest` exercises production inventory/guard integration through handle
changes, gaps and identity collisions. `BluetoothEndpointIdentityTest` checks process-private identity,
current-handle authentication and stale/recycled-handle rejection. `PlaybackRouteSafetyContinuityTest`
uses the real service PlayerEvents callback to distinguish internal safety pause from listener Pause.

The existing `verifyDebug` suite includes Robolectric `AudioOutputRouterThreadTest`,
route safety, car-arrival, focus/noisy, stale-intent, lifecycle and Media3 policy
tests, and the Phase 1C continuity matrix.

**Still needs physical acceptance:** proof that a real Android Auto head unit
reports the same platform handle in the live inventory and playing AudioTrack,
including OEM Bluetooth reconnect, permission denial, system media buttons,
and playback with car projection connected. CI does not prove audible routing.
