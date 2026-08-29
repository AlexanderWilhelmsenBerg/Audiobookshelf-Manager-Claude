# ADR-0027 — The output chooser asks rather than commands

**Status:** Accepted, 2026-08-29
**Requirement:** PRODUCT_SPEC PLAY-002, and ROUTE-002 for the identity rule it borrows

## Context

The owner asked for a Bluetooth output chooser on the player card and in the Android Auto player: an icon
that opens a list of connected devices, one tap to send the book somewhere else.

Three platform facts shape what that can be.

**An app cannot switch the media route.** `AudioManager.setCommunicationDevice` governs *voice* routing and
does nothing for media. The system output switcher cannot be opened programmatically with a device
preselected. What does exist is `ExoPlayer.setPreferredAudioDevice(AudioDeviceInfo)` — present on the
interface in Media3 1.7.1, verified in the bytecode before any of this was designed — which sets the
preferred device on the underlying `AudioTrack`.

**Preferred is not chosen.** The platform honours the preference while the device is connected and
available, and silently ignores it otherwise. There is no API anywhere that answers *"which output is media
actually coming out of right now"*.

**Android Auto cannot draw an app's list.** A custom action in the player sends a command; it cannot push
the car to a browse node, and there is no submenu affordance. The only list an app can put in front of a
driver is a node in the browse tree.

## Decision

### 1. The chooser reports what was asked for, never what is happening

*Automatic* is ticked whenever nothing has been chosen — including when the system has sensibly routed to a
headset. The alternative is inferring the live route from the connected-device list, which is a guess that
would be wrong exactly when it mattered and which a listener could catch. The app knows what it requested;
it says that.

### 2. The selection is not remembered across restarts

PLAY-002: *"Playback never unexpectedly moves from headphones to the phone speaker."* A remembered speaker
choice does precisely that, weeks later, to somebody who has forgotten making it.

Remembering only *some* devices was considered and rejected: a rule that keeps headphone choices and drops
speaker ones is one a listener cannot predict, and an unpredictable routing rule on a book somebody falls
asleep to is worse than making the choice again. This is a *switch output now* control. The thing that
persists is ROUTE-002's per-device policy, which answers a different question and already has a screen.

### 3. The car's output rows are browsable, not playable

A playable row goes through `onSetMediaItems`, and answering that means handing Media3 a queue: the player
is re-set, re-prepared, and rebuffers. For a control whose entire purpose is *not* to disturb what is
playing, that is the wrong callback — product priority 1.

A **browsable** row goes through `onGetChildren`, which never touches the player. So opening an output row
*is* choosing it, silently, and the child it returns states what happened. The cost is that there is no
confirmation tap; the mitigation is that the row already in use is marked, so a driver who opens the wrong
one sees immediately and the way back is the row above.

### 4. One identity rule, shared with ROUTE-002

`OutputDevices` derives both `KnownDevice.id` (the policy list) and `AudioOutput.id` (the chooser) from the
same kind-and-name rule. Two schemes would let a listener set a policy on *Pixel Buds Pro* and pick a
different-looking row in the player, with nothing to tell them the app thinks those are two devices — the
policy would silently stop applying and there would be no symptom to report. `AudioOutputIdentityTest`
compares the two functions against each other rather than checking each separately, so drift fails.

The inherited cost is stated in ROUTE-002 and unchanged: no Bluetooth permission is requested, so two
identically named headsets share one identity.

## Consequences

- The chooser appears only when more than one output is connected, on the phone and in the car alike. A
  phone always reports its own speaker, so "one output" is the ordinary state and a menu for it answers
  nothing.
- **The Android Auto player has no output button.** The list is a browse tab, one swipe from the player,
  because no API lets a custom action open a browse node. This is the one part of the original request the
  platform does not allow as described.
- Choosing an output cannot be verified by the app. A device test has to confirm by ear.
- `AudioOutputRouter` holds an `ExoPlayer` and an `AudioManager`, so it is not reachable from a JVM test.
  What *is* tested is the identity rule and every decision the browse tree makes, through
  `AutoLibrary.Outputs` — the same narrow-interface trick `OutputDeviceWatcher.Actions` uses.
