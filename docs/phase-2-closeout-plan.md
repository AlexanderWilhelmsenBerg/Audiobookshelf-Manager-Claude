# Phase 2 closeout — one pull request per gap

Written after PR #12 merged, at the owner's request: *"make a plan to fill all the gaps. I want to handle
each gap as it's own pull request."*

`docs/phase-2-gaps.md` is the checklist. This is the order it gets closed in, and what each pull request
owns. **Seven pull requests, one hardware run.** The run cannot be a pull request — nobody can merge a drive — so
it is listed last as what it is.

Updated 2026-08-14, after the drive. Two of the three things this plan was waiting on hardware for came back
**passing**: Android Auto discovery and media-button resume. The third — the two-hour soak — has not been
run. And the drive produced one new pull request, because an app that is finally *in* the car turns out to
show the wrong thing in it.

Sequencing is value first: the feature with a dead button in the UI since wave 2, then the four small
requirement clauses, then the two device-policy requirements. Both ROUTE items are in scope by the owner's
decision, recorded below.

## The order

| # | Branch | Requirement | Size | Why here |
| --- | --- | --- | --- | --- |
| ~~1~~ | `claude/phase-2-bookmarks` | 11.1 | L | ✅ **done** — a disabled button since wave 2; the capture that unblocked it is in |
| ~~2~~ | `claude/phase-2-finished-threshold` | PLAY-004 | M | ✅ **done** — both clauses, and the rule moved out of the player |
| 3 | `claude/phase-2-duck-or-pause` | PLAY-002 | S | One setting, one branch in the player |
| 4 | `claude/phase-2-buffer-diagnostics` | PLAY-006 | S | Two readings the buffer work already half-collects |
| 5 | `claude/phase-2-route-002` | ROUTE-002 | L | Eleven criteria about device identity |
| 6 | `claude/phase-2-route-003` | ROUTE-003 | S | Three-way startup setting; two thirds true by accident today |
| ~~7~~ | `claude/phase-2-auto-library` | PLAY-001, 11.1 | M | ✅ **done** — the car shows the phone's shelves, from the phone's own use case |
| — | *no branch* | exit criterion | — | The two-hour soak. Hardware, not code |

Each pull request is a draft, carries its own device-test section where one is needed, and updates
`docs/phase-2-gaps.md` for the rows it closes. No pull request depends on another except where stated.

---

## PR 1 — Bookmarks (PRODUCT_SPEC 11.1)

**Why first.** It is the only gap that is a *feature* rather than a clause, the player has shown a disabled
bookmark button since wave 2, and it was blocked for a reason that no longer holds: PRODUCT_SPEC 22.4/22.5
forbid building on an unobserved shape, and the shape is now recorded and pinned by `CapturedShapesTest`.

**What the capture settled**, and each item is a thing a client written from memory gets wrong:

| Fact | Consequence for the design |
| --- | --- |
| `POST /api/me/item/{id}/bookmark` `{time, title}` → the bookmark | Create is a write with a readable result |
| A bookmark has **no id**; it is keyed by `time` in whole **seconds** | The local primary key is `(profile, book, seconds)`. Two bookmarks in the same second are not expressible, and the UI must say so rather than silently replacing one |
| They live on `user.bookmarks`, one flat array across every book | A book's bookmarks are a filter on `libraryItemId`, and a full read arrives with `GET /api/me` — which the app already calls on every profile refresh |
| `DELETE …/bookmark/{seconds}` → `200 text/plain "OK"` | The success path is not JSON. Parsing it as JSON throws on the happy case |

**Scope**

- `core/model`: `Bookmark(bookId, at: Duration, title: String, createdAt: Instant)`.
- `core/network`: `BookmarkDtos`, four routes on the existing authenticated service, mapped through
  `AbsBookmarkApi`. A contract test per route against the committed fixtures.
- `core/database`: a `bookmarks` table, profile-scoped with the same cascade as `playback_history`.
  **Additive migration, version 13.** Read back from `GET /api/me` on refresh, so the table is a cache of
  the server's array rather than a second source of truth.
- `domain`: `BookmarkRepository` — `observe(bookId)`, `add(bookId, at, title)`, `rename`, `remove`.
  Local-first with `hasUnsyncedChanges`, exactly as progress does, so a bookmark made on a train survives.
- `:playback`: enable the existing custom session command, so a car and a headset can drop a bookmark
  without the app open. This is the half that makes it a Phase 2 item rather than a phone feature.
- `:app`: the button becomes live; a bookmarks sheet beside the chapter and history sheets, sharing the
  row-tap-to-seek behaviour the history pane already has; the book screen lists them.

**Tests.** Contract tests for four routes; repository tests for the offline path and for the
same-second collision; a screen test for the sheet; a migration test.

**Risks.** The seconds-keyed identity is the one to watch — a listener who bookmarks twice within a second
must get a comprehensible result, and the server will simply overwrite.

---

## PR 2 — The finished threshold, both clauses (PLAY-004)

**Two rows of the gap table, and they are the same code path**, which is why they are one pull request:

- **`markAsFinishedTimeRemaining` from library settings** — ADR-0013's unbuilt half.
- **A configurable finished threshold**, 90–99%. The app hard-codes 95%.

The 2026-08-13 capture is the reason this is second rather than fifth: the CI server log showed
`markAsFinishedTimeRemaining` overruling an un-finish on an eight-second book, which is the first time the
setting has been *observed*. The app does not read it, so a library configured with a two-minute threshold
gets the app's 95% instead — books marked finished at the wrong moment, in both directions.

**No capture is needed, and the plan was wrong to say one was.** `GET /api/libraries` already carries a
`settings` object, and the committed `libraries.json` has held both fields since the wave A capture:

```
"markAsFinishedTimeRemaining": 10,
"markAsFinishedPercentComplete": null
```

So this implements a decision that is **already recorded** rather than making one. ADR-0013 states the rule
in full, including why it is a `max`:

```
finishedWhenRemaining = max(the user's setting, library.markAsFinishedTimeRemaining ?: 0s)
```

The asymmetry is the design: the app must never be the one calling a book unfinished that the server has
finished, because the two rules then disagree and the book oscillates every time either syncs. And ADR-0013
already names the configurable range as a **duration, 5–120 seconds** — PLAY-004's literal 90–99% was
deviated from deliberately, because 95% of a ten-hour book is half an hour from the end.

**Scope**

- `LibraryDto` gains `settings`; `Library` carries the rule; Room's `library` table gains two nullable
  columns. **Additive migration, version 14.**
- `FinishedThreshold` stops being a constant and takes the library's rule and the user's setting.
- The **caller** changes shape too, and this is the part worth doing carefully. `PlaybackService` currently
  computes `isFinished` itself and passes it to `recordPosition`, which means the service needs the rule. It
  should not: the repository already resolves the profile and the book on every write, so it is the one place
  that can resolve the library's setting as well. Moving the decision there leaves one place that knows.
- The setting itself: 5–120 seconds under Settings → Playback, defaulting to ADR-0013's 30.

**Tests.** A table test over the `max`, including a library more eager than the user's setting and one less
eager; the unknown-duration case, which must never be finished; and the service no longer being able to get
it wrong, because it no longer decides.

**Not in scope, and previously mis-scheduled here:** lengthening the seeded contract book. That was for
demonstrating the un-finish round trip in a fixture, which the threshold work does not depend on — the
threshold is unit-tested against synthetic durations. It stays outstanding, noted in
`docs/api-compatibility.md`.

### Done, 2026-08-14 — and three things the scope above did not say

Merged as build 0.9.2. Everything above shipped. What the plan did not anticipate:

- **No percentage, and the library's value is inherited rather than merged.** Both on the owner's
  instruction, from the device run on the same build: *"I do not want a percentage… for a 100 hour book it
  would mark it finished with 5 hours left"*, and *"instead of fighting the server, have them merge — inherit
  from the web interface."* So `markAsFinishedPercentComplete` is not read at all, and the `max` is gone: a
  library's `markAsFinishedTimeRemaining` **is** the rule for its books, and the setting is the fallback for a
  library that sets none. ADR-0013 carries both reversals and why the `max` was the weaker idea.
- **`FinishedThreshold` moved from `:domain` to `:core:model`.** Its range and default now have three
  readers — the settings model, the settings store's clamp, and the chips — and `:core:datastore` cannot see
  `:domain`. Two copies of one range is one range and one bug.
- **The Playback tab names any library that overrules the chosen value.** A `max` the user cannot see is a
  setting that lies: choose 30 seconds against a library asking for 90 and the tab says so, with both
  numbers. That is also the one place in the app with a test tag, because three chip rows on that tab offer
  the same eight labels.

---

## PR 3 — Duck instead of pause (PLAY-002)

The requirement says a transient audio-focus loss pauses **or ducks according to a setting**. There is no
setting; the app always pauses. A satnav instruction currently stops an audiobook and restarts it, which is
the behaviour most listeners change first in any player that offers the choice.

**Scope.** One setting under Settings → Playback. Media3 handles the ducking itself once the focus request
says so, so the work is the setting, the plumbing to `PlayerFactory`, and making sure auto-rewind does
**not** fire on a duck — a ducked book never stopped, and rewinding it would replay ten seconds every time a
notification chimed.

**Tests.** The auto-rewind interaction is the one worth a test; the setting itself is a DataStore field.

---

## PR 4 — Rebuffer count and startup latency (PLAY-006)

The requirement asks for both in diagnostics. Neither is collected. They are the two numbers that turn "it
kept stopping on the train" into something a buffer preset can be chosen against, and they belong beside
the readings that are already on the About tab.

**Scope.** Count `STATE_BUFFERING` transitions that are not the first, and measure first-frame latency from
`prepare()` to `STATE_READY`, both in the service where the player lives. Two rows under Testing, and both
in the event log so a support report carries them.

**Deliberately not** PLAY-006's Advanced buffer mode — a five-field form for a preference the five presets
already cover. Recorded as declined in `docs/phase-2-gaps.md`, not as missing.

---

## PR 5 — ROUTE-002, per-device playback policy

**In scope by the owner's decision.** PRODUCT_SPEC's own Phase 2 deliverable list names only ROUTE-001, and
this is eleven acceptance criteria about device identity rather than about playing a book — but the owner
asked for every gap filled, so it is filled.

What exists today is the *behaviour* the policies choose between: one global "start playing when a car
connects" switch, off by default. What is missing is the **registry**.

**Scope**

- A `playback_devices` table: the device's identity as the platform reports it, a display name, a last-seen
  date, and the chosen policy. **Additive migration, version 14** (or 13 if PR 1 has not merged — the
  numbering is settled by merge order, and each pull request checks).
- Four policies per device: `Never react`, `Arm only`, `Auto-play`, `Ask`. The existing global switch becomes
  the default for a device nobody has configured, so nobody's current behaviour changes on upgrade.
- The two rules the requirement names beside the policies: a **locked profile** never auto-plays, and a
  **speaker** is not a device that may.
- Settings → Playback → In the car becomes a list of devices with their policies and last-seen dates.

**Risks.** This is the only pull request in the plan that can start audio on its own, and `Ask` needs a
surface that is safe to use while driving. The honest fallback for `Ask` is arming rather than prompting
when the app has no foreground window.

---

## PR 6 — ROUTE-003, startup mode

"App launch alone never starts playback" and "no foreground service from boot" both hold today, and both
hold **by accident** — nothing starts playback automatically at all. The three-way profile setting does not
exist, so the requirement is two thirds satisfied and zero thirds implemented.

**Scope.** The three-way setting, and a test for each branch. Small, and last because it is the least
consequential thing on the list.

---

---

## PR 7 — what the car actually shows (PLAY-001, 11.1)

**New, and it exists because the drive happened.** Two rounds were spent on whether the app appeared in a
car at all. It does now — and the first screen reads **"no books"**, while voice search finds them.

### The empty tab, diagnosed

`AutoLibrary.continueListening()` filters on `book.progress?.isFinished == false`. A book that has never
been played has no progress, so `progress?.isFinished` is `null`, the comparison is false, and the book is
excluded. The Continue tab therefore contains only books that have been *started and not finished* — on a
fresh library, none of them. Search is unaffected because it reads the whole list.

One line fixes the emptiness. It is also the least interesting part of the request.

### What was actually asked for

*"I would like the same library setup as the app. And the same player when playing."*

The app's shelf is several shelves — Continue listening, Continue a series, Recently added, Listen again,
Discover — plus browse by series, author and genre. The car has one filtered list and two panes about the
playing book. So this is not a bug fix, it is the browse tree growing to match the library the app already
models.

**Scope**

- The root's tabs become the app's structure rather than a player's: **Continue** (fixed, and falling back
  to recently-added when nothing is in progress, so a fresh library is never empty), **Library** browsing by
  series / author / genre through the nodes `LibraryRepository` already exposes, and the two panes that are
  about the playing book — Chapters and History — kept.
- Paging is already in place (`onGetChildren` takes `page` and `pageSize`); the new nodes have to respect it,
  because a 490-book library in one binder transaction is a transaction a head unit refuses.
- The grant filter stays where it is: every read goes through `observeAccessibleBooks`, so a library this
  profile has lost cannot appear on a dashboard (PRODUCT_SPEC 5.2).

**"The same player when playing" needs stating carefully.** An app cannot draw a player in a car — Auto
renders its own from the media session and the metadata, and there is no surface for a custom view. What
*can* be matched is everything that feeds it: cover art, title, author, chapter as the current metadata,
the configured skip amounts on the transport, and the speed and sleep-timer commands as custom actions.
That is the honest reading of the request and it is what the pull request should deliver; promising a
ShelfPlayer-looking screen in a car would be promising something the platform does not allow.

**Tests.** `AutoLibrary`'s tree is testable without a car — its id protocol and root are already on the
companion for that reason — so each new node gets a test, and the fresh-library case gets one specifically:
**a library with no progress must not produce an empty Continue tab.**

**Risks.** This is the surface with the longest feedback loop in the project: every mistake costs a drive.
The tests should therefore cover the *shape* of the tree exhaustively, so what a drive is checking is
rendering rather than logic.

---

## The one that cannot be a pull request

**The two-hour streaming soak.** An exit criterion. It needs two hours of real audio over a real network,
and what it is looking for is the thing no unit test reaches: whether a `ConcatenatingMediaSource2` crosses
file boundaries gaplessly at 1.5× on a cold buffer, whether the journal drifts, whether the outbox grows.
`docs/phase-2-gaps.md` keeps it as 🔬 until it has been run once and recorded.

### Settled by the 2026-08-14 drive

- **Android Auto discovery** — the app appears, browses and plays. Three rounds; the last two were spent on
  a build that was already correct, because nothing in the app could report the phone's *Unknown sources*
  setting. It can now.
- **Media-button resume (ROUTE-001)** — a headset play against a dead process resumes the last unfinished
  book, and since 0.8.0 a different book can then replace it. Both halves were needed, and the first hid
  the second for an entire build.

## What stays out, and why

Recorded so it is not mistaken for an omission:

- **PLAY-006's Advanced buffer mode** — declined; the presets cover the preference.
- **Equaliser, widgets, statistics** — the owner's, for a later phase (`docs/phase-2-closeout.md`).
- **Downloads and the smart-download queue** — Phase 3 (ADR-0017).
- **`CHANGELOG.md` stops at Phase 1.** Five waves of Phase 2 live in `docs/phase-2-gaps.md` instead.
  Retro-filling it is worth doing and is not a gap in the product; it rides along with PR 1 rather than
  taking a pull request of its own.
