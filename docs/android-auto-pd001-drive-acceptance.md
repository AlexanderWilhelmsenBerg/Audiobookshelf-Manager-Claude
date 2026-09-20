# Android Auto combined drive acceptance — PD-001 / #65

This is the compact physical check for the next real-car run. It combines the still-device-bound evidence for
#65 with #10, #34, #35, #36 and #6 so one drive can settle the surface without turning the trip into a test lab.

Record the APK commit, phone/Android version and Android Auto/head-unit version once at the start. Use two saved
profiles if practical: one unlocked and one passcode-protected.

## Before connecting

1. On the phone, start a book on the Bluetooth headset and let it advance long enough to have fresh progress.
2. Note the configured seek-back interval.
3. Have profile A active. Ensure profile B is saved; leave B locked for the first profile test.
4. Know one Series with recent playback and one Series with no playback activity.

## In the car

### Browse / artwork / invalidation — #65 + #10

- Open BookWave. The root shows **exactly Continue, Series, Authors, Profiles**, in that order. There is no
  Library or History destination.
- Open Continue, Series and Authors. Book rows have covers where BookWave has them; Series has a representative
  cover; an Author with a portrait uses it and another Author can fall back to a representative cover. Missing
  art is an ordinary host placeholder, not a broken/credential URL.
- Series with recent listening activity appear before untouched Series; among comparable rows the order is
  stable/alphabetical. Open one Series and confirm its books still follow the expected series sequence.
- Make or observe a browse-tree mutation covered by #10 (for example Continue membership/order or a Series/
  Author membership change) and confirm the visible node refreshes without disconnecting Android Auto.

### Profiles — #65

- Open Profiles. Confirm all saved profiles are recognisable and profile A is clearly marked active.
- Select locked profile B. It must **not** expose B's library. The car should report that B must be unlocked in
  BookWave on the phone.
- Unlock B on the phone, return to Android Auto and select B again. The switch should succeed; playback remains
  paused, the outgoing position is preserved, and B's remembered book may be restored only in a paused state.
- Browse Continue/Series/Authors after the switch. No profile-A title or old subscribed Series/Author child may
  remain visible. This is the physical proof of #10's hard profile-boundary invalidation.

### Player / route regressions — #34 / #35 / #36 / #6

- **#34:** while the car is the intended output, verify the Car action renders its active/selected icon state.
- **#35:** verify the standard Android Auto **Queue** button is absent. Do not look for a History replacement.
- **#36:** repeat car connection while the book is already playing in the headset. It should remain playing in
  that same headset; it must not resume on the phone speaker or car, and a deliberately paused book must stay
  paused.
- **#6:** at a clearly non-zero position, press the headset/system Previous/Back control once. It should seek
  backward by the configured BookWave interval (clamped at zero when applicable), not restart the audiobook.
  Let progress sync afterwards and confirm playback continues normally.

## Evidence

A pass/fail note for each section is enough. Capture a short screen recording/photo for browse/profile rendering
where safe, and retain BookWave logs around profile switching, #36 route/focus handling and #6 Previous/Back.
JVM tests prove what BookWave publishes; this drive proves what the projected host actually renders and invokes.
