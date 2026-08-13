# Closing out Phase 2 — what the other clients have that we do not

Written 2026-08-13, at the start of wave 5, after waves 1–4 merged.

The question this answers: **with four waves built, what is still missing from Phase 2 that a listener would
notice?** PRODUCT_SPEC is the contract, but a requirements document written once cannot see what the
ecosystem settled on afterwards — so this compares against the clients people actually use.

## What was compared

| Client | Platform | Why it is here |
| --- | --- | --- |
| [Audiobookshelf app](https://github.com/advplyr/audiobookshelf-app) | Android + iOS | The first-party client. What the server's own team considers table stakes. |
| [Absorb](https://github.com/pounat/absorb) | Android + iOS | The most feature-complete third-party Android client. |
| [AudioBooth](https://github.com/AudioBooth/AudioBooth) | iOS/iPadOS/macOS/watchOS | The owner's UI reference for the player and book screen. |
| [ShelfPlayer](https://github.com/rasmuslos/ShelfPlayer) | iOS | **Archived** — the original was sold and the repository now carries only a farewell note. It is no longer a reference for anything, and this document does not treat it as one. |
| [SoundLeaf](https://audiobookshelf.org/docs/documentation/community/community-apps/) | iOS | Named here only for its queue, which nothing else has. |

## The comparison

✅ built · ⚠️ partial · ❌ absent · — out of Phase 2 scope

| Capability | Official | Absorb | AudioBooth | **Ours** |
| --- | --- | --- | --- | --- |
| Sleep timer, end-of-chapter, fade | ✅ | ✅ | ✅ | ✅ |
| Shake to reset the timer | ✅ | ✅ | — | ✅ |
| Playback speed | ✅ | ✅ | ✅ | ✅ |
| **Per-book** speed memory | ✅ | ✅ | — | ✅ |
| Auto-rewind after a pause | ✅ | ✅ | — | ✅ |
| Configurable skip amounts | ✅ | ✅ | — | ✅ |
| Chapter navigation | ✅ | ✅ | ✅ | ✅ |
| Lock screen / background playback | ✅ | ✅ | ✅ | ✅ |
| Progress sync, offline queue | ✅ | ✅ | ✅ | ✅ |
| **Book *and* chapter progress together** | ✅ | ✅ | ✅ | ✅ *(wave 5)* |
| **Media-button resume** | ✅ | ✅ | ✅ | ❌ |
| **Android Auto / CarPlay** | ✅ | ✅ | ✅ | ❌ |
| **Bookmarks** | ✅ | ✅ | — | ❌ (button present, disabled) |
| Offline downloads | ✅ | ✅ | ✅ | — Phase 3 |
| Widgets | — | ✅ | ✅ | — wanted, later phase: always the latest played book |
| Listening statistics | ✅ | ✅ | ✅ | — wanted, later phase |
| Equaliser | — | ✅ | — | — wanted, later phase |
| Chromecast | ✅ | ✅ | — | — not in spec |
| Auto-play next in a series | — | ✅ | — | — Phase 4 |
| Queue / up-next, drag to reorder | — | — | — (SoundLeaf) | — reframed as smart download, Phase 3 (ADR-0017) |
| Car mode (oversized controls) | — | ✅ | — | — the owner means Android Auto; see that row |

## The four gaps worth closing, in order

Ordered by *what Phase 2 is contractually required to deliver* first, then by benefit against risk. Three of
the four are already required by PRODUCT_SPEC and are simply unbuilt — that matters more than any feature a
rival has, because Phase 2 cannot be called finished while an exit criterion is missing.

### 1. A book is one timeline window — ADR-0016 · ✅ **built, 2026-08-13**

Not a competitive gap; a correctness one, and the reason it leads. Media3 reports the *current media item's*
position and duration to every controller, and our playlist is one item per audio file — so the notification,
the lock screen and (later) Android Auto describe the file, not the book. A device run found it: on a library
with a file per chapter it reads as "time left in this chapter".

Wave 4 printed the book's remaining time beside it as text. This replaces the caption with the right number
and **deletes the caption**, three item extras, `globalPositionOf`, `tracksOf` and `GlobalTimeline.cursorFor`.
Mostly subtraction.

**Benefit:** every present and future control surface becomes correct at once, and the code shrinks.
**Risk:** it is the core playback path. Mitigated by being mostly deletion, and by the soak.

### 2. Media-button resume — **PRODUCT_SPEC Phase 2 exit criterion, unbuilt**

> Exit criteria: … **Media-button resume.**

A headset play button must resume the last book. This is ROUTE-001, it is one of four things PRODUCT_SPEC says
Phase 2 must prove, and none of it exists: there is no `onPlaybackResumption`, so a headset press against a
dead process does nothing.

**Benefit:** Phase 2 cannot close without it. It is also the single most-used control on a pair of headphones.
**Effort:** small — Media3 has a dedicated `MediaSession.Callback.onPlaybackResumption` hook, and the last
position is already in Room. The work is mostly deciding what "the last book" means with two profiles.

### 3. Android Auto browse tree — **PRODUCT_SPEC 11.1 responsibility, unbuilt**

> Responsibilities: … **publish browsable content to Android Auto/system**

`onGetLibraryRoot` currently rejects, which wave 1 recorded as the honest answer rather than returning an empty
root. Every client compared here has Auto or CarPlay. For an audiobook app this is not a nicety — the car is
where a large share of listening happens, and a client that cannot be driven from the head unit is a client
people keep a second app for.

**Benefit:** the largest single feature gap against every rival, and it is already a stated responsibility of
the service we built.
**Effort:** medium. The tree itself is small (Continue listening / Libraries / Series / Authors), but it needs
`onGetChildren`, `onGetItem`, `onSearch`, per-profile filtering (PRODUCT_SPEC 5.2 applies to a head unit
exactly as it does to a screen) and a device test in an actual car or the Desktop Head Unit.

### 4. Bookmarks — **PRODUCT_SPEC 11.1 responsibility + recommended feature #4, unbuilt**

> expose custom commands for **bookmark**, sleep timer, mark finished, and download when supported

Section 8 lists "bookmarks with optional short notes" as recommended feature #4; `BookmarkEntity` is already in
the spec's schema. The player has carried a **disabled bookmark button since wave 2** — a visible promise we
have not kept.

**Benefit:** high for the target user. It is the one feature in this list that changes how somebody uses a
long book, and the button is already on screen.
**Effort:** medium, and it is the only item here that needs the **server**: Audiobookshelf has bookmark
endpoints, none of them captured. PRODUCT_SPEC 22.4/22.5 forbid building on an unverified shape, so this
starts with a capture — the same discipline wave 0 used.

### Also worth doing, and cheap

**A chapter progress bar under the book's.** Absorb calls it "dual progress bars (book + chapter)" and the
official app has it too. We show a book bar plus the chapter *title*, so there is no way to see how far
through the current chapter you are — the question somebody asks before deciding whether to stop. Purely
additive UI, no new data: `GlobalTimeline.chapterAt` already gives the bounds. ✅ **Built in wave 5
alongside item 1**, because it is small and it is the highest ratio of noticed-benefit to risk in the list.

## What the owner decided, 2026-08-13

This document originally declined equaliser, car mode, widgets, statistics and a queue as scope PRODUCT_SPEC
does not ask for. The owner's answer was to **want all of them**, and to place them:

> Else I also do want equaliser, car mode, widgets, statistics and a queue. For carmode I want android auto,
> widgets should always display the latest played book, and the queue should just start the next book in the
> series. … equaliser, car mode, widgets, statistics neither has to start now, they can come in a later
> phase.

Where each one lands:

| Feature | Phase | What was decided |
| --- | --- | --- |
| **Car mode = Android Auto** | **2** | Not oversized on-screen controls — the head unit. Already gap 3 above, and already a PRODUCT_SPEC 11.1 responsibility, so nothing moves. |
| **Queue = smart download** | **3** | Not a reorderable list: prefetch the next book in the series. Conditions recorded in [ADR-0017](adr/0017-smart-download-drives-the-series-queue.md) — over halfway, book over an hour, setting on, Wi-Fi only. |
| **Widgets** | later | Always showing the **latest played book**, which makes it a home-screen resume button rather than a second player. |
| **Listening statistics** | later | See the note below — it is the only one with a "start now" argument, and the argument does not survive. |
| **Equaliser** | later | Media3 has `AudioProcessor`; nothing about it constrains the player built here. |

### The one "does it have to start now?" question, and its answer

Only **statistics** has an argument for starting early, and it is not about the screen: statistics needs
*history*, and a feature that arrives in Phase 5 cannot invent data Phase 2 threw away. The app's
`playback_sessions` outbox compacts synced rows after seven days, which is right for a sync queue and wrong
for an archive.

It still does not force work into Phase 2, because **the history is the server's**. Audiobookshelf keeps
listening sessions itself, and reading them back is a request rather than a schema. What that costs is a
capture rather than a migration, and PRODUCT_SPEC 22.4/22.5 mean the capture has to come before anyone
relies on the shape — the same discipline bookmarks needs.

So: nothing is pulled into Phase 2 by any of these. The only Phase 2 item among them is Android Auto, and it
was already on the list.

## Revised wave 5

1. ✅ **One timeline window** (ADR-0016) + the chapter progress bar — built, awaiting a device run.
2. **Media-button resume and playback resumption** (ROUTE-001) — closes an exit criterion. ← *next*
3. **Android Auto browse tree** — closes PRODUCT_SPEC 11.1.
4. **Bookmarks**, starting with a contract capture.
5. The small closeout items already named: `markAsFinishedTimeRemaining` (ADR-0013's other half), rebuffer
   count and startup latency in diagnostics (PLAY-006's last criterion).
6. **The two-hour soak and the rest of the exit criteria.** Last, because everything above changes what the
   soak would be testing.

Items 2 and 3 are the ones that decide whether Phase 2 is finished. Item 4 is the one a user would thank us
for.
