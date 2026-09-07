# ADR-0029 — Android Auto is a stable audiobook surface

**Status:** Accepted 2026-09-05  
**Requirements:** PLAY-001, PLAY-002, PLAY-003, ROUTE-002, LIB-002, LIB-003  
**Supersedes:** the routing and browse conclusions of ADR-0027's third amendment where they differ from this decision.

## Context

PR #78 began as a fix for one Android Auto output button. A car test showed why the problem was larger: a blind cycle over every Android audio device could move an audiobook through earbuds, the phone speaker and the car, while the head unit gave the driver no useful explanation of what the next press would do.

The same review also exposed a broader product question. Android Auto should not be a small copy of the phone UI. The platform owns the player chrome, theme, artwork treatment and placement of custom actions. What BookWave does control is the media session, the browse tree, the metadata attached to its items, and the meaning of its custom actions.

That makes the correct design a small number of stable destinations with predictable semantics rather than a dense dashboard.

## Decision

### 1. The root has four stable destinations

For a non-empty accessible library the Android Auto root is always, in this order:

1. **Continue**
2. **Chapters**
3. **History**
4. **Library**

The driver can learn those four positions once. Empty shelves do not cause the root itself to rearrange.

`Library` contains the broader discovery choices that do not need to be one tap from the player: Series, Authors, Downloads, Recently added, Listen again, Discover and Audio output when applicable. Series reuse BookWave's existing numeric series-order rules rather than inventing a car-specific sort.

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

### 7. Do not break the phone notification to chase car-only action placement

Media3's legacy compatibility state is shared by hosts that include the media notification and some Android Auto implementations. BookWave can publish custom actions and preferred slots, but cannot reliably demand a car-only layout on every head unit.

The Android Auto browse/navigation design therefore excludes sleep and bookmark as destinations and prioritises the Car/Headset actions. Existing phone-notification behaviour is not removed merely to make a particular head unit hide an action it may source from the shared legacy state.

### 8. The current output is shown by lighting an action, because nothing else on the player can show it

A second device run reported that the car's player never says where the audio is going: *"the current audio output is not seen."*

Two surfaces already carry it and neither reaches a driver. The headset action's display name is `Playing on AirPods Pro 3`, and §2.11 records that this head unit does not draw custom-action labels. The `Audio output` browse list marks the live route *Playing here*, which is correct and four taps away from the player.

What the host draws on the player is the title, the byline and these two icons. Only the icons are BookWave's, so that is where the state goes: each output action has a lit variant carrying an indicator bar. `OutputButtons.onHeadset` and `onCar` are deliberately **not complements**, and this rule has been narrowed twice from the same mistake. A confirmed headset route lights Headset. Car lights only on positive evidence *of a car* — a `TYPE_BUS` route, or an unselected ambiguous A2DP route while a car controller is bound — and **both actions unlit is a legitimate state**: the phone speaker, a dock or USB sink, or a route the platform has not reported. The first version lit Car whenever Headset was false, which described phone-speaker playback as car playback; the guard that replaced it still lit Car for every route `OutputDevices.roleOf` sends to `Other`, which is every USB device, accessory, dock and HDMI sink. Both were the same error — a positive claim drawn from the absence of alternatives — and it is why the predicate now asks rather than infers.

**The byline is deliberately not used.** It is built once in `MediaItems.queueFor` from the session, so making it name the live route would mean replacing the `MediaItem` on every route change — rebuilding the media source of a playing book for a cosmetic gain, against product priority 1. A lit icon costs a republish of the button preferences, which the service already does when the route moves — **though the republish alone never reached the car, and then the route behind it turned out to be stale**, and a third device run is what found it. `MediaSession.setMediaButtonPreferences(List)` updates Media3's internal layout and dispatches to Media3 controllers, but never calls `updateLegacySessionPlaybackState`, and the legacy `PlaybackStateCompat` is where a car reads custom actions from. So the correct icon sat in the session until an unrelated player event rebuilt the state. `MediaButtonPublishing` now also publishes through `getMediaNotificationControllerInfo()`, the one public path that forces that refresh. A third device run then reported the icon still dark, and two more app-side causes came out of it: nothing re-read the *route* when a car bound — Android Auto activating an already-connected A2DP link fires no `AudioDeviceCallback`, so the decision was made from a pre-drive route — and a reported speaker could mask a reported dashboard, because `current()` took the first active output in the platform's enumeration order. `AudioOutputRouter.resettle` and a speaker-demoting `current()` fix those, and `republishOutputButtons` now logs the inputs to the decision so the next drive is conclusive rather than suggestive.

The lesson is the one §8 keeps learning, three times over: each step was true and none of them was sufficient. *The republish happens* was true; *the car sees it* did not follow. *The car sees it* became true; *the state behind it is current* did not follow.

The minimised control bar has now been wrong in this section **three times**, and the answer is a test rather than a paragraph. The first version called the missing actions an unavoidable layout limit, reasoning from the three slots this code happened to use. The second corrected that to six — `CommandButton` does declare `SLOT_BACK_SECONDARY` and `SLOT_FORWARD_SECONDARY` — and concluded that requesting them, with `SLOT_OVERFLOW` as a fallback, put the actions in the bar on any host that would place them, leaving the rest a host contract. A device run then reported the compact player unchanged.

Both conclusions were reasoning about the wrong layer. Android Auto is served by `MediaSessionLegacyStub`, which builds its layout with `CommandButton.getCustomLayoutFromMediaButtonPreferences` — and that conversion branches on exactly three slot values: `SLOT_BACK`, `SLOT_FORWARD` and `SLOT_OVERFLOW`. The secondary slots are not tested in it anywhere. A button naming one falls through to the overflow branch; a button naming one *without* overflow is dropped entirely. It was never a host contract: the slots were discarded a layer before the host saw them.

`MediaButtonSlotConversionTest` executes that conversion from inside Media3's own package, so the claim fails a build instead of sitting in prose — the specific remedy for this section's habit.

**The owner then took the decision the third version deferred.** *"Compact player still don't show headset or car button. I need them more than seek forward and back."* So the output actions now name the back and forward slots outright, and PLAY-007's skips name the same slots with an overflow fallback — the outputs win because they are published first, and a displaced skip is relocated rather than dropped, which is a distinction the conversion enforces and a test pins. The bar holds Car and Headset; the skips are one tap away in overflow, on the car **and** on the phone, which reads the same single layout. `docs/risks.md` R-109 records what that costs.

Two properties are asserted rather than reasoned about, and the second is a safety property: BookWave's own ordering puts an output action in the bar (`MediaButtonLayoutTest`, which exists because reverting the ordering broke *no* test until it did), and the back slot is occupied in every combination of shown actions. If it is ever vacated, Media3 stops clearing `ACTION_SKIP_TO_PREVIOUS`, nothing in this app intercepts it, and a head unit's *previous* reaches `Player.seekToPrevious` and restarts the book.

### 9. A car arriving still leaves the book paused, and that is recorded rather than fixed here

The same run reported it: *"when listening to something when android auto is connecting, it pauses the audio. If listening on a headset, it should not stop."*

Nothing in this app pauses on car arrival. The platform does, by one of two routes — `ACTION_AUDIO_BECOMING_NOISY`, which Android broadcasts when an A2DP sink is deactivated and a car taking the active A2DP slot does exactly that, or a permanent audio-focus loss while the projection host starts. Media3 pauses for both and offers a resume for neither, which is why the book stays stopped rather than dipping.

**A resume was implemented and then lifted back out of this PR at the owner's decision.** Five review rounds found seven defects in it, and their shape is what makes this a decision rather than a setback: the mechanism has to infer *"a car took the audio"* from proxies — a pause reason, a binding count, an `isActive` flag, a route the platform reports late or not at all — and each round removed one inference that had looked sound in a comment. Four of the seven were resumes that should not have happened, including one that could have started an audiobook aloud on the phone speaker.

The judgement is therefore that this cannot be finished without a car. Two facts it depends on are unmeasured and unmeasurable here: whether a real host's controller binds close enough to the pause to be paired with it, and how long the held-headset preference takes to become the live route on a platform that announces neither. The implementation is preserved at commit `26f65f0` on this branch's history and returns as its own PR once one drive has answered R-106.

For this **car-arrival pause problem**, #78 deliberately ships no inferred auto-resume. The routing actions, lit-state presentation and other settled Android Auto improvements remain independent of that deferred continuity experiment.

## Consequences

- The root remains predictable even as the library changes.
- A car arriving still stops the book; the fix is deferred to its own PR rather than merged unverified (R-106).
- The player says which output the book is on when the observed route is strong enough to say so; speaker/unknown can leave both output actions unlit rather than lying.
- Car and Headset request the two secondary primary-bar slots and retain overflow as fallback; whether a specific host shows those positions in its minimised bar is still device-only evidence.
- The phone speaker is not a BookWave Android Auto destination.
- Connecting a car does not silently steal an audiobook from an already-active headset when BookWave has enough state to preserve it.
- Pressing Car has one meaning on every supported car: hand routing back to Android.
- Pressing Headset has one meaning: choose among headset candidates, never the phone speaker.
- Series/author/download discovery remains available without crowding the root.
- A series book's live player can expose author + series/sequence without changing the server session's author field.
- Android Auto styling stays consistent with the host instead of being a partially reimplemented phone theme.
- Classic A2DP remains semantically ambiguous where Android itself supplies no stronger fact; that limitation is explicit and bounded.

## Verification

The PR's unit/Robolectric coverage includes the four-root browse contract, series ordering, voice-series matching, speaker exclusion including stale cached rows, Car-to-Automatic routing, ambiguous-A2DP handling, headset cycling, the car-arrival preservation race, profile-scoped series enrichment and the live Media3 series byline. The final implementation also publishes active/inactive output glyphs through a tested `OutputActionIcons` mapping, requests the secondary primary-bar slots with overflow fallback, explicitly releases the seek-slot reservations, and reports credential/network playback failures through the media session.

The owner device-tested the Car/Headset routing on 2026-09-06: Headset appeared when connected and switched audio to the headset; Car returned audio to the car. That run also supplied the two presentation findings addressed by the final slice: live Now Playing showed only title + author, and the custom car glyph's front wheel sat too far forward. The new secondary-slot placement, lit glyphs and failure message remain host/device acceptance items rather than being called verified by JVM coverage.
