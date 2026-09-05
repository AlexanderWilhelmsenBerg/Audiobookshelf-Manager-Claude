# ADR-0027 — The output chooser asks rather than commands

**Status:** Accepted 2026-08-29; amended after device runs. **The final Android Auto routing and browse contract is ADR-0029, which supersedes this ADR's third-amendment conclusions where they differ.**
**Requirement:** PRODUCT_SPEC PLAY-002, and ROUTE-002 for the identity rule it borrows

## Context

The owner asked for a Bluetooth output chooser on the player card and in the Android Auto player: an icon
that opens a list of connected devices, one tap to send the book somewhere else.

Three platform facts shape what that can be.

**An app cannot switch the media route.** `AudioManager.setCommunicationDevice` governs *voice* routing and
does nothing for media. The system output switcher cannot be opened programmatically with a device
preselected. What does exist is `ExoPlayer.setPreferredAudioDevice(AudioDeviceInfo)`, which sets the
preferred device on the underlying `AudioTrack`.

**Preferred is not chosen.** The platform may honour or decline the preference. On API 33+ the active media
route can be read with `AudioManager.getAudioDevicesForAttributes`; below that, the request is the only
available local indication.

**Android Auto cannot draw an app's arbitrary list or custom player panel.** A custom action sends a command;
it cannot open a submenu or make the host adopt BookWave's phone theme. A list must be represented through
the media browse tree, while the player itself is rendered by Android Auto.

## Decision

### 1. Report the requested and active routes separately

The chooser reports both facts where Android exposes them. `AudioOutput.isActive` describes the platform's
route; `AudioOutputRouter.selectedId` describes BookWave's preference. A declined preference is therefore
visible rather than reported as successful.

### 2. Do not persist a selected route across restarts

PLAY-002 requires that playback not unexpectedly move from headphones to the phone speaker. A remembered
speaker preference would do exactly that after an app restart. Output selection is therefore an immediate
routing request, not a durable preference.

ROUTE-002's per-device playback policy is a separate durable concept and remains stored independently.

### 3. Output rows are browsable, not playable

A playable browse row goes through media-item selection and can rebuild the player queue. Changing an audio
route must not rebuffer or disturb the audiobook, so output rows use browse callbacks and perform only the
routing request.

### 4. One output identity rule is shared with ROUTE-002

`OutputDevices` derives `KnownDevice.id` and `AudioOutput.id` from the same kind/name rule so the device a
listener configures and the device they route to cannot silently become different identities.

## First amendment, 2026-08-29 — route observation and the always-available output node

A hardware pass showed two defects in the initial decision.

First, hiding the output control unless several devices were connected hid it in the car, where the head unit
may be the only output Android reports. The browse node therefore exists whenever it is useful and provides
an explicit empty state when no selectable outputs exist.

Second, a phone-speaker preference was declined while the UI claimed success. API 33's
`getAudioDevicesForAttributes` provides the active media route, so the UI now separates the chosen preference
from where audio is actually playing. Below API 33 that observation is unavailable.

`setPreferredAudioDevice` affects BookWave's own audio track only; it does not route navigation or another
application's audio. The public API also accepts one preferred device, not an app-defined multi-output set.

## Second amendment, 2026-08-29 — Android Auto can have an action, just not a device submenu

A second car pass showed that the earlier conclusion "Android Auto cannot have an output button" was too
broad. Media3 custom actions do reach legacy/Android Auto controller layouts, so BookWave can publish an
action on the player.

What BookWave cannot control is where a head unit places that action or make it open an app-defined output
submenu. The browse tree remains the list surface.

The same Media3 compatibility state may also feed the phone notification, so an action published for car
compatibility may appear there as well. The app must not break the phone notification in an attempt to force
a car-only layout that Media3/Android Auto does not guarantee.

## Third amendment, 2026-09-04 — retired by ADR-0029

The whole-list cycle proved unsafe in a car because it included the phone speaker. The first replacement
design introduced separate Car and Headset roles plus an opt-in `keepSoundInHeadset` setting.

That opt-in form was subsequently simplified while PR #78 was implemented and tested. **ADR-0029 is the
authoritative final decision:**

- Car releases BookWave's preferred device and returns routing to Android/Automatic.
- Headset cycles only headset candidates; the phone speaker is not a BookWave Android Auto destination.
- An already-active headset is preserved automatically when a car arrives; this is normal routing behaviour,
  not a persisted setting.
- Classic Bluetooth A2DP is represented as semantically ambiguous instead of being assumed to be a headset.
- Android Auto uses the stable Continue / Chapters / History / Library root described in ADR-0029.

Keeping this short retired section is deliberate: it preserves why the PR contains `AudioOutputRoles` and
`HeadsetHold`, while ADR-0029 prevents the temporary setting-based design from being mistaken for current
product behaviour.