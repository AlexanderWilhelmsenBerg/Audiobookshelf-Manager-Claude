# Playback architecture

**Classification:** Current contract for `main`, plus clearly marked pending contracts from the committed PR chain.  
**Current as reviewed:** 2026-09-12.

This document is the compact entry point for BookWave's playback correctness architecture. Detailed ADRs, bug investigations and reviews remain the evidence for why these rules exist.

## Core principle: evidence is not authority

BookWave receives playback facts from several places:

- local Media3/ExoPlayer movement;
- durable local progress/journal state;
- acknowledged pause/session writes;
- REST/session queries;
- realtime server progress events;
- external media commands such as notification, headset and car Play.

Those sources may provide **evidence** that the server or another device has moved. They must not independently seek the player or invent their own resume rule.

A playback decision belongs at a named policy/service boundary and all UI/system surfaces should enter that boundary.

## One audiobook is one playback timeline

ADR-0016 is the current contract: a book is presented to Media3 as one logical timeline even when its playable media spans several files.

Consequences:

- progress/bookmarks/history use book-relative positions;
- default Media3 previous/next semantics must not accidentally restart a many-hour audiobook;
- chapters are metadata/navigation over the book timeline, not independent playback windows merely to satisfy a host queue UI;
- Android Auto/player presentation must not casually reopen the one-window decision.

## Session ownership is serialized

Recent architectural work made playback-session transitions ordered and durable before Media3 queue/session handoff. A new book/profile session must not race an old session's final progress write or let an obsolete owner keep writing after the player changes.

Profile identity is carried with the playback/session work that owns it rather than resolved from "who is active now" at write time.

## Realtime is evidence, never a direct seek command

Realtime progress events may tell BookWave that another device moved or finished a book. They update evidence/state used by the resume policy and history, but the socket does not directly seek the player.

This separation is deliberate: a transport/event callback does not own user intent and cannot safely decide whether a remote rewind/advance should replace current local playback.

## Acknowledged pause state is evidence

When this device pauses and the server acknowledges the position, that acknowledgement is meaningful evidence about the server baseline. It must not be discarded simply because a later Play originates outside the app UI.

Once local playback moves again, that paused baseline becomes stale and must be invalidated for future freshness decisions.

## Unified resume freshness

PR #93 established `ResumeFreshnessCoordinator` as the one owner for standard Play resume freshness.

The current contract is:

- app Play, notification/system Play, headset Play and car Play use the same policy;
- realtime evidence can prove newer remote movement;
- REST/session query remains a correctness fallback when evidence is insufficient;
- acknowledged paused state is valid baseline evidence;
- local movement invalidates stale evidence;
- intentional remote rewinds are preserved, including a trusted rewind to `0:00`; never replace the rule with `max(position)`;
- small drift within the product threshold continues locally, while meaningful remote movement is adopted;
- wrong-profile, wrong-book, stale-generation and own-session echo evidence is rejected.

A fresh-session first-Play exemption is narrower than "new `/play` means authoritative". It may be minted only when one direct BookWave action opens the server session and immediately issues Play. Service-owned browse/arm opens do not get it.

Cold Media3 playback resumption is also explicitly excluded. `MediaSession.Callback.onPlaybackResumption(isForPlayback = true)` opens the real Audiobookshelf session and returns media for Media3 to install; Media3 then issues a loaded-item Play. That loaded Play must enter `ResumeFreshnessCoordinator` before raw Play because a newly opened `/play` position can still be stale or zero. The REST fallback remains bounded by the coordinator's timeout, and an unavailable check preserves the installed local position rather than inventing movement.

If the still-valid acknowledged baseline and Media3's installed position have diverged without a local-movement invalidation, a successful REST check may prove that the server still agrees with that baseline. In that case the coordinator restores the verified baseline before audio starts. That restore is not another device's progress and must not be recorded as remote movement. If the REST lookup is unavailable, the coordinator does not manufacture authority from the baseline and leaves the installed position untouched.

The debug cold-resume diagnostic may replace only the Media3-installed initial position. It must not change the underlying opened session/baseline, become durable progress, run for foreground Play, or run for metadata-only `onPlaybackResumption(false)` queries.

No future widget, shortcut, App Action, Android Auto callback or UI button may reimplement this policy privately.

## Remembered book identity

BW-PLAY-01 gives the device one durable answer to **which book did this phone last actually play for this profile?** That fact is intentionally separate from resume-position freshness.

The contract is:

- one opaque remembered-book ID is stored in profile-scoped Proto DataStore;
- ownership changes only when Media3 reports that locally owned media is actually playing; opening, arming or syncing a book does not claim it;
- REST/realtime progress, including deliberate remote rewinds or advances, never changes the remembered identity;
- startup/profile restore and Android Auto Continue resolve that identity against the profile's accessible books, then leave position choice to `ResumeFreshnessCoordinator`;
- finished or inaccessible remembered books produce no fallback selection; another book is never invented from `progress.updatedAt`;
- a temporarily missing cached progress projection does not erase a valid remembered identity: Android Auto may expose the remembered book as `book/<id>` and let the shared session opener supply the authoritative start position;
- profile deletion clears the remembered identity, and per-profile writes/clears are serialized so a delayed playback write cannot recreate deleted state;
- existing profiles migrate to **no remembered book** until this device actually plays one.

This state stores no title, cover or resume position. Those remain projections from the library/progress owners rather than duplicated device-local metadata.

## Profile boundaries

Playback, library, history, resume and Android Auto surfaces are profile-bound.

A profile switch must not allow:

- the previous profile's session to continue writing under the new profile;
- cached Android Auto nodes from the previous profile to remain authoritative;
- a widget/shortcut/system surface to expose locked or inaccessible profile metadata;
- restore/resume selection to be inferred from another profile's state.

## Android Auto

ADR-0029 remains the routing/media-control architecture, while PD-001 is the current browse-home product decision.

The separation is:

- Android Auto owns host rendering/player chrome;
- BookWave owns media-session semantics, browse hierarchy, metadata and allowed custom actions;
- the root is exactly **Continue → Series → Authors → Profiles**; the old Library/History/Chapters/output subtree is not an alternate car path;
- Series presentation derives recency from the active profile's existing `Book.progress.updatedAt` evidence, while books inside a Series retain LIB-003 sequence order; no second playback-history/Series-recency owner exists;
- browse invalidation is profile-bound and snapshot-derived: one accessible-book emission produces one immutable exposed-shape snapshot, ordinary changes notify only parents whose ordered opaque child membership changed, and profile-generation changes evict all profile-scoped plus emitted dynamic parents (BW-AUTO-01);
- browse artwork crosses the host-process boundary only through opaque local `content://` capabilities; each browse request reads the download manifest once so durable offline covers are preferred, then the bridge may reuse BookWave's existing image cache with network loading disabled. A capability is profile-bound and an unavailable image degrades to the host placeholder;
- Continue, Series, Authors and their book children request artwork-first host grid presentation, while Profiles requests a text-first list; the automotive host still owns the exact background/card chrome, with BookWave contributing its shipped teal platform accent where supported;
- Profiles uses the existing `SwitchProfileUseCase` for lock/flush/pause/context ownership and `RestoreProfilePlaybackUseCase` for the incoming paused restore, which resolves the resume candidate with the same rule as Android Auto's resume row (remembered unfinished book, else the newest Continue book; owner-approved) and only ever arms. Each saved profile is published as a **playable, non-browsable action row** (#174): a browsable row made the host open an empty child view, because a profile has no children. Selecting the row reaches `onSetMediaItems`, which runs the switch and always answers with a failed future — Media3's contract for that is to leave the player untouched, so the selection never sets, prepares or plays media. Selecting the active profile is a no-op, car selections are serialised, and a refusal (for example a locked profile) is sent to the car as a session error. Inactive rows also keep the `Use profile` media-item command for hosts that draw browse actions; it runs the same switch. Because the car can switch while the phone UI never attached a controller, `PlaybackController.handOver` attaches to the live session by its direct token first, so the outgoing book is still paused and flushed. The car surface never implements a separate switch transaction or credential-entry flow;
- Android Auto uses the same playback/resume owner as every other Play surface.

JVM/Robolectric tests can assert browse-tree construction, ordering, metadata, URI shape and delegation. They cannot prove how a real head unit renders artwork/action buttons or whether a projected host visibly redraws after `notifyChildrenChanged`; those remain DHU/physical-car acceptance.

## Audio routing

Transport classification and semantic role are separate facts.

Classic Bluetooth A2DP can represent headphones, a speaker or a projected-car route. BookWave must allow an ambiguous/unknown semantic answer where Android does not expose enough information.

Issue #11 replaces the inference-heavy `HeadsetHold` / `heardAudio` / release-state combination with one service-owned `RouteHeardOwnership`. Its route record is bound to the currently loaded Media3 book generation and is invalidated when the book changes or the queue empties.

The owner distinguishes explicit BookWave listener intent from weaker Android route-policy observation. Every explicit phone/Android Auto output choice enters through `AudioOutputRouter.select`; framework policy from `getAudioDevicesForAttributes` is considered only while BookWave playback is observed as running and is never described as proof of the exact AudioTrack sink. An explicit listener choice therefore outranks enumeration/order disagreement, and multiple ambiguous classic-A2DP routes never become ownership merely because one was listed first.

GitHub #128 (historical Forgejo #36) adds one service-owned `CarArrivalResumeGate` beside, not above, route-heard ownership. Its scope is narrow: correlate Media3's measured audio-focus loss with a car-specific boundary inside the bounded window. Arrival may use a positive projection connection or the first 0→1 Android Auto controller bind. Confirmed projection departure supports either focus/boundary order; the conservative final 1→0 legacy-controller fallback supports only a focus loss already observed while the car was still connected. It may snapshot only an exact headset that `RouteHeardOwnership` positively proved while the current generation was playing, together with the explicit output-selection sequence that was current at that moment.

Arrival captures that snapshot at focus loss because live route evidence may flap immediately afterward. While a car is connected, departure additionally retains the last positively proven playing headset so a transient A2DP omission cannot erase stable listener intent. `AndroidAutoProjectionMonitor` supplies the stronger physical lifecycle signal: the service retains its existing positive lifecycle latch across Unknown reads until confirmed NotConnected, clears stale controller counts on exit and never treats recovery from Unknown as another arrival. Without a positive projection observation, disconnect-first/later-focus-loss remains diagnostic-only. That retained snapshot is not a second route authority: it cannot discover an output, cannot replace a newer explicit Car/Headset/Automatic selection, cannot cross a generation/profile/session boundary and cannot make speaker or car output eligible. A policy reassertion remains separate from listener selection.

The exact captured id is passed to `CarArrivalRouteRecovery`, which may wait for and reassert only that same headset. Live Android device enumeration is therefore observation used to prove presence, not authority to choose a replacement. Car still releases BookWave's preference to Automatic, Headset still targets only headset candidates, and classic A2DP remains semantically `Ambiguous`.

BookWave continues to delegate audio-focus ownership to ExoPlayer/Media3. On the measured setup, arrival focus loss precedes the first trustworthy car-controller bind by two to four seconds, so there is no safe app signal that can guarantee zero-gap entry without generalising focus-loss resume. JVM tests prove BookWave's ownership, generation, lifecycle-correlation and precedence rules; they do not prove audible continuity, focus reacquisition or the exact physical sink. Headset/car/speaker acceptance remains a device boundary.

## Sleep timer and automatic schedule

`SleepTimerController` remains the source of truth for timer behavior. The implemented schedule is an eligibility policy around the same timer, evaluated during active playback against the civil window; it does not start audio through an alarm or worker. Manual cancellation suppresses the current occurrence durably, and a later occurrence can become eligible again.

PD-002 and merged PR #213 suppress new automatic timers while a car controller/projection owns the connection, preserve existing/manual timers and re-evaluate eligible active playback on disconnect without starting audio or clearing cancellation. The service supplies connection truth to that existing timer owner.

Phone players retain the book title and display the countdown on the Sleep action. Car metadata/commands never expose it; because one MediaSession is shared, the phone notification/lock screen also hide it while Auto is connected. Device rendering, sensors/grace and the timer-expiry/continuity interaction remain acceptance cases in the [verification register](../testing/roadmap-verification-register.md).

## System surfaces

Future launcher shortcuts, widgets, App Actions and deep links must call a stable semantic action/playback boundary.

They may display a projection of durable BookWave state; they do not own:

- remembered-book selection;
- resume freshness;
- session reconciliation;
- playback progress truth;
- profile authorization.

## Testing contract

Playback correctness should be proven at the lowest level that can actually prove the claim:

- **pure JVM/domain tests:** policy/state-machine decisions, ownership, generation/profile/book rejection;
- **repository/storage tests:** persistence, migration and profile boundaries;
- **Robolectric/Media3 tests:** session/controller integration and metadata/browse construction where platform shadows are meaningful;
- **connected device:** process death, real AndroidKeyStore/storage/audio/service lifecycle where applicable;
- **DHU / real car:** Android Auto rendering/controller behavior;
- **real audio routes:** headset/car/speaker routing plus Android Auto arrival and departure races.

Never upgrade a lower-level test into evidence about a host/device behavior it cannot observe.

## Evidence/history

Useful historical reasoning remains in:

- `docs/adr/` for accepted/superseded decisions;
- `docs/bugs/` for reproductions and correctness investigations;
- `docs/reviews/` for dated audits;
- `docs/risks.md` for live residual risk.

Those documents should be read as evidence beneath this current contract and `docs/roadmap.md`, not as competing work queues.
