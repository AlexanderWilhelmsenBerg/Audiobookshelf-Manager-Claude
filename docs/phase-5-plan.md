# Phase 5 — Management tools: the plan, and what has to happen first

**Status: started. Slice 1 is the captures and the permissions.**

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

## The slices

### Slice 1 — the captures, and the permissions become real *(this slice)*

Two halves, both of which unblock everything after them.

**The captures.** `scripts/capture-contracts.sh` gains the management endpoints, ordered so that read-only
probes run before writes and the item deletion runs last. What each capture is for is written beside it.
The one thing it deliberately does not capture is a cover *upload*, which needs a multipart body and an
image this script has no business inventing.

**The permissions.** `update`, `delete`, `upload` and the account `type` are persisted on the profile row
and exposed on `Profile`, and `ProfileRole` is derived from the type rather than defaulted. Ends with: the
app can honestly say what this account may do, which is what every later slice gates on.

### Slice 2 — the capability probe

`AbsCapabilityResolver` learns to confirm the management capabilities. Which of them a probe can honestly
answer depends on what slice 1's captures show — an endpoint that answers `404` for an unknown item and
`403` for an unpermitted one can be probed; one that only fails after it has done something cannot.

This is the slice that decides whether MGR-006 can exist at all: its first criterion is *"The action does
not exist unless the connected server reports a dedicated, tested source-file-delete capability."*

### Slice 3 — metadata editing (MGR-001)

The largest slice, and the one with the most criteria that are about *the editor* rather than the network:
dirty-field tracking, inline validation, a local draft that survives a network failure, and a conflict view
when the item changed underneath. Most of it is testable without a server.

### Slice 4 — covers (MGR-002)

Photo Picker, MIME and dimension validation, preview before commit, cache invalidation. Depends on a
capture of the upload endpoint that slice 1 does not attempt.

### Slice 5 — match and scan (MGR-003, MGR-004)

Both are "ask the server to do something and watch it happen", and both are capability-gated. Scan needs
the repeated-tap guard and the four visible states; match needs the untrusted-display-data handling.

### Slice 6 — removal from the database (MGR-005)

The label is fixed by the requirement: **`Remove from Audiobookshelf database`**. The confirmation states
that media files remain on the server and a later scan may re-add the item. Local download removal is a
separate, unchecked checkbox.

### Slice 7 — source-file deletion (MGR-006), if and only if the capability is real

Capability-gated, typed confirmation, and an explicit server acknowledgement. If the server cannot prove
the deletion happened, the UI says so rather than claiming success. **This slice may correctly end with no
feature at all**, and that is a successful outcome rather than a failed one.

### Slice 8 — user management (EPIC USER)

Admin-only, not cached offline, and never displaying a token or a password hash.

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
