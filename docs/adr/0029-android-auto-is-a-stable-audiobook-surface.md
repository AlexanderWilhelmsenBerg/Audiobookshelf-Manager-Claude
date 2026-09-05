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

This narrows R-103 rather than pretending to eliminate it. Without requesting additional Bluetooth identity permission or asking the user to classify a device, an arbitrary classic-A2DP endpoint cannot always be named semantically. The important safety properties do not depend on that guess: phone speaker is excluded, Car releases the preference, and the car-arrival race preserves a previously known headset.

### 5. History is navigation, not an audit log

The car's History list exists to answer "where can I go back to?". It keeps useful position decisions and remote-device movement, de-duplicates equivalent rows, and uses chapter-relative labels when chapter data exists.

Sleep-timer bookkeeping, ordinary Play entries and server-freshness diagnostics stay out of the car list. They remain valid history/diagnostic information elsewhere; they are simply poor driving controls.

### 6. Android Auto receives audiobook metadata, but Android draws it

BookWave supplies the platform with the information it can truthfully provide from cached library/session data: cover artwork, title, author, series and sequence in browse/resume rows, chapter rows, chapter-relative progress and whole-book progress.

The live playback session currently carries title, author, cover and chapters but not series membership. BookWave therefore does not invent a live Now Playing series value. Adding that would require extending the playback-session model as a separate data-contract change.

Android Auto decides how that metadata is laid out. BookWave cannot make the car player inherit the phone's background theme, place a custom shadow behind the cover, or draw a bespoke metadata panel beside it. Those are host-rendered surfaces.

### 7. Do not break the phone notification to chase car-only action placement

Media3's legacy compatibility state is shared by hosts that include the media notification and some Android Auto implementations. BookWave can publish custom actions and preferred slots, but cannot reliably demand a car-only layout on every head unit.

The Android Auto browse/navigation design therefore excludes sleep and bookmark as destinations and prioritises the Car/Headset actions. Existing phone-notification behaviour is not removed merely to make a particular head unit hide an action it may source from the shared legacy state.

## Consequences

- The root remains predictable even as the library changes.
- The phone speaker is not a BookWave Android Auto destination.
- Connecting a car does not silently steal an audiobook from an already-active headset when BookWave has enough state to preserve it.
- Pressing Car has one meaning on every supported car: hand routing back to Android.
- Pressing Headset has one meaning: choose among headset candidates, never the phone speaker.
- Series/author/download discovery remains available without crowding the root.
- Android Auto styling stays consistent with the host instead of being a partially reimplemented phone theme.
- Classic A2DP remains semantically ambiguous where Android itself supplies no stronger fact; that limitation is explicit and bounded.

## Verification

The PR's unit/Robolectric coverage includes the four-root browse contract, series ordering, voice-series matching, speaker exclusion including stale cached rows, Car-to-Automatic routing, ambiguous-A2DP handling, headset cycling and the car-arrival preservation race.

GitHub Actions workflow run #504 passed on head `61db9071b310efadff8694e960d6f8a1b264a5f3` before this documentation-only closeout commit.