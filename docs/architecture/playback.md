# Playback architecture

**Classification:** Current contract for `main`, plus clearly marked pending contracts from the committed PR chain.  
**Current as reviewed:** 2026-09-07.

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

## Unified resume freshness — pending PR #93

**Not on `main` until PR #93 merges.** The committed design makes #93 the one owner for external/local Play resume freshness.

The intended contract is:

- app Play, notification/system Play, headset Play and car Play use the same policy;
- realtime evidence can prove newer remote movement;
- REST/session query remains a correctness fallback when evidence is insufficient;
- acknowledged paused state is valid baseline evidence;
- local movement invalidates stale evidence;
- intentional remote rewinds are preserved; never replace the rule with `max(position)`;
- small drift within the product threshold continues locally, while meaningful remote movement is adopted;
- wrong-profile, wrong-book, stale-generation and own-session echo evidence is rejected.

No future widget, shortcut, App Action, Android Auto callback or UI button may reimplement this policy privately.

## Remembered book identity — roadmap item BW-PLAY-01

Current `main` still derives the "last played" unfinished book from server-backed progress recency. That is not the same fact as **which book this phone last listened to**.

The planned contract after #93 is:

- one durable local remembered-book ID per profile;
- updated only from unambiguous local playback ownership;
- survives process death, startup, offline operation and profile switching;
- remote progress/realtime timestamps do not change the remembered identity;
- the remembered **book** and resume **position freshness** remain separate decisions;
- migration does not invent ownership from server `updatedAt` values.

Until BW-PLAY-01 lands, documentation/system-surface work must not describe server progress recency as a durable local-ownership contract.

## Profile boundaries

Playback, library, history, resume and Android Auto surfaces are profile-bound.

A profile switch must not allow:

- the previous profile's session to continue writing under the new profile;
- cached Android Auto nodes from the previous profile to remain authoritative;
- a widget/shortcut/system surface to expose locked or inaccessible profile metadata;
- restore/resume selection to be inferred from another profile's state.

## Android Auto

PR #78 is the current committed Android Auto/routing finalization and carries ADR-0029 on its branch until merge.

The intended long-term separation is:

- Android Auto owns host rendering/player chrome;
- BookWave owns media-session semantics, browse hierarchy, metadata and allowed custom actions;
- the stable car root/product decisions are not reopened without device/platform evidence;
- browse invalidation remains profile-safe and will later become shape-derived/selective (BW-AUTO-01);
- Android Auto uses the same playback/resume owner as every other Play surface.

A JVM/Robolectric test can assert browse-tree construction and metadata. It cannot prove how a real head unit renders action slots, completion metadata, icons or presentation.

## Audio routing

Transport classification and semantic role are separate facts.

Classic Bluetooth A2DP can represent headphones, a speaker or a projected-car route. BookWave must allow an ambiguous/unknown semantic answer where Android does not expose enough information.

The existing #78 `HeadsetHold` work is explicitly under follow-up pressure because inference-heavy preservation accumulated edge cases. The roadmap therefore prefers a research spike that observes the route actually carrying BookWave's audio during genuine playback before adding another release/suppression flag.

No routing redesign should merge merely because a JVM model looks convincing; car/headset routing requires real device evidence.

## Sleep timer and future automatic schedule

The existing manual sleep timer remains the source of truth for timer behavior.

PR #98 changes Playback settings UI only and explicitly excludes automatic scheduling.

Future BW-SLEEP-01 will add a schedule as an **eligibility policy around the existing timer**, not a competing timer implementation. Product rules/state machine come before AlarmManager/WorkManager choices.

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
- **real audio routes:** headset/car/speaker routing and arrival races.

Never upgrade a lower-level test into evidence about a host/device behavior it cannot observe.

## Evidence/history

Useful historical reasoning remains in:

- `docs/adr/` for accepted/superseded decisions;
- `docs/bugs/` for reproductions and correctness investigations;
- `docs/reviews/` for dated audits;
- `docs/risks.md` for live residual risk.

Those documents should be read as evidence beneath this current contract and `docs/roadmap.md`, not as competing work queues.
