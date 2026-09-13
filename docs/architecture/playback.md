# Playback Architecture

This document defines the current ownership and correctness boundaries for BookWave playback.

It is not a second product specification. User-facing behavior belongs in `PRODUCT_SPEC.md`; sequencing belongs in `docs/roadmap.md`; accepted architectural decisions belong in ADRs; test commands belong in `docs/testing.md`.

## Ownership map

| Concern | Primary owner | Notes |
|---|---|---|
| Media3 player/session lifecycle | `:playback` | One player/session owner; UI and external controllers must not fork playback policy. |
| Playback session open | `OpenPlaybackSessionUseCase` + playback repository | One session-open path for phone, car and other Media3 surfaces. |
| Resume freshness | `ResumeFreshnessCoordinator` | One freshness authority for all standard Play commands. |
| Device-local remembered audiobook identity | `RememberedBookRepository` | Per-profile identity written only by actual local playback; remote progress cannot replace it. |
| Local position journal / durable sync input | playback repository + sync outbox | Local progress durability must not depend on immediate server availability. |
| Realtime progress evidence | realtime evidence store / book-change bridge | Evidence for policy and UI, not a direct player mutation path. |
| Playback history | playback history repository | Local events and imported remote evidence have explicit ownership. |
| Audio routing | `AudioOutputRouter` / route policy owner | Routing observations and user intent must not be conflated. |
| Android Auto browse surface | `AutoLibrary` + media service callback | Browse presentation consumes playback/library state; it does not own playback policy. |

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

The resume decision compares:

- local Room position;
- durable acknowledged-pause evidence;
- recent realtime evidence;
- bounded REST freshness when needed.

## Resume freshness decision table

All standard Play commands — in-app, notification, headset/Bluetooth, Android Auto and other Media3 controllers — must converge on the same freshness owner.

| Evidence | Required behavior |
|---|---|
| No loaded resumable item | Use normal media/session resolution; do not invent a position. |
| Fresh local state with no newer trusted evidence | Resume local position. |
| Recent trusted realtime evidence for the same profile/book | Consider it in the shared freshness decision. |
| Server check required and succeeds | Use the shared policy result; do not create a caller-specific rule. |
| Server check unavailable | Apply the documented bounded fallback; do not block Play indefinitely. |
| Profile/book changes while check is running | Supersede the stale decision. |
| Seek/auto-rewind/other local move races a check | Newer local intent wins; stale result must not overwrite it. |

PR #93 established the shared owner. PR #140 / #138 extended the same policy to Media3 cold playback resumption: `onPlaybackResumption` may stage the remembered session/position so Media3 can load the queue, but that position is not trusted merely because it came from the cold-resumption handoff. The first loaded Play still enters `ResumeFreshnessCoordinator`, which consumes the staged cold start as fresh-start context and applies the same bounded REST/realtime/local decision used by warm controller Play. If the server is unavailable, the staged/session position remains the fallback; if a newer trusted position exists, the coordinator may replace it before audio starts. A newer seek/Pause/book/profile change supersedes the cold plan exactly like any other in-flight freshness plan.

This separation matters:

- Media3 owns the mechanics of restoring an empty session;
- BookWave owns whether the restored position is still trustworthy;
- there is still one resume policy rather than a new Android-Auto/headset-specific branch.

## Durable locally remembered audiobook identity

Resume-position freshness and **which audiobook this device most recently played** are separate facts.

BookWave persists one opaque remembered library-item id per local profile in app DataStore. The contract is:

- only actual local playback (`isPlaying == true`) may write it;
- REST/realtime progress, including deliberate remote rewinds or advances, never changes the remembered identity;
- startup/profile restore and Android Auto Continue resolve that identity against the profile's accessible books, then leave position choice to `ResumeFreshnessCoordinator`;
- finished or inaccessible remembered books produce no fallback selection; another book is never invented from `progress.updatedAt`;
- a temporarily missing cached progress projection does not erase a valid remembered identity: Android Auto may expose the remembered book as `book/<id>` and let the shared session opener supply the authoritative start position;
- profile deletion clears the remembered identity, and per-profile writes/clears are serialized so a delayed playback write cannot recreate deleted state;
- existing profiles migrate to **no remembered book** until this device actually plays one.

This state stores no title, cover or resume position. Those remain projections from the library/progress owners rather than duplicated device-local metadata.

## Playback-end history

Playback-end history has one transport owner at the service/player boundary. Ordinary app, notification,
headset and Bluetooth pauses persist as `Pause`. A sleep timer marks the next Media3 pause with its cause
before changing `playWhenReady`, so the same callback persists one `SleepTimerExpired` row instead of a
generic `Pause` plus a second timer row. The marker is one-shot and is cleared by a subsequent Play if it
was never consumed. Local history persistence does not depend on a server round trip.

## Profile boundaries

Playback, library, history, resume and Android Auto surfaces are profile-bound.

A profile switch must not allow:

- the previous profile's session to continue writing under the new profile;
- cached Android Auto nodes from the previous profile to remain authoritative;
- a widget/shortcut/system surface to expose locked or inaccessible profile metadata;
- a delayed write to resolve ownership from whichever profile became active later.

## External controllers

External controllers are first-class inputs, not second-class shortcuts around app logic.

The media-service boundary is where controller commands converge. Standard Play/Pause behavior should not depend on identifying which controller issued the command because Media3 intentionally normalizes many controller sources.

Any feature that truly depends on controller identity must use evidence the platform actually exposes rather than infer it from unrelated state transitions.

## Android Auto

Android Auto has three distinct concerns that must remain separated:

1. **Browse/presentation** — library tree, metadata, progress rows, invalidation.
2. **Controller behavior** — Play/Pause/seek/custom actions reaching the shared playback session.
3. **Audio routing** — current output and explicit route choice.

Do not solve an Android Auto rendering problem by modifying resume policy, or a routing problem by changing the browse tree.

## Downloads are not playback ownership

A downloaded copy changes where bytes come from, not which subsystem owns playback state.

Playback session semantics, remembered identity, resume freshness, history and progress ownership must remain consistent whether the underlying bytes are streamed or local.

## Validation tiers

Different claims require different evidence:

- **pure JVM / unit:** policy, arbitration, reducer/state-machine and formatting behavior;
- **Robolectric / repository:** Room/DataStore persistence, Android-adapter behavior where the platform can be simulated;
- **connected device:** process death, real AndroidKeyStore/storage/audio/service lifecycle where applicable;
- **DHU / real car:** Android Auto rendering/controller behavior;
- **real audio routes:** headset/car/speaker routing and arrival races.

Never upgrade a lower-level test into evidence about a host/device behavior it cannot observe.

## Evidence/history

Useful historical reasoning remains in:

- `docs/adr/` for accepted/superseded decisions;
- `docs/bugs/` for reproductions and correctness investigations;
- `docs/reviews/` for dated audits;
- git history for implementation details.

If an older note conflicts with current `PRODUCT_SPEC.md`, the active roadmap or accepted ADRs, the current canonical documents win.
