# ADR-0029 — Android Auto is a stable audiobook surface

**Status:** Accepted 2026-09-05  
**Requirements:** PLAY-001, PLAY-002, PLAY-003, ROUTE-002, LIB-002, LIB-003  
**Supersedes:** the routing and browse conclusions of ADR-0027's third amendment where they differ from this decision.  
**Partially superseded:** `docs/product-decisions.md` PD-001 (2026-09-19) replaces this ADR's Android Auto browse-home/History target with **Continue → Series → Authors → Profiles**, removes History from the car browse library, and makes safe cached browse artwork a product target. The routing, playback, media-control and host-behavior decisions in this ADR remain accepted unless separately superseded.

## Context

PR #78 began as a fix for one Android Auto output button. A car test showed why the problem was larger: a blind cycle over every Android audio device could move an audiobook through earbuds, the phone speaker and the car, while the head unit gave the driver no useful explanation of what the next press would do.

The same review also exposed a broader product question. Android Auto should not be a small copy of the phone UI. The platform owns the player chrome, theme, artwork treatment and placement of custom actions. What BookWave does control is the media session, the browse tree, the metadata attached to its items, and the meaning of its custom actions.

That makes the correct design a small number of stable destinations with predictable semantics rather than a dense dashboard.

## Decision

### 1. The root has four stable destinations

For a non-empty accessible library the Android Auto root is always, in this order:

1. **Continue**
2. **Series**
3. **Authors**
4. **Library**

The driver can learn those four positions once. Empty shelves do not cause the root itself to rearrange.

**Four is the platform's number.** Android Auto sends a root-children limit as a browser root hint and the
documentation says to expect four, so this list is at its ceiling and any change to it is a swap rather than
an addition. Nothing in Media3 enforces the hint, which makes honouring it the app's job.

**Chapters and History held positions 2 and 3 until the owner drove with it** — *"Chapter and history can be
removed from library view. Have series and author instead."* Neither was deleted. Both now lead `Library`,
where they are one tap further away and still answer honestly with nothing playing rather than showing a
blank screen. Series and Authors moved the other way, out of `Library` and onto the root, and are listed in
one place only.

`Library` therefore contains, in order: Chapters, History, then the broader discovery choices — Downloads,
Recently added, Listen again, Discover and Audio output when applicable. Series reuse BookWave's existing numeric series-order rules rather than inventing a car-specific sort.

Voice search also matches series names in addition to title, author and narrator.

### 2. Car and Headset are roles, not a cycle over devices

The old whole-list output cycle is retired.

**Car** means: release BookWave's preferred audio device and return routing to Android by selecting **Automatic**. Android Auto already owns normal car routing; trying to identify and pin a particular dashboard device is less reliable, especially for projected/wireless cars that expose their audio transport as ordinary Bluetooth A2DP.

**Headset** means: step only through headset candidates. Definite car buses and speakers are excluded. A stale Android Auto browse row naming the phone speaker is refused as well, so the speaker cannot reappear through a cached tree.

The headset action reports the confirmed route name when BookWave has one, for example `Playing on AirPods Pro 3`. The label is state; the icon/button remains the action.

### 3. An already-active headset is preserved when the car arrives

This is normal routing behaviour, not a user preference.

A headset is remembered only by being the active/explicit route first. A merely connected pair of earbuds is never selected because a car connects. If the remembered headset disconnects it is forgotten.

Classic A2DP needs an additional race guard. Projected Android Auto may move audio to an A2DP dashboard before its media controller callback arrives. If a different ambiguous A2DP route becomes active while the remembered headset is still connected and the user did not explicitly select the new route, that new route does not overwrite the remembered headset. The car callback can therefore reassert the route the audiobook was already using.

This replaces the earlier `keepSoundInHeadset` opt-in concept. There is no user-facing switch and no persistent preference for it.

### 4. Transport type and semantic role are separate facts

`TYPE_BLUETOOTH_A2DP` tells BookWave how audio is transported; it does not prove whether the endpoint is earbuds, a speaker or a projected dashboard. The model therefore represents classic A2DP as **ambiguous** instead of pretending every A2DP device is a headset.

Definite roles remain definite where Android exposes enough information: wired/BLE headset and hearing-aid routes can be headset candidates, `TYPE_BUS` can be a car, and speakers remain speakers.

This narrows the ambiguity rather than pretending to eliminate it. An earlier draft referred to that residual as `R-103`, but no R-103 row was ever registered in `docs/risks.md`; this ADR is the canonical record of the limitation rather than leaving a dangling risk reference. Without requesting additional Bluetooth identity permission or asking the user to classify a device, an arbitrary classic-A2DP endpoint cannot always be named semantically. The important safety properties do not depend on that guess: phone speaker is excluded, Car releases the preference, and the car-arrival race preserves a previously known headset.

### 5. History is navigation, not an audit log

The car's History list exists to answer "where can I go back to?". It keeps useful position decisions and remote-device movement, de-duplicates equivalent rows, and uses chapter-relative labels when chapter data exists.

Sleep-timer bookkeeping, ordinary Play entries and server-freshness diagnostics stay out of the car list. They remain valid history/diagnostic information elsewhere; they are simply poor driving controls.

### 6. Android Auto receives audiobook metadata, but Android draws it

BookWave supplies the platform with the information it can truthfully provide from cached library/session data: title, author, series and sequence in browse/resume rows, chapter rows, chapter-relative progress and whole-book progress.

Browse and resume rows carry **no** cover artwork today. `AutoLibrary.playable` accepts an `artworkUri` and no caller passes one, so `MediaMetadata.artworkUri` is null on every browse row and the head unit draws its own placeholder. Supplying it means resolving a cached cover to a URI the car's process may read, which is a content-provider question this ADR does not answer. The Now Playing screen is unaffected — its cover comes from the playback session, not from these rows.

A real-car test showed that this head unit renders only the live Media3 title and artist lines even when richer metadata is available. Audiobookshelf's `/play` response carries title and author but no series membership, so BookWave enriches a successful playback session from the **same profile's cached Room book row**. The primary series is preferred, otherwise the first membership, and its server-provided sequence is retained when present. No extra network request is made and no series value is guessed.

The live Media3 item keeps the title unchanged. Its visible artist/byline becomes `Author • Series #N` when series context exists, while `subtitle` and `albumTitle` also carry the series label for hosts that render those fields. A book outside a series keeps the existing title + author presentation. The session's `author` remains the actual author; the combined byline exists only at the Media3 presentation boundary so session sync/history are not polluted with display formatting.

Android Auto decides how that metadata is laid out. BookWave cannot make the car player inherit the phone's background theme, place a custom shadow behind the cover, or draw a bespoke metadata panel beside it. Those are host-rendered surfaces.

**#35 outcome.** The owner's projected Android Auto host was physically verified on 2026-09-19 to keep
the standard queue button and to render neither attempted History metadata link. The owner subsequently
dropped the History-on-player requirement. BookWave removes the player-side History navigation metadata and
withholds `COMMAND_GET_TIMELINE` from Media3's media-notification controller instead. Media3 uses that
controller as the platform-session proxy and, by design, does not publish a framework queue when that command
is absent, so Android Auto has no queue button. The real one-book timeline and BookWave's in-app Media3
controller remain unchanged.

### 7. One shared layout follows actual car-controller binding

Media3's compatibility state is shared by Android Auto and modern system media controls, so BookWave still
cannot publish two simultaneous layouts. Issue #38 instead changes the ordering of that one layout from the
state that matters: whether a car controller is actually bound.

With no car bound, skip back and skip forward lead and occupy the primary compact slots. While a car is
bound, Car and Headset lead and take those slots when available. Displaced actions keep overflow as their
fallback. The switch uses `CarConnections.isConnected()`; a merely present car-like audio route does not
change phone-button priority.

The back slot remains occupied in every supported state. That is a safety invariant: leaving it vacant lets
Media3 expose raw Previous again, which can reach `Player.seekToPrevious()` and restart a single-window
audiobook.

### 8. The current output is shown by lighting an action, because nothing else on the player can show it

A second device run reported that the car's player never says where the audio is going: *"the current audio output is not seen."*

Two surfaces already carry it and neither reaches a driver. The headset action's display name is `Playing on AirPods Pro 3`, and §2.11 records that this head unit does not draw custom-action labels. The `Audio output` browse list marks the live route *Playing here*, which is correct and four taps away from the player.

What the host draws on the player is the title, the byline and these two icons. Only the icons are BookWave's, so that is where the state goes: each output action has a lit variant carrying an indicator bar. `OutputButtons.onHeadset` and `onCar` are deliberately **not complements**, and this rule has been narrowed twice from the same mistake. A confirmed headset route lights Headset. Car lights only on positive evidence *of a car* — a `TYPE_BUS` route, or an unselected ambiguous A2DP route while a car controller is bound — and **both actions unlit is a legitimate state**: the phone speaker, a dock or USB sink, or a route the platform has not reported. The first version lit Car whenever Headset was false, which described phone-speaker playback as car playback; the guard that replaced it still lit Car for every route `OutputDevices.roleOf` sends to `Other`, which is every USB device, accessory, dock and HDMI sink. Both were the same error — a positive claim drawn from the absence of alternatives — and it is why the predicate now asks rather than infers.

**The byline is deliberately not used.** It is built once in `MediaItems.queueFor` from the session, so making it name the live route would mean replacing the `MediaItem` on every route change — rebuilding the media source of a playing book for a cosmetic gain, against product priority 1. A lit icon costs a republish of the button preferences, which the service already does when the route moves — **though the republish alone never reached the car, and then the route behind it turned out to be stale**, and a third device run is what found it. `MediaSession.setMediaButtonPreferences(List)` updates Media3's internal layout and dispatches to Media3 controllers, but never calls `updateLegacySessionPlaybackState`, and the legacy `PlaybackStateCompat` is where a car reads custom actions from. So the correct icon sat in the session until an unrelated player event rebuilt the state. `MediaButtonPublishing` now also publishes through `getMediaNotificationControllerInfo()`, the one public path that forces that refresh. A third device run then reported the icon still dark, and two more app-side causes came out of it: nothing re-read the *route* when a car bound — Android Auto activating an already-connected A2DP link fires no `AudioDeviceCallback`, so the decision was made from a pre-drive route — and a reported speaker could mask a reported dashboard, because `current()` took the first active output in the platform's enumeration order. `AudioOutputRouter.resettle` and a speaker-demoting `current()` fix those, and `republishOutputButtons` now logs the inputs to the decision so the next drive is conclusive rather than suggestive.

The lesson is the one §8 keeps learning, three times over: each step was true and none of them was sufficient. *The republish happens* was true; *the car sees it* did not follow. *The car sees it* became true; *the state behind it is current* did not follow.

**#34 selected-state fallback.** A 2026-09-19 physical re-test proved that changing icon, label and action
identity was still insufficient. The missing state was inside BookWave: both **Car** and generic
**Automatic** were represented as `selectedId=null`. A Car press could therefore leave the selected-id flow
unchanged and never republish the newly selected presentation. Car is now a distinct explicit semantic
destination even though both choices still clear ExoPlayer's preferred device. Explicit-intent changes
republish immediately. While a car controller is bound, that Car intent may bridge only the projected
no-route/built-in-speaker observation gap; a definite headset or known Other route vetoes it, so a dock,
USB or HDMI route cannot borrow the car glyph. #11/#36 route ownership remains authoritative for a held
headset. The separate active command id remains for host cache busting.

The minimised control bar has now been wrong in this section **three times**, and the answer is a test rather than a paragraph. The first version called the missing actions an unavoidable layout limit, reasoning from the three slots this code happened to use. The second corrected that to six — `CommandButton` does declare `SLOT_BACK_SECONDARY` and `SLOT_FORWARD_SECONDARY` — and concluded that requesting them, with `SLOT_OVERFLOW` as a fallback, put the actions in the bar on any host that would place them, leaving the rest a host contract. A device run then reported the compact player unchanged.

Both conclusions were reasoning about the wrong layer. Android Auto is served by `MediaSessionLegacyStub`, which builds its layout with `CommandButton.getCustomLayoutFromMediaButtonPreferences` — and that conversion branches on exactly three slot values: `SLOT_BACK`, `SLOT_FORWARD` and `SLOT_OVERFLOW`. The secondary slots are not tested in it anywhere. A button naming one falls through to the overflow branch; a button naming one *without* overflow is dropped entirely. It was never a host contract: the slots were discarded a layer before the host saw them.

`MediaButtonSlotConversionTest` executes that conversion from inside Media3's own package, so the claim fails a build instead of sitting in prose — the specific remedy for this section's habit.

**The owner then took the decision the third version deferred.** *"Compact player still don't show headset or car button. I need them more than seek forward and back."* The output actions therefore name the back and forward slots outright, and PLAY-007's skips name the same slots with an overflow fallback. Issue #38 later made the ordering state-dependent: outputs win while a car controller is bound; otherwise skips win so the phone keeps its compact seek controls.

Two properties are asserted rather than reasoned about: the state-dependent ordering is run through Media3's real conversion, and the back slot is occupied in every binding/action-visibility combination. If it is ever vacated, Media3 stops clearing `ACTION_SKIP_TO_PREVIOUS`, nothing in this app intercepts it, and a head unit's *previous* reaches `Player.seekToPrevious` and restarts the book.

### 9. Car lifecycle continuity is tied to measured focus-loss and controller-boundary evidence

Issue #36 is an integration/lifecycle problem, not a general “resume after focus loss” policy.

The 2026-09-19 physical drives established the arrival ordering on the tested projected-Android-Auto setup:

1. the current-generation book was actively advancing through an owned Bluetooth headset;
2. Media3 changed `playWhenReady` to false with `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS`;
3. the first Android Auto controller bound two to four seconds later;
4. Android could temporarily remove the same A2DP endpoint from its live device list and clear the preferred
   route back to Automatic before returning it;
5. BookWave could safely issue Play only after that exact headset was present and reasserted again.

The 2026-09-20 physical retest adds a second lifecycle fact: leaving/stopping the car can also stop playback.
Before this change the disconnect callback merely decremented `CarConnections` and republished buttons. A focus
loss observed while the car was already connected was therefore captured by an arrival-only gate and had no
matching departure transition.

BookWave keeps ExoPlayer/Media3 audio-focus management enabled. Android recommends `setAudioAttributes(...,
true)` for ExoPlayer focus ownership, and an audiobook is speech content that should yield focus rather than
continuously fight another owner. The measured first car-specific signal arrives after the focus loss, so the
implementation does **not** claim it can safely eliminate the whole two-to-four-second pre-bind interruption.
Doing so from the focus callback alone would recreate the rejected generic-focus-resume design and could resume
for calls, navigation, another media app, the phone speaker, or a car route. The earliest safe arrival recovery
is the first matching 0→1 car-controller bind.

#### Arrival state machine

`playing exact headset`
→ `audioFocusLoss`
→ `arrival candidate captured`
→ `first car bind 0→1`
→ `exact headset route recovery/reassertion`
→ `final generation + output-intent check`
→ `Play issued`
→ `isPlaying=true`.

- The ExoPlayer listener captures the immutable headset id, loaded-book generation and explicit-selection
  sequence while `RouteHeardOwnership` still proves that exact headset was heard.
- The first 0→1 Media3 car-controller bind may match that focus loss only inside the bounded lifecycle window.
  Later bindings are not arrivals.
- Route recovery runs in the service coroutine and may suspend while Android temporarily omits the captured
  endpoint. It reads live device/selected-route state, but it may write only a policy reassertion of the exact
  captured id.
- During every suspension, book generation and explicit-selection sequence remain guards. A newer listener
  choice is authority; a transient live device omission is not.
- Standard Play/Pause intent is observed at the session-facing `ResumeFreshnessPlayer` boundary before it is
  forwarded to ExoPlayer. This matters for a duplicate Pause after focus has already made
  `playWhenReady=false`: even if ExoPlayer emits no second state-change callback, that newer listener intent
  still cancels the old continuity candidate. Service-owned automatic recovery uses the raw ExoPlayer and
  therefore does not invalidate itself through this boundary.
- The final check requires the same generation/selection sequence and the exact captured headset. Speaker,
  car, Automatic and another headset cannot substitute.
- The `Play issued` diagnostic is deliberately separate from the later `isPlaying=true` confirmation.

#### Departure state machine

While a car controller is present and playback is actually active, the same continuity owner retains the last
**positively proven** exact headset plus generation and explicit-selection sequence. Live A2DP disappearance
does not erase this lifecycle evidence; a deliberate pause, book/session boundary or newer output choice does.

The only departure ordering currently eligible for recovery is:

`playing exact headset + car connected`
→ `audioFocusLoss while car is still connected`
→ `departure focus candidate`
→ `last car disconnect 1→0`
→ route recovery / final check / Play / `isPlaying=true`.

Only the final 1→0 disconnect is a departure; dropping one of two Android Auto controller bindings is not.
The focus event must already have been observed while the car connection still existed and the final disconnect
must follow inside the bounded correlation window. A **disconnect-first → later focus-loss** ordering is logged
but deliberately stays silent: that ordering has not been physically measured, and treating any later focus
loss as departure could turn a phone call, navigation prompt or another media app into an automatic resume.
The next physical drive is expected to establish the actual exit ordering. If it is disconnect-first, #36 must
remain open until a stronger car-specific signal can make that path safe. Arrival and departure targets carry
an explicit phase and cannot consume each other.

`AUDIO_BECOMING_NOISY` remains outside this policy. So do generic focus loss without a matching lifecycle
boundary, phone-speaker playback, car output, an absent exact headset, stale generation/profile/session state,
a newer Play/Pause decision and a newer explicit Car/Headset/Automatic choice.

## Consequences

- The root remains predictable even as the library changes.
- Car lifecycle continuity is narrowly restored only when measured audio-focus loss pairs with the correct arrival or final-departure controller boundary and the exact generation-bound headset target remains eligible; generic focus/noisy pauses remain untouched (R-106).
- The player says which output the book is on when the observed route is strong enough to say so; speaker/unknown can leave both output actions unlit rather than lying.
- Car and Headset hold the two app-claimable primary slots while a car controller is bound; with no car bound, PLAY-007's skips hold those slots on the phone. The losing group remains in overflow. An earlier attempt requested secondary slots instead; those are discarded by the legacy conversion before a host sees them, as §8 records. Whether a specific head unit draws what it is sent remains device-only evidence.
- The phone speaker is not a BookWave Android Auto destination.
- Connecting a car does not silently steal an audiobook from an already-active headset when BookWave has enough state to preserve it.
- Pressing Car has one meaning on every supported car: hand routing back to Android.
- Pressing Headset has one meaning: choose among headset candidates, never the phone speaker.
- Series/author/download discovery remains available without crowding the root.
- A series book's live player can expose author + series/sequence without changing the server session's author field.
- Android Auto styling stays consistent with the host instead of being a partially reimplemented phone theme.
- Classic A2DP remains semantically ambiguous where Android itself supplies no stronger fact; that limitation is explicit and bounded.

## Verification

The PR's unit/Robolectric coverage includes the four-root browse contract, series ordering, voice-series matching, speaker exclusion including stale cached rows, Car-to-Automatic routing, ambiguous-A2DP handling, headset cycling, the car-arrival preservation race, profile-scoped series enrichment and the live Media3 series byline. The final implementation also publishes active/inactive output glyphs through a tested `OutputActionIcons` mapping, gives Car and Headset the two primary bar slots with the skips relocated to overflow — asserted by running Media3's own layout conversion, including the invariant that the back slot is never left empty — and reports credential/network playback failures through the media session. The seek-slot reservations are **not** published: an earlier `setSessionExtras` call was removed as measured dead code, because Media3 recomputes both keys from the custom layout and overwrites the app's value. Under this layout it computes the intended answer on its own; the value is inherited, not asserted.

The owner device-tested the Car/Headset routing on 2026-09-06: Headset appeared when connected and switched audio to the headset; Car returned audio to the car. Physical drives on 2026-09-19 measured arrival `audioFocusLoss` before the first car bind and exposed the transient A2DP remove/re-add race. The 2026-09-20 retest reported that entry now recovers only after a visible few-second interruption and that car exit stops playback. The supplied 19:34–19:38 excerpt contained no playback/focus/controller/route diagnostics, so it does not prove the exact latest ordering. §9 therefore preserves the measured safe arrival boundary, adds only the conservative focus-before-final-disconnect departure correlation, and expands diagnostics for the next drive. JVM coverage still cannot prove audible continuity, focus reacquisition or physical route choice; #36 remains open for a combined entry/pause-control/exit drive.
