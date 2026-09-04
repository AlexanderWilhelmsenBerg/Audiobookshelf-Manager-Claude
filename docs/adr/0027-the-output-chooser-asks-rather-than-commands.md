# ADR-0027 — The output chooser asks rather than commands

**Status:** Accepted 2026-08-29; **amended three times**, each after a device run — see the *Amendments* at
the end. The first reverses decision 1 and the first consequence; the second reverses the claim that the
Android Auto player can have no output button; the third (2026-09-04) replaces that button's whole-list
cycle with two buttons that name a role, and adds *Keep sound in the headset*. Decisions 2, 3 and 4 stand
unchanged.
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
available, and silently ignores it otherwise. ~~There is no API anywhere that answers *"which output is media
actually coming out of right now"*.~~ **That second sentence was false and the amendment below corrects it.**

**Android Auto cannot draw an app's list.** A custom action in the player sends a command; it cannot push
the car to a browse node, and there is no submenu affordance. The only list an app can put in front of a
driver is a node in the browse tree. *(Still true. What was wrongly inferred from it — that the player can
therefore have no output **button** — is corrected in the second amendment.)*

## Decision

### 1. ~~The chooser reports what was asked for, never what is happening~~ — **reversed, see the amendment**

~~*Automatic* is ticked whenever nothing has been chosen — including when the system has sensibly routed to a
headset. The alternative is inferring the live route from the connected-device list, which is a guess that
would be wrong exactly when it mattered and which a listener could catch. The app knows what it requested;
it says that.~~

The chooser now reports **both**, from two different sources, because they can disagree and the disagreement
is the interesting fact.

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

- ~~The chooser appears only when more than one output is connected, on the phone and in the car alike. A
  phone always reports its own speaker, so "one output" is the ordinary state and a menu for it answers
  nothing.~~ **Reversed by the amendment: it appears whenever there is an output at all.**
- ~~**The Android Auto player has no output button.** The list is a browse tab, one swipe from the player,
  because no API lets a custom action open a browse node. This is the one part of the original request the
  platform does not allow as described.~~ **Reversed by the second amendment: the player has a button. What
  the button cannot do is open the list.**
- ~~Choosing an output cannot be verified by the app. A device test has to confirm by ear.~~ The app can
  verify it on API 33+ and does. A device test is still what confirms the *sound* moved, and §2.11 asks for it.
- `AudioOutputRouter` holds an `ExoPlayer` and an `AudioManager`, so it is not reachable from a JVM test.
  What *is* tested is the identity rule and every decision the browse tree makes, through
  `AutoLibrary.Outputs` — the same narrow-interface trick `OutputDeviceWatcher.Actions` uses.

---

## Amendment, 2026-08-29 — what a car and a phone speaker found

The owner ran the chooser on hardware. Three things came back, and two of them were this ADR's fault.

### The tab vanished in the car

Decision 1's consequence hid the control below two connected outputs. In the car the head unit is the
connected output — often the only one the platform reports — so the tab was absent from precisely the place
somebody would reach for it. The count was the wrong question. The control is how a listener finds out
**where the book is going** and how they move it somewhere else; one output is still a choice, and an empty
list is a sentence the node can say for itself.

**The tab and the phone's menu now appear whenever there is an output at all.** With none connected the car's
node says so in one unplayable row rather than being a blank screen a driver has to back out of.

### Choosing the phone speaker did nothing, and the app said it worked

`setPreferredDevice` was declined. That is allowed and unpreventable — it is a *preference*, `AudioTrack`
returns a boolean, and Media3's wrapper drops it. What was not acceptable is that the app reported success:
decision 1 said the tick means "what we asked for", and a listener reading a tick next to *Phone speaker*
while the sound stays in a headset is being told something untrue by an interface that knew no better.

It did not have to know no better. **`AudioManager.getAudioDevicesForAttributes(AudioAttributes)` is public
API from 33** (verified in `android-36/data/api-versions.xml`), and answers exactly the question this ADR
declared unanswerable, for the same `USAGE_MEDIA` attributes `PlayerFactory` builds the player with. The
claim was wrong when written and cost a device run to find.

So:

- `AudioOutput.isActive` now means **where media actually is**, read from the platform.
- The **chosen** id is published separately by `AudioOutputRouter.selectedId`.
- The menu draws them as two different marks: the **tick** is the choice, the **"playing here"** label is
  the route. When they disagree, the listener sees the disagreement instead of a confident lie.
- The routing log line records `asked`, `routedTo` and `honoured` — kinds only, never product names (14.5).

Below API 33 there is nothing to read, so the request stands in for the route, which is the old behaviour;
minSdk is 26, so this is a real gap on Android 8–12 and R-77 records it.

### Two questions the owner asked, answered from the API

**"Only the audiobook is sent to the headset — navigation can choose for itself."** That is already exactly
what happens, and it is a property of the mechanism rather than something this app arranges.
`setPreferredAudioDevice` sets the preference on **this app's own `AudioTrack`**. It cannot and does not
touch another app's stream, so a navigation app keeps whatever route its own attributes and the system's
policy give it — in a car, the car. Nothing here needs building; it is worth writing down because the
opposite would have been a serious defect.

**"It would be good to choose multiple outputs."** Not possible for an app. `AudioTrack.setPreferredDevice`
takes exactly one `AudioDeviceInfo`, `Player.setPreferredAudioDevice` takes exactly one, and there is no
plural setter anywhere in the public API. The platform *can* route one stream to several outputs — which is
why the route is read as a set, and why a device genuinely playing to two shows both marked — but only the
system decides that. An app can read it; it cannot ask for it.

---

## Second amendment, 2026-08-29 — the button exists; only the list was impossible

The owner ran the car again: *"on the android auto, there is still no button. On the play screen there is
only a queue button which opens the queue. There should be audio device or bluetooth icon button there to
the left of queue."*

They were right, and this ADR had talked itself out of building it. The original consequence said "the
Android Auto player has no output button", reasoning from the true fact that **no API opens a browse node
from a custom action**. That rules out the *list*. It does not rule out the *button* — and a button was
always buildable, through the same mechanism this app already uses for its skip and sleep-timer actions.

### What was verified before building it

Read out of `media3-session-1.7.1.aar`, not assumed:

- Media3 builds each legacy `PlaybackStateCompat.CustomAction` from a `CommandButton`, taking the action
  string, the display name and **`CommandButton.iconResId`**. That resource is what a head unit draws, so an
  app-supplied drawable works and no Media3 icon constant is needed.

  *Verified in 1.7.1 in `PlayerWrapper`, and re-verified in **1.11.0**, where the same three-argument
  construction has moved to `MediaSessionLegacyStub`. The fact held; the class did not, which is why this
  bullet now names the behaviour rather than the file.*
- `CommandButton.getCustomLayoutFromMediaButtonPreferences` keeps the back and forward slot buttons and then
  **only buttons whose slots contain `SLOT_OVERFLOW`**. So that slot is a requirement here, not a taste.
- `MediaSessionImpl.setMediaButtonPreferences(ControllerInfo, …)` only writes the legacy playback state when
  the controller is the *media-notification* controller. **There is no per-controller button set that gives
  the car a button the notification does not also get** — Android Auto reads the one global legacy state.

### The decision

**A button on the player screen that steps to the next output.**

- The icon is a Bluetooth rune shipped as a vector drawable, the same glyph family the phone's chooser uses.
- Its display name is the current destination — the route where the app can read one, the choice otherwise —
  so a long press and a screen reader both answer *"where is this going"*.
- One press moves to the next output; the order is `[Automatic] + connected outputs`, and *Automatic* is in
  the cycle because it is the only way back to letting the system route. `AudioOutputCycle` owns it and is a
  pure function, so the order is tested without a car.
- **The browse tab stays.** Stepping is for a driver who wants the next thing; the tab is for someone who
  wants to choose. Neither replaces the other, and the tab is the one that shows all the destinations at
  once.

Cycling was considered and rejected in the original ADR — *"a driver cannot see what the next press will
select"* — and that objection is still true. It is accepted now because the alternative turned out to be
**no button at all**, which is worse, and because the button carries its current state in its name and icon
rather than being a blind toggle.

### Two consequences, stated rather than discovered later

- **The media notification gets the button too.** Not a choice; see the third bullet above. It does the same
  thing there, and it sits in the overflow group rather than displacing a transport control.
- **Where the car puts it is the car's decision.** The button is one custom action among the app's others,
  and Android Auto lays those out itself. "To the left of queue" is what was asked for and is not something
  an app can specify; §2.11 records where it actually lands.

### One more platform gap, found while building this

There is no route-change callback. `AudioDeviceCallback` fires when a device connects or disconnects, and
the API list has no `addOnDevicesForAttributesChangedListener` to pair with the getter the first amendment
introduced (checked against `android-36/data/api-versions.xml`). So after asking for an output the app
re-reads the route once, a fraction of a second later, purely so the label stops reporting the old one. It
is best-effort by construction and affects nothing but text.

---

## Third amendment, 2026-09-04 — the cycle offered the phone speaker, so the buttons name roles instead

The owner ran the car with the button from the second amendment: *"In android auto, there is now a Bluetooth
button. Pressing it cycles through headset, phone speaker and the car. Phone speaker should not be a
choice."*

The cycle was `[Automatic] + every connected output`, and a phone always reports its own speaker. So the
control the second amendment added to help a driver could, in two presses, put a book into the phone's
speaker at speed — and cycling means the driver cannot see that coming, which is the objection the second
amendment accepted only because the alternative was no button at all.

Filtering the speaker out of the cycle was the small fix and it was rejected. It answers one destination and
leaves the reason: a cycle over a list nobody can see is a control whose next state is a guess, and the list
grows whenever the platform reports something new.

### The decision

**Two buttons that name what a listener means, replacing the one that stepped through everything.**

- **Car** — one destination, always the same one. It selects `DeviceKind.Car` where the platform reports an
  audio bus, and otherwise selects *Automatic*, which while a car is connected **is** the car: clearing the
  preferred device hands routing back to the platform, whose own answer in a car is the dashboard. That is
  the honest mapping rather than a shrug, and it is the only one available on a projected head unit that
  reports no bus of its own.
- **Headset** — a short cycle over the things a person is wearing: wired, Bluetooth, hearing aid. It steps
  and wraps when there are two, re-selects when there is one, and jumps to the first when the book is
  somewhere else entirely. Its display name is the **active** headset's own advertised name, so a long press
  and a screen reader both answer *"which earbuds"*; whether a head unit finds room to draw that text beside
  the icon is the head unit's decision and not an app's.
- **The phone speaker is not reachable from either.** Not by a filter — by construction. No button names it,
  so no press selects it, and there is no filter for a later edit to drop. The browse tab still lists every
  output including the speaker, for the listener who is parked and choosing; that is what the tab is for.
- **A button with nothing to act on is absent, not disabled.** A head unit draws a disabled custom action as
  a grey square with no explanation and a driver cannot ask it why. So the car button appears when a car is
  bound to the session or a car bus is connected, and the headset button when something wearable is.
- `AudioOutputCycle` is replaced by `AudioOutputRoles`, still a pure object and still tested without a car.

### And a setting, because the car taking the sound is not always wanted

The same report asked for it: *"have a setting in playback under headset, keep sound in headset. Which means
when connecting to car, keep playing on the headset."*

`PlaybackSettings.keepSoundInHeadset`, **off by default**, in the Playback tab immediately above *In the
car* so the two read as one story. On, it pins the route to the headset the book was already coming out of
when a car binds to the session.

Two things it will not do, and both are in `HeadsetHold` rather than in a comment. It will not move audio
**to** a headset — a headset that is connected but not playing is earbuds in a pocket, and starting to play
into a pocket because a car door opened is a worse failure than the one this prevents. And it holds nothing
at all when the remembered headset has disconnected.

The mechanism has one subtlety worth writing down: the headset is remembered **continuously**, from every
published output list, rather than read at the moment a car arrives. By then it is usually too late — the
platform moves the route as soon as the car's audio link comes up, and the honest answer to *"which headset
is the sound in"* has already become *"none"*. `HeadsetHoldTest` covers that ordering directly.

### What a device pass still has to answer

`docs/risks.md` R-103. A projected car whose audio arrives as `TYPE_BLUETOOTH_A2DP` rather than `TYPE_BUS`
is, to every public API this app may call, a Bluetooth headset — so the headset button's cycle can include
the dashboard. The fixes are a Bluetooth permission ROUTE-002 declines to ask for, or a per-device *this is
my car* mark in the ROUTE-002 list. Neither is worth building against a guess about what a car reports,
which is product priority 6, so the behaviour is recorded and the car is asked.
