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
