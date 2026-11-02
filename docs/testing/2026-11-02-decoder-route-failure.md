# Decoder failure and output intent

Requirements: PLAY-001/002/004, ROUTE-002 and diagnostics privacy. Implementation belongs to
draft #300; combined device testing remains in #301. This does not certify hardware acceptance.

## Evidence from APK2252

The 15:06:07 UTC export records ERROR_CODE_DECODING_FAILED at 14:57:23 and 14:57:38 UTC.
The first follows RemoteSubmix enumeration and precedes the car-controller bind. It affects the
loaded local book. After a profile switch, a different streamed book fails while being prepared
paused; its media requests returned 200/206. The intervening authentication refresh succeeded.
No previous crash was recorded. The old export contains no nested codec exception, so neither a
damaged file nor a route-induced codec defect is established.

## Implemented boundaries

- Playback error logging now includes a bounded, cycle-safe cause classification, renderer/format
  numeric fields, an allowlisted audio MIME, codec implementation flags and codec error/recovery flags.
  Only the standard numeric codec diagnostic grammar is retained. Arbitrary exception messages,
  codec names, format metadata and URLs are excluded. Existing player and route-intent snapshots
  accompany the error in the normal log and unified export.
- The existing attached-player route guard immediately pauses and retires a decoding-failed renderer's
  pending probe. It restores the held narration position while muted, cancels completion work and
  marks the current transfer failed. Late proof, timeout and retired attachment callbacks cannot
  commit that transaction. The selected destination remains owned by OutputIntentOwner. Existing
  transient I/O retry intent, mute and timeout behavior remain unchanged.
- A new Android Auto Play after a player error is not discarded as duplicate route intent merely
  because Media3 retains playWhenReady=true. It uses the same Car selection and proof transaction
  as before. An explicit headset lock still prevents generic controller takeover; Pause never selects.
  Later deliberate Play uses the existing guarded prepare/freshness path. No automatic decoder retry,
  second player, focus owner, seek loop or guessed headset source is added.

## Physical-input limitation

The latest device capture still exposes virtual media keys, not a headset identifier. AOSP's
[AVRCP sender](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/42338891fb61e46b7ea096c2c529771e9f5cf838/android/app/src/com/android/bluetooth/avrcp/AvrcpTargetService.java)
constructs a generic KeyEvent. The apparent
[InputDevice Bluetooth-address accessor](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/InputDevice.java)
is hidden from the public SDK; it is not a supported additional permission solution.
Vendor/product IDs, names and descriptors do not supply a general input-to-audio-output join.
Unattributed Play retains the selected destination (PD-009). Explicit Headset/Car actions remain
the reliable destination requests. Universal headset-button switch-back is unresolved.

## Regression and hardware procedure

OutputHandoffFailureTest uses the actual router, guard, position effects and volume owner for both
destinations, suspended restoration, late messages and same-player reattachment. Its two failure
cases fail without the error listener. CarErrorTransportTest invokes the actual service with Media3
controller context and verifies error-state routing and explicit-lock protection. Existing input
callback tests continue to cover capture before default Media3 handling. PlaybackErrorDiagnosticsTest
uses Media3/framework exceptions plus hostile metadata, cyclic causes and long chains.

For the next stationary device run, clear diagnostics, start recording and play a known-good local
book on the headset. Connect Auto, record audible destination, then exercise headset Pause/Play,
Car Pause/Play and explicit Headset/Car actions. If the decoder fails, export before clearing or
restarting. Record whether a deliberate retry works, the audible destination and any skipped/repeated
narration. Separately test the streamed book and profile switch. Repeat with a second connected
headset to verify that no name-based or anonymous event selects the wrong endpoint.

The next export should distinguish nested CodecException from IllegalStateException and reveal
available transient/recoverable flags. It may still require a private Android bug report to diagnose
an OEM codec failure. Automated tests establish transaction behavior, not the original codec cause
or physical continuity.
