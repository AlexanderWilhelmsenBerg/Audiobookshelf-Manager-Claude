# Phase 5 — Management tools: the plan, and what has to happen first

**Status: Phase 5 is complete.** All eight slices are done, one of them correctly with no feature. The captures came back, the four questions they could not answer
were settled from the Audiobookshelf project's own source, and one of those answers ended a slice with no
feature — correctly. 

## The problem this phase starts with

Phase 5 is the first phase where **this app writes to the server**. Every phase before it read, and the one
write it does make — progress and bookmarks — was captured before it was built.

Nothing in EPIC MGR has ever been captured. Not one management endpoint's request shape, response shape,
status code or failure mode. And PRODUCT_SPEC is explicit about what that means:

> **22.4** Do not invent server endpoints.
> **22.5** Add or update a contract fixture before relying on a new response shape.

So the honest first slice is not a metadata editor. It is **finding out what the server actually does**,
because everything else is a guess until then — and a guess that writes to somebody's library is a
different class of mistake from a guess that reads one.

## What is different about this phase

Every previous phase could be wrong and cost the user a re-sync. This one can be wrong and cost them their
metadata, their covers, or an item. Three consequences shape the slice order:

1. **Nothing writes without a capture.** Slice 1 adds the capture calls; the rest wait for their shapes.
2. **Permissions are enforced twice** (PRODUCT_SPEC principle 4), and the second enforcement — in the
   domain layer — needs permissions the app does not currently persist. That is slice 1's other half.
3. **The destructive requirements come last**, not first, even though MGR-005 and MGR-006 are the ones with
   the most specific criteria. An app that can delete before it can edit is an app whose first management
   feature is the irreversible one.

## What the app already has

Worth stating, because it is more than it looks:

- `me.json` carries `permissions.update`, `permissions.delete`, `permissions.upload` and `type`
  (`root`/`admin`/`user`/`guest`). All four are **parsed off the wire and dropped**, exactly as
  `permissions.download` was before Phase 3 slice 1.
- `ServerCapability` already has `MetadataUpdate`, `CoverUpload`, `MatchProvider`, `ScanItem`,
  `ScanLibrary`, `UserManagement`, `RemoveFromDatabase` and `SourceFileDelete`. None is ever set —
  `AbsCapabilityResolver` records only what a probe confirms, and no probe asks about these.
- `ProfileRole` has `Listener`/`Editor`/`Manager`/`Admin`, and every profile is currently a `Listener`.
- The book screen's three-dot menu exists and is where the management actions belong.

## What the captures came back with

Run on 2026-08-15 against 2.36.0. `docs/api-compatibility.md` has the full record; these are the findings
that change the plan.

| Finding | Effect |
| --- | --- |
| **`GET /api/users` returns every user's live token** | `UserDto` must never model the field. Slice 8's shape is decided before it starts. |
| **A created user is `isActive: false`** | USER-002 cannot report "created" and stop. |
| **Cover removal, library scan and item deletion answer `text/plain "OK"`** | Three endpoints where assuming JSON would report failure for a success. |
| **`PATCH /api/items/{id}/media` returns the whole item** | MGR-001's "refresh from server" *is* the response. No follow-up `GET`. |
| **Item scan is synchronous; library scan is not** | MGR-004 needs two different treatments, and neither response says which. |
| **A quick match with no provider defaults to Google, and can miss** | A miss is `200` with a `warning`, not an error. |

### What they did not settle, and what did

Four questions were left open. None of them could be answered by a capture, so they were answered by
reading the Audiobookshelf project's own source at the same version — ADR-0012's amendment records the
licensing posture, and `docs/api-compatibility.md` has the findings in full. The short version:

| Question | Answer |
| --- | --- |
| **A successful match** | Quick match is not a preview at all — it applies the change and then reports it. MGR-003's preview has to come from `GET /api/search/books`, which writes nothing, with the chosen fields applied via the metadata `PATCH`. **Slice 5 is unblocked and its design changed.** |
| **Cover upload** | Multipart, part named `cover`, validated on the **filename extension** — `png`, `jpg`, `jpeg`, `webp`. Android's Photo Picker does not supply a usable one, so the app must synthesise it. A URL body is the alternative. |
| **What a `403` looks like** | `text/plain`, body `Forbidden`, because these handlers use Express's `sendStatus` — which is also why three of them answered `text/plain "OK"`. One cause, not three quirks. The capture script now creates an active non-admin account and records three real refusals. |
| **Source-file deletion** | Two endpoints exist — `DELETE /api/items/{id}?hard=1` and `DELETE /api/items/{id}/file/{ino}` — and **neither can prove it happened**: a failed filesystem removal is logged on the server and discarded, and the request succeeds anyway. **Slice 7 ships no feature, by decision (ADR-0021) rather than by absence.** |

The reference also settled the permission model, which had been assumed: the item routes gate on the HTTP
method, cover *upload* needs the update **and** upload grants, and both scan endpoints gate on the account
*type* rather than on any grant.

## The slices

### Slice 1 — the captures, and the permissions become real *(done)*

Two halves, both of which unblock everything after them.

**The captures.** `scripts/capture-contracts.sh` gains the management endpoints, ordered so that read-only
probes run before writes and the item deletion runs last. What each capture is for is written beside it.
The one thing it deliberately does not capture is a cover *upload*, which needs a multipart body and an
image this script has no business inventing.

**The permissions.** `update`, `delete`, `upload` and the account `type` are persisted on the profile row
and exposed on `Profile`, and `ProfileRole` is derived from the type rather than defaulted. Ends with: the
app can honestly say what this account may do, which is what every later slice gates on.

### Slice 2 — the capability probe *(done)*

The slice's question was "which of the management capabilities can a probe honestly answer", and the
answer turned out to be **one**.

`GET /api/search/providers` is read-only, needs no privilege beyond a session, has no side effects, and
answers something that genuinely varies by deployment — an administrator can configure custom providers.
It is now `AbsCapabilityResolver`'s second real probe, alongside the websocket handshake.

Everything else in EPIC MGR cannot be probed, and the reason is structural rather than incidental: asking
whether metadata may be edited means editing it, and asking whether an item may be deleted means deleting
it. What gates those is **the account's grant, not the server's capability** — a different question with a
different owner, since two profiles on one server share a capability set and must not share a menu.

So the gate is `ManagementAction` and `ManagementPermissions` in `:core:model`: a pure function of the
profile's grants, the confirmed capabilities and connectivity, returning *why* an action is unavailable
rather than a boolean. MGR-005 blocks offline invocation while MGR-006 says the action must not exist, and
a caller cannot tell those apart from a boolean.

This slice also decided MGR-006, which is what it was for. The capability is real and the acknowledgement
is not, so `SourceFileDelete` is never confirmed and **slice 7 is already complete with no feature**
(ADR-0021).

### Slice 3 — metadata editing (MGR-001) *(done)*

The largest slice, and most of what makes it hard is on this side of the wire.

**Dirty tracking is the load-bearing part**, and not for tidiness: `authors` and `series` are
*replacements* on this endpoint, so the server removes every entry an array does not contain. A payload
built from anything wider than the user's actual edits would delete data. `MetadataPayload` sends only the
changed fields, and the two tests named for that are the most important in the slice.

**The conflict check is a comparison, not a header.** Audiobookshelf's metadata route carries no `ETag` and
honours no `If-Match`, so there is no way to ask the server to refuse a stale write. The editor reloads the
item before saving and compares three versions — what it opened with, what the user typed, and what the
server holds now — and reports a conflict only where the user *and* somebody else changed the same field.

**Drafts are a Room table** (migration 19), not `ViewModel` state, because the process dies behind a user
who leaves to look up an ISBN. They are deliberately not an outbox: MGR-001 forbids queueing privileged
edits for blind offline execution, so nothing drains the table and only the user submits it.

The refresh after a save is a second, expanded request. The `PATCH` response is complete for the metadata
but carries no tracks, and writing a snapshot without tracks would replace a playable book with an
unplayable one.

### Slice 4 — covers (MGR-002) *(done)*

Photo Picker, validation, preview, commit, and removal behind a confirmation.

**All four validations are this app's own**, and that is the point of the slice. The server checks one
thing — the filename's extension — so an eight-megabyte photograph, a zero-byte file and a `.png` that is
really a text document are all accepted and all become somebody's cover. `CoverCandidate` is the only place
MIME type, decode success, dimensions and a size limit are checked.

**The filename is contract, not convention.** The server reads the extension, and Android's Photo Picker
hands back a URI whose display name is frequently absent or extensionless, so the app synthesises the name
from the MIME type it validated. A valid PNG sent as `image` is refused; the same bytes as `cover.png` are
not.

**The image is decoded twice**: once with `inJustDecodeBounds`, which reads the header and allocates no
pixels, and once for real only if the bounds pass. On a mid-range phone that is the difference between a
message and an `OutOfMemoryError` when somebody picks a 48-megapixel photograph by mistake.

Cache invalidation needs no cache code: the upload moves the item's `updatedAt`, the refresh picks it up,
and `?ts=` makes the new image a different key from the old one.

### Slice 5 — match and scan (MGR-003, MGR-004) *(done)*

**Match is search-then-apply, not quick match.** `GET /api/search/books` returns candidates and writes
nothing, so the preview MGR-003 requires is buildable; the chosen fields are then applied through the same
metadata `PATCH` slice 3 uses, which is what makes "existing non-empty fields are not overwritten without
an explicit choice" achievable. Quick match cannot do it: it applies the first result and then reports
what it did. Candidate fields differ by provider and every one of them is optional. Two of them are
hazards rather than data — `cover` is a URL on a third party's host, and `description` is provider HTML.

**Scan** needs the repeated-tap guard and the four visible states, and the two endpoints need different
treatment: an item scan is over before it answers and reports `NOTHING`/`ADDED`/`UPDATED`/`REMOVED`/
`UPTODATE`, while a library scan acknowledges before it starts and never reports a result at all. Both
gate on the account *type* rather than on a grant. A `500` from an item scan can mean "file-based library
items cannot be rescanned" and must be shown as a failed scan rather than as a crash.

### Slice 6 — removal from the database (MGR-005) *(done)*

The label is the requirement's own words, and the confirmation's three clauses each exist because the label
alone would be read as something worse: it leaves the database, the media files stay on the server, and a
later scan may add the book back.

The middle clause is **true because of what the request does not carry**. `?hard=1` is the flag that deletes
the files, and ADR-0021 records why this app never sends it — so the promise is structural rather than a
matter of wording.

The order is fixed by the requirement: server first, Room only after confirmation. The local download is a
separate, unchecked checkbox, and a failed local delete does not fail the removal — the server's copy is
already gone, and inviting a second attempt against an item that no longer exists would earn a `404`.

### Slice 7 — source-file deletion (MGR-006) *(done — no feature, by decision)*

The slice ended the way it was allowed to end. The capability is real: `?hard=1` on the item delete
removes the files, and there is a per-file endpoint too. The acknowledgement is not — a failed filesystem
removal is logged on the server and discarded, and the request succeeds either way, so no response this
server sends can satisfy *"the server response must explicitly confirm deletion"*.

A capture cannot help, which is why this is a decision rather than a deferral: every response that route
can produce is a success, including the ones where the file survived. ADR-0021 records it, and
`ManagementActionTest` guards it — the test fails the moment a probe starts confirming the capability,
which is precisely when the decision needs revisiting.

### Slice 8 — user management (EPIC USER) *(done)*

Admin-only, never cached, and the token problem solved by absence rather than by filtering.

**There is no `token` property anywhere.** `GET /api/users` returns every user's live access token in
plain text to any admin who asks. USER-001 says tokens are never displayed; the stronger rule this build
enforces is that the value is never *parsed* — `UserSummaryDto` has no field for it, so there is nothing to
store, log, put in a crash report or render by accident. `pash`, the password hash, is absent for the same
reason, and `CapturedShapesTest` asserts the fixture does contain the token so the omission stays
deliberate rather than becoming an oversight somebody "fixes".

**Nothing is cached.** `ServerUserRepository` has no `observe`, no Room table and nowhere to put one, which
is USER-001's "not cached for offline viewing by default" expressed as an absence. The list is who exists on
somebody's private server and what each may do, read on a device other household members also use.

**The password is cleared on every outcome**, not only on success — a failed create leaves the username so
the administrator can retry and does not leave the password in memory behind a screen they may put down.

**A new account is created active**, because the server defaults it to inactive and an account nobody can
sign in to is not what "created" means.

**Deleting a user is absent, not disabled.** USER-003 puts it in later scope "unless thoroughly
contract-tested", and disabling is what it prefers where the server supports it. This one does.

### Not in these slices

- **Embed metadata (MGR-007).** It asks the server to rewrite the user's source audio files. It needs its
  own decision about whether this app should offer that at all, and the answer is not obvious.
- **Batch matching.** MGR-003 says later scope.
- **Bulk source-file deletion.** MGR-006 says not in version 1.

## What could still stop this phase

The captures could come back saying the endpoints are not what the requirements assume. That is the point
of running them first, and it would be a finding rather than a failure — PRODUCT_SPEC's own rule is that
server behaviour is not guessed, and a phase that discovers it cannot do something is more useful than one
that ships something that quietly does not work.
