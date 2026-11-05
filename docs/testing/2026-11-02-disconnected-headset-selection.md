# Disconnected headset selection and physical input identity

Requirements: PLAY-002, ROUTE-001/002. Feature PR300; combined device candidate PR301.

## Reproduced defect and correction

On APK2253, the WH-1000XM3 and A50 required manual selection after another headset disconnected.
Saved diagnostics show an absent but still-selected guarded endpoint and rejected deliberate Play,
with projection NotConnected. Both remained audible during subsequent locked-screen tests.
No notifications arrived; the earlier reported notification-associated stop is unresolved.
Raw device reports remain local, outside version control.

The router retained the continuity guard for every disappearance, even ordinary swaps outside Auto.
Retirement now uses the existing OutputIntentOwner and handoff effects. Positive NotConnected evidence,
absence of controllers and the projection latch, no explicit lock, and no active probe are required.
Retirement pauses the attached player, checks selection/item/context/effect scope, publishes Automatic,
clears the native preference synchronously while paused, then releases the guard. Failed preference
application retains protection. It does not select a replacement by name or start audio.

Disconnect refresh and deliberate Play both reach this path. The latter handles absence becoming known
after the disconnect callback. Active verification retains its bounded failure behavior; a later deliberate
Play may retire the stale choice after settlement. Unknown/active Auto, locks, reconnect and newer intent
remain protected. One player/session, current-track proof and Media3 focus ownership are preserved.

## Verification and device procedure

The initial five production-path regressions produced three expected failures on the old behavior;
the explicit-lock and unknown-Car controls passed. Additional tests cover service-command reachability,
native preference release before Play, and reconnect/car uncertainty/new lock during safety pause.
Final gate and exact CI results are recorded on PR300/301 and issue289. Hardware acceptance is pending.

Test two successive headset swaps with Auto disconnected, pressing Play both after disappearance and
after replacement connection. Ordinary unlocked selection should no longer require manual output choice.
Disconnect must pause; connection alone must not autoplay. Repeat with Keep audio here: an absent locked
endpoint must remain refused until explicit release. Then verify Auto arrival preserves a healthy headset,
explicit Headset/Car actions still transfer, and active/unknown Auto retains disconnected-route protection.

## Physical identifier research

All four tested headsets delivered virtual events: deviceId=-1, source=0. WF-1000XM6 used the Google app
dispatcher; AirPods Pro 3, WH-1000XM3 and A50 used Android Bluetooth. Those identify software, not hardware.
AOSP's [AVRCP sender](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/b2dfcaf71aa0342edc790966f93e75ed6743c6f7/android/app/src/com/android/bluetooth/avrcp/AvrcpTargetService.java)
knows the device internally but constructs a generic KeyEvent before dispatching it.

Real HID events can expose an [InputDevice name/descriptor](https://developer.android.com/reference/android/view/InputDevice#getDescriptor()),
but these are not guaranteed unique and have no public join to an audio endpoint. Existing diagnostics
retain genuine input evidence when present. [AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo)
and BluetoothDevice names/addresses identify outputs, not the sender of generic media events. The apparent
InputDevice Bluetooth-address accessor is hidden from the public SDK. Companion association does not restore
missing event identity. LE Audio's [media control path](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/619fafa41b7fe03ce7905422d966a59c68769f3d/android/app/src/com/android/bluetooth/mcp/MediaControlProfile.java)
also does not generally expose the physical sender to the media app.

One narrow supported exception is [ACTION_VENDOR_SPECIFIC_HEADSET_EVENT](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset#ACTION_VENDOR_SPECIFIC_HEADSET_EVENT):
it includes BluetoothDevice.EXTRA_DEVICE for vendor HFP commands, requiring BLUETOOTH_CONNECT on modern
Android. It does not broadcast every standard Play/Pause press. There is no evidence these devices emit
a documented vendor event for their media gesture. Vendor SDK support needs separate compatibility evidence.
No new permission or speculative physical-origin classifier is introduced.

Privileged HCI/ADB traces can sometimes correlate Bluetooth traffic with an address for diagnosis, but
are not a portable app capability. Saved bugreport snoops lack ACL traffic needed for AVRCP analysis.
PD-009 remains: unattributed transport retains current destination; explicit output actions select it.
