# Phase 2 closeout — one pull request per gap

Written after PR #12 merged, at the owner's request: *"make a plan to fill all the gaps. I want to handle
each gap as it's own pull request."*

`docs/phase-2-gaps.md` is the checklist. This is the order it gets closed in, and what each pull request
owns. **Six pull requests, two hardware runs.** The two runs cannot be a pull request — nobody can merge a
drive — so they are listed last as what they are.

Sequencing is value first: the feature with a dead button in the UI since wave 2, then the four small
requirement clauses, then the two device-policy requirements. Both ROUTE items are in scope by the owner's
decision, recorded below.

## The order

| # | Branch | Requirement | Size | Why here |
| --- | --- | --- | --- | --- |
| ~~1~~ | `claude/phase-2-bookmarks` | 11.1 | L | ✅ **done** — a disabled button since wave 2; the capture that unblocked it is in |
| 2 | `claude/phase-2-finished-threshold` | PLAY-004 | M | Two clauses of one requirement, and one of them is a live defect |
| 3 | `claude/phase-2-duck-or-pause` | PLAY-002 | S | One setting, one branch in the player |
| 4 | `claude/phase-2-buffer-diagnostics` | PLAY-006 | S | Two readings the buffer work already half-collects |
| 5 | `claude/phase-2-route-002` | ROUTE-002 | L | Eleven criteria about device identity |
| 6 | `claude/phase-2-route-003` | ROUTE-003 | S | Three-way startup setting; two thirds true by accident today |
| — | *no branch* | exit criteria | — | The soak and the car. Hardware, not code |

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

**Scope**

- Capture the library-settings endpoint **first**, in the same pull request, and build only on what it
  returns (22.4/22.5). If it does not expose the value, the pull request says so and ships the configurable
  threshold alone rather than guessing.
- `FinishedThreshold` gains the library's time-remaining rule beside the percentage, and the percentage
  becomes a setting with the spec's 90–99% range.
- **Lengthen the seeded contract book past the threshold**, so un-finishing becomes demonstrable in a
  fixture at all. This moves a duration in about a dozen committed fixtures, which is churn — and it
  belongs here, because this is the pull request that cares about the number.

**Tests.** A threshold table test at the range's ends; the un-finish round trip, which only becomes
possible once the seeded book is long enough.

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

## The two that cannot be pull requests

**The two-hour streaming soak.** An exit criterion. It needs two hours of real audio over a real network,
and what it is looking for is the thing no unit test reaches: whether a `ConcatenatingMediaSource2` crosses
file boundaries gaplessly at 1.5× on a cold buffer, whether the journal drifts, whether the outbox grows.
`docs/phase-2-gaps.md` keeps it as 🔬 until it has been run once and recorded.

**Android Auto in a car.** Two device runs have failed to find the app. Everything the APK controls is
verified correct against the shipped binary, and 0.8.0 added the five readings that say what is not —
including **Installed by** and **Last car connection**. The next attempt is a drive, not a commit. If *Last
car connection* still reads never afterwards, the next pull request is about discovery; if it reads a time,
the browse tree is in play and that is a different pull request. **Which one it is cannot be known before
the drive**, so neither is in this plan.

## What stays out, and why

Recorded so it is not mistaken for an omission:

- **PLAY-006's Advanced buffer mode** — declined; the presets cover the preference.
- **Equaliser, widgets, statistics** — the owner's, for a later phase (`docs/phase-2-closeout.md`).
- **Downloads and the smart-download queue** — Phase 3 (ADR-0017).
- **`CHANGELOG.md` stops at Phase 1.** Five waves of Phase 2 live in `docs/phase-2-gaps.md` instead.
  Retro-filling it is worth doing and is not a gap in the product; it rides along with PR 1 rather than
  taking a pull request of its own.
