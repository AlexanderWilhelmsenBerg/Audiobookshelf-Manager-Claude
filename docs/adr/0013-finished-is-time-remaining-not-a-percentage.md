# ADR-0013: "Finished" is time remaining, not a percentage

- **Status:** Accepted
- **Date:** 2026-08-07
- **Requirements:** PRODUCT_SPEC PLAY-004. **Deviates from its literal wording** — see below.
- **Decided by:** the project owner, in response to the conflict recorded in `docs/api-compatibility.md`

## Context

PLAY-004 says: *"A finished threshold defaults to 95% and is configurable from 90–99%."*

The server does not work that way. It marks a book finished from the **library's** own settings —
`markAsFinishedTimeRemaining` and `markAsFinishedPercentComplete` — which are in the committed
`libraries.json` and, on the capture server, read `10` seconds and `null`.

So there were two rules for the same question, and they disagree in a way the user sees. Ten seconds
remaining on a ten-hour book is 99.97%; 95% of the same book is **thirty minutes** from the end. Under
the requirement as written, the app would call a book finished with half an hour left in it.

That is the part worth stating plainly: PLAY-004's percentage is not a good rule for audiobooks. A
percentage of a long book is a long time. What listeners mean by "finished" is "the narration stopped",
which is a duration from the end, not a fraction of the whole.

## Decision

**A book is finished when 30 seconds or less remain.** Time remaining, not percentage.

The app never applies a threshold *less* eager than the server's:

```
finishedWhenRemaining = max(30s, library.markAsFinishedTimeRemaining ?: 0s)
```

and a book the server reports as `isFinished` is finished, regardless of position.

### Why `max` rather than simply 30 seconds

The two rules have to agree, or a book oscillates: marked finished in one place, unfinished in the
other, changing every time either syncs.

- Where the server is **less** eager than 30 s — the capture server, at 10 s — the app finishes first,
  sends `isFinished`, and the server accepts it. Everything the server would call finished, the app
  already has. Consistent.
- Where the server is **more** eager — a library configured at 60 s — taking the server's number is
  what keeps them in step. Without `max`, the server would finish books the app still showed as in
  progress.

So the app is never the one that says "not finished" about a book the server has finished. That
asymmetry is the whole design.

## Consequences

- **This deviates from PLAY-004's literal 95% / 90–99%.** Recorded here rather than silently
  implemented. The requirement's *intent* — a book near its end counts as done, and the user can tune
  it — is preserved; its unit is not.
- The configurable range becomes a duration. A sensible span is 5–120 seconds, matching the skip
  controls PLAY-007 already uses, and that is what the setting will offer when SET-002 grows it.
- `markAsFinishedPercentComplete` is read but has no effect while it is `null`, which is the only
  value any capture has shown. If a server sets it, the same `max` logic applies: the app must not be
  less eager than a server that finishes on percentage either. **Unverified** — no fixture has a
  non-null value, so this stays unimplemented until one does (PRODUCT_SPEC 22.5).

## The fixture cannot demonstrate the unfinished case

Worth recording so nobody reads `me-after-session.json` as a normal mid-book state. The fixture book is
**eight seconds long** and the library's rule is ten seconds remaining, so *every* position in it
leaves less than ten seconds and the server marks it finished — even the corrected 4.5-second sync,
which is 56% through.

The seed now also creates a book long enough to sit mid-progress, so a future capture can record an
`isFinished: false` state. The *shape* is identical either way, which is what 22.5 requires; only the
values differ.

## Implemented, 2026-08-14 — and what changed from the decision above

Phase 2 closeout PR 2. Until then this ADR was accepted and **not implemented**: `FinishedThreshold`
was an `object` with a hard-coded 30 seconds, and `LibraryDto` parsed `settings` away entirely — so the
`max` had only one operand and the library's rule reached nothing. Both clauses are now real.

Three things the decision above did not say, decided while building it:

- **`markAsFinishedPercentComplete` is applied, not deferred.** The consequence above says it "stays
  unimplemented until" a capture produces a value. That was the wrong call, and the reasoning is the
  ADR's own: the asymmetry argument does not depend on having seen a value. A library configured on a
  percentage would finish books this app still showed as in progress, which is exactly the oscillation
  the `max` exists to prevent — and there is no way for the app to *notice* that happening. So the
  branch is written to the documented field, honoured when non-null, and dropped rather than clamped
  when out of range. It remains **unverified**: `CapturedShapesTest` asserts the field is sent and that
  no capture has ever given it a value, so the day one does, the assertion is where the news arrives.

- **The decision lives in the repository, not in the player.** `PlaybackService` used to compute
  `isFinished` and pass it to `recordPosition`, which put the rule in the one place that could see
  neither half of it. `DefaultPlaybackRepository` already resolves the profile and the book on every
  write, so it resolves both halves too, and `PlaybackRepository.recordPosition` no longer takes the
  flag. One place knows the rule.

- **The library's number is shown in Settings.** A `max` that silently overrules the chosen value is a
  setting that lies. The Playback tab names any library asking for longer than the chosen threshold, and
  says which number wins.

The setting is 5–120 seconds under Settings → Playback, defaulting to this ADR's 30, stored as
`finished_threshold_seconds` and clamped on both read and write. The library's half is stored per
library in Room (schema 14, `finishedTimeRemainingSeconds` and `finishedFractionComplete`) — nullable,
because a library that has not asked for a rule is not a library asking for zero seconds.

## Revised the same day, on the owner's instruction — inherit, do not merge

The device run on build 0.9.2 produced two corrections, both from the owner and both narrowing this ADR
rather than widening it. They are recorded here because the section above is now wrong in two places.

### 1. No percentage, anywhere

> *"For the marking as finished, I do not want a percentage. It does not make sense. For a 100 hour book,
> it means 95%, it would mark it finished with 5 hours left."*

The implementation note above argued for honouring `markAsFinishedPercentComplete` on asymmetry grounds.
That argument was about a rule the owner does not want the app to hold in any form, and the example settles
it: five hours of an audiobook is not "finished" under any reading. The field is no longer parsed, no longer
stored, and no longer part of `FinishedThreshold`. `CapturedShapesTest` pins it as *sent and deliberately
unread*, so a later reader finding it in the fixture does not mistake the omission for an oversight.

The consequence, stated plainly: **if a library on the server is configured with a percentage and no time
remaining, this app will not honour it.** That is a deliberate divergence from the server, unlike everything
else here, and it is the owner's decision.

### 2. The library's value is inherited, not merged

> *"Instead of fighting the server, have them merge. Inherit from the web interface."*

The `max` is gone. Where a library sets `markAsFinishedTimeRemaining`, **that is the number the app uses**;
the listener's setting applies only to a library that sets none.

The `max` was the weaker idea, and the reason is the ADR's own argument turned around. It bounded the
disagreement instead of removing it: with the app at 30 s against the capture server's 10 s, a book was
finished in ShelfPlayer twenty seconds before the web interface agreed, and a listener switching between the
two saw a book whose state depended on where they looked. Inheriting leaves one rule per book, and it is the
one the server's own interface displays.

What the `max` protected against is still handled, just not here:

- a book the server reports `isFinished` is finished regardless of position, and
- a locally finished book is never quietly un-finished (`DefaultPlaybackRepository` or-s the flag).

So the app still cannot contradict the server in either direction.

### A note on the database version, because it cost a start-up crash

Removing `finishedFractionComplete` was done, at first, by editing **version 14's** schema in place — on the
reasoning that version 14 had not shipped. It had: build 0.9.2 carried it to the owner's phone an hour
earlier. Room stores a version's identity hash and compares it on open, so the next build crashed at startup
on that one device and would have on no other. Version 14 is now left exactly as it shipped, with both
columns, and **version 15** removes the column by the table rebuild SQLite requires before 3.35.
`MigrationTest` opens a database shaped as 0.9.2 left it, so the mistake is a failing test now rather than a
failing phone.

The listener's setting keeps its 5–120 second range and its 30-second default, and the Playback tab now
lists **every** library with what it actually uses — the inherited seconds, or a line saying it has none and
follows the setting. An earlier build listed only libraries that differed from the chosen value, which
displayed nothing on exactly the server where the chips were doing nothing at all.

## Revised a third time — the app keeps no threshold at all

The owner, after the 0.9.4 device build:

> *"The only thing missing is that the app should update the marked finished seconds on the server as well, so
> these match. If this is not possible then remove it from setting. Since this setting is library specific it
> could be difficult."*

It is not possible in the sense asked for, so the setting is gone. Three facts, in the order that settles it:

1. **There is no per-user finished threshold on the server to match.** `contracts/me.json`'s user object has
   no `settings` key at all — it carries `permissions`, `bookmarks`, `mediaProgress`, `librariesAccessible`
   and identity fields, and nothing resembling a playback preference. So "make the two match" cannot mean
   "sync a per-listener value"; there is no such value to sync with.

2. **`markAsFinishedTimeRemaining` belongs to the library, not to a person.** Every account with access to that
   library reads the same number. Writing it from one listener's phone reconfigures the library for everybody
   on the server, and a non-administrator account cannot write it at all — so the app would need to hide the
   control by role, and the control would still be misrepresenting itself as a personal setting.

3. **`library.settings` carries twelve fields and this app models one.** The others are the server's own
   scanning and matching behaviour: an ordered `metadataPrecedence` array, `disableWatcher`,
   `autoScanCronExpression`, four `skipMatching*`/`hide*` flags. No capture shows whether the server merges a
   partial settings PATCH or replaces the object. If it replaces, a write-back from this app would silently
   discard eleven settings it deliberately does not understand, on a library shared with other people.
   PRODUCT_SPEC 22.4 forbids relying on unobserved server behaviour, and destroying somebody's library
   configuration is exactly the outcome that rule exists to prevent.

So the app has no threshold. `FinishedThreshold` holds the library's value and nothing else; `Default` — still
thirty seconds — is a fallback for a library whose settings have not been read yet, not a preference. The
proto field is reserved rather than removed, because build 0.9.2 wrote it on a device.

### This is now a real deviation from PLAY-004, and it is wider than before

PLAY-004 says the threshold is **configurable**. In this app it is not: there is no control, and the only way
to change it is the Audiobookshelf web interface, per library. The requirement's intent — that a listener can
tune when a book counts as finished — is met only in the sense that a self-hosted user is also their own
administrator. On a server they do not administer, they cannot tune it at all.

That is the owner's decision, taken with the alternative in front of them, and it buys something the
requirement did not anticipate: the app and the web interface can never disagree about whether a book is
finished. Recorded here as a deviation rather than quietly satisfied, per PRODUCT_SPEC 22's rule about
requirements being contractual.

### What the Settings screen does instead

The Playback tab keeps a **Finished** section that is a reading rather than a control: it names every library
and the number in force for its books, says which libraries the server has not answered for and what fallback
is being used, and states where the value is changed — the web interface, under that library's settings.

A screen that dropped the subject entirely would leave the app's most surprising behaviour unexplained. A
listener who watches a book finish with a minute left has to be able to find out that their library asked for
a minute.
