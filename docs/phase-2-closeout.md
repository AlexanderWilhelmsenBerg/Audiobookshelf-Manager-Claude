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
| Widgets | — | ✅ | ✅ | — Phase 6 |
| Listening statistics | ✅ | ✅ | ✅ | — |
| Equaliser | — | ✅ | — | — not in spec |
| Chromecast | ✅ | ✅ | — | — not in spec |
| Auto-play next in a series | — | ✅ | — | — Phase 4 |
| Queue / up-next, drag to reorder | — | — | — (SoundLeaf) | — not in spec |
| Car mode (oversized controls) | — | ✅ | — | — not in spec |

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

## What is deliberately not recommended

- **Equaliser, Chromecast, car mode, widgets, listening statistics.** Real features in rival apps, none of them
  in PRODUCT_SPEC, and none of them Phase 2. Adding scope the spec does not ask for while an exit criterion is
  unmet would be the wrong trade.
- **Queue / up-next.** Only SoundLeaf has it, and an audiobook is not a playlist. Phase 4's "auto-play next in
  a series" covers the case people actually mean.
- **Skip silence / volume boost.** Not in the spec, and not offered by the clients compared here either — the
  earlier impression that Absorb had them was wrong; its feature list does not mention either.

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
