# ADR-0021: Source-file deletion ships no feature, because the server cannot confirm one

- **Status:** Accepted
- **Date:** 2026-08-15
- **Requirements:** PRODUCT_SPEC MGR-005, MGR-006; CLAUDE.md principle 5 ("destructive actions must
  describe their actual effect")
- **Related:** ADR-0012 (the official project as an API reference)

## Context

MGR-006 is written defensively, and the defence turned out to be pointed at the wrong risk.

Its first criterion is *"the action does not exist unless the connected server reports a dedicated, tested
source-file-delete capability"*, and `docs/phase-5-plan.md` recorded slice 7 as possibly ending with no
feature — on the assumption that no such endpoint might exist. Reading the server's source at 2.36.0
(ADR-0012's amendment) shows that two do:

- `DELETE /api/items/{id}?hard=1` deletes the database rows and then recursively removes the item's
  directory from the server's filesystem. Without `hard`, the same route deletes only rows, which is the
  MGR-005 operation.
- `DELETE /api/items/{id}/file/{ino}` removes a single file and updates the item to stop listing it.

So the endpoints exist, the flag is the difference between the two requirements, and the first criterion
could be met.

## The problem

MGR-006's fifth and seventh criteria cannot be met, and the reason is in the server rather than in
this app:

> The server response must explicitly confirm deletion.
> If the server cannot prove deletion, the UI reports uncertain state and does not claim success.

On both routes the filesystem removal is attempted and **a failure is logged on the server and then
discarded**. The request succeeds either way. A hard delete answers `200 OK`; a file delete answers with
the updated item. Neither says anything about the bytes.

That is not a rare edge. A read-only mount, a container that lost its bind mount, a permission problem, a
file held open by another process, a network filesystem that dropped — every one of them produces the
response a successful deletion produces.

Nor can the app verify afterwards. Once the file is gone from the item's file list, asking for it returns
`404` whether or not it is still on disk, because that `404` is generated from the item's list. The check
that looks like proof is proof of nothing.

## Decision

**BookWave offers no source-file deletion, and `ServerCapability.SourceFileDelete` is never confirmed by
any probe.**

Not "not yet" and not "pending a capture". A capture cannot help: every response that route can produce
is a success, including the ones where the file survived. The evidence needed is evidence the protocol
does not carry.

The capability stays in the enum, and the code that gates on it stays, so that a future server version
which does confirm the deletion needs a probe rather than a rewrite.

## What ships instead

MGR-005, unchanged and clearly labelled: `Remove from Audiobookshelf database`, whose confirmation states
that media files remain on the server and that a later scan may re-add the item. That is exactly what the
un-flagged `DELETE` does, and it is now confirmed rather than assumed.

The rule this project has carried since CLAUDE.md was written — *no claims that the Audiobookshelf
database-delete endpoint removes media files* — turns out to be precisely right, and now has a mechanism
behind it: the flag that *would* remove them is the one this app does not send.

## Alternatives considered

**Ship it with a warning that the result is unverified.** Rejected. MGR-006 requires typing `DELETE` to
confirm, which is a ritual that communicates certainty. A dialogue that demands that ceremony and then
reports "possibly deleted" is worse than no feature: it trains the user to believe the ceremony means
something.

**Ship it and verify by re-scanning the item.** Rejected, and it is the tempting one. A scan reports
`REMOVED` when a file is gone from the library — but the scan reads the same directory the delete claimed
to empty, and a delete that silently failed leaves a file the scan will happily re-add. The scan would
then report `NOTHING` or `ADDED`, which the app would have to interpret, and the interpretation is a guess
about why (PRODUCT_SPEC 22.4). It also costs a server-side scan per deletion.

**Ship the per-file variant only.** Rejected for the same reason as the first: the honesty problem is the
acknowledgement, not the blast radius.

## Consequences

- Phase 5 slice 7 is complete and correct with no user-facing change. `docs/gaps.md` records this as a
  decision rather than an omission.
- The three-dot menu's destructive section has exactly one entry, whose label says what it does.
- If Audiobookshelf later returns a deletion result, this ADR is superseded by adding a probe. Nothing
  written under it has to be undone.
