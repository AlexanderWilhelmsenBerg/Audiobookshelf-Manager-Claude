# ADR-0018: Phase 3 downloads, as the owner specified them

- **Status:** Accepted
- **Date:** 2026-08-14
- **Requirements:** PRODUCT_SPEC DL-001, DL-002, DL-003, DL-004, DL-005, DL-006. **Deviates from four of
  them** — see below.
- **Supersedes:** ADR-0017 in part (the smart-download trigger)
- **Decided by:** the project owner, answering the eight questions in `docs/phase-3-plan.md`

## Context

`docs/phase-3-plan.md` closed with eight decisions that each blocked a slice of Phase 3. All eight are now
answered. This ADR records the answers, and — more importantly — records the **four places where the answers
depart from PRODUCT_SPEC**, so that the departures are decisions rather than drift.

## Decision

### 1. Smart download stays in Phase 3, and fires at the halfway mark

> *"Smart download can stay in phase 3, but should be handled by downloading the next book in the series,
> when the book crosses the halfway mark. So, 50% in book 6 will trigger download of book 7."*

**Trigger: the current book crossing 50%, downloading the next book in its series.** A toggle in Settings, off
by default, with its own network choice.

**This is the third trigger this feature has had, and the first that is settled.** PRODUCT_SPEC's DL-005
documents one; ADR-0017 gave a different one; this supersedes both. The conflict was real and was flagged
rather than silently resolved.

> **Deviation 1 — PRODUCT_SPEC assigns DL-005 to Phase 4.** It is in Phase 3 by the owner's decision, taken
> twice now (ADR-0017 and again here).

> **Deviation 2 — DL-004 says smart downloads are "unmetered only, no cellular option in version 1".** The
> owner wants cellular to be selectable for them. See decision 5.

Halfway is measured against the book's duration, which the app already holds, so the trigger needs no new
server data. "The next book in the series" uses `SeriesMembership` and `SeriesSequence`, which LIB-003 already
models — including its rule for non-numeric sequences, which is what stops "Book Two" being treated as the end
of a series.

### 2. Partial downloads, verified at the end

> *"You have permission to add `/api/items/{id}/file/{fileId}` with a Range header to the capture list. I do
> want partial download as long as the files are verified at the end."*

The capture harness now probes the file endpoint for range support and validators. **Verification is not
optional and is not conditional on the range answer**: whatever the transfer did, the completed file is
validated before it is committed, which is DL-002 criterion 1 and the owner's own condition.

Worth recording, because the permission was given on a false premise: the capture job runs against a
**throwaway Audiobookshelf container in CI**, not against the owner's server. No request has ever been pointed
at their installation by this project, and none is needed to answer this.

### 3. Whether the server sends a checksum — **answered by capture: yes, a validator, not a checksum**

The owner added contract captures hoping to answer it. They could not: the existing envelopes record status
and content type and **no response headers at all**, so no committed fixture could ever have shown an `ETag`.

The harness now records, for the audio file endpoint: `Accept-Ranges`, whether a `Range` request answers 206
with a `Content-Range`, and whether `ETag` and `Last-Modified` are present. Presence rather than value —
an ETag differs between captures and would be drift-check noise.

The capture ran on 2026-08-14 against Audiobookshelf 2.36.0 and is committed as
`core/network/src/test/resources/contracts/item-file.json`:

| Question | What the server answered |
| --- | --- |
| `Accept-Ranges` | `bytes` |
| `Range: bytes=0-1023` | `206`, with a `Content-Range`, and exactly 1024 bytes back |
| `ETag` | present |
| `Last-Modified` | present |
| `Content-Length` | present |
| Unauthenticated request | `401` |

Three things follow, and each is a slice's design decided rather than guessed.

**Resumable partial downloads are viable** (decision 2, slice 6). Range is real, so a transfer interrupted at
byte *n* resumes at byte *n*, and `Content-Length` on the full request gives the total to resume against.

**The validator is an `ETag`, which is not a checksum, and the difference is the honest wording of *repair*.**
An ETag is a token whose only guaranteed property is that it changes when the file changes; it is not
required to be a hash of the bytes, and Audiobookshelf does not document how it derives one. So the app can
say *"this file is no longer the file the server had"* with certainty, and cannot say *"the bytes on disk are
correct"* from the ETag alone. Repair therefore compares the stored ETag against the server's current one and
offers a re-download; that is a **staleness check**, and decision 8's button must be labelled as one.
Integrity of the bytes *as downloaded* is a separate guarantee, and it comes from hashing what was written
before committing it (DL-002 criterion 1), not from the server.

**A resumed transfer must be validator-guarded.** `If-Range` with the stored ETag turns "the file changed
under me" from silent corruption — the first half of the old file glued to the second half of the new one —
into a plain `200` that restarts the download. This is the one case where skipping a header produces a file
that passes every local check and is wrong.

### 4. App storage by default, with a selectable folder and SD card

> *"The files should live in the app folder, but it should also be possible to select a folder, and sd should
> be possible to select."*

Default: the app-private path DL-003 mandates. Additionally, a chooser for any folder the user picks,
including an SD card.

> **Deviation 3 — DL-003 mandates app-private storage** and its criteria 1–3 are written around a path the app
> owns: sanitised filenames, no path traversal, not exposed to other apps. A user-chosen folder is reached
> through Android's Storage Access Framework as a tree URI, where the app does not construct paths at all —
> which satisfies criteria 1 and 2 by construction, and **breaks criterion 3 deliberately**: a folder the user
> picked is a folder the user (and anything else with access) can read.

Two consequences the owner should know, recorded here rather than discovered later:

- **Files in a chosen folder survive uninstall**; files in the app folder do not. That is usually the point of
  choosing one.
- **An SD card can be removed.** A book whose files are on absent media must degrade to "not downloaded" and
  offer a re-download, not fail as corrupt. That is the same handling PLAY-003's "unreadable local file"
  criterion already requires, which is why it is cheap to honour.

### 5. Network policy: Wi-Fi is always allowed, cellular is a per-category toggle

> *"Default is streaming has cellular and wifi. Download have wifi only. Smart download have wifi only. But
> you should be able to turn cellular on for downloads and smart download. But you can't turn off wifi."*

| Category | Default | Cellular can be enabled |
| --- | --- | --- |
| Streaming | Wi-Fi **and** cellular | already on by default |
| Manual downloads | Wi-Fi only | yes |
| Smart downloads | Wi-Fi only | yes |

**Wi-Fi cannot be turned off for any category.** That makes each setting a single boolean — "also use
cellular" — rather than DL-004's three-way choice, and it removes a state ("cellular only") that nobody wants
and that would strand a user on Wi-Fi.

> **Deviation 4 — DL-004 specifies three options per category including "ask on cellular".** The owner's model
> is two states. "Ask" survives only where DL-004 requires it for *streaming*, because being prompted before a
> download starts is useful and being prompted mid-book is not.

### 6. One copy per device, progress per profile

> *"If two different users share a book in the library, I want progress to stay per user and not have to
> download per user."*

One physical copy per server item, shared by every profile on the device; progress stays per profile, which it
already is. This is DL-003 criteria 4–5 — entitlement records and reference counting so removing one profile
cannot delete another's book.

> *"A simple solution can show all downloaded books for all users in the setting."*

**This one needs care, and is implemented in a modified form.** PRODUCT_SPEC 5.2 keeps one profile's content
off another's screen, and a storage screen listing titles from a library the current profile cannot access
would cross exactly that boundary — the one the whole visibility subsystem exists to enforce.

So the storage screen lists **everything downloaded, with sizes, and every one is deletable** — which is the
actual need, reclaiming space — but an item the current profile cannot see is listed **without its title**:
*"1 book in a library this profile cannot see — 412 MB"*. Nothing is hidden and no title leaks. If the owner
wants full titles regardless, that is a deliberate relaxation of 5.2 and should be its own decision.

### 7. Cleanup: opt-in, with a delay, and a smart-download companion

> *"This should be toggle in settings. To delete books after they are finished... delete after x days after
> finished... If smart download is on, you can also have the option to delete the previous book when the new
> is downloaded."*

Three separate switches, all off by default:

1. **Delete finished books**, after a configurable number of days.
2. **Delete the previous book in a series** when smart download fetches the next one. Available only when
   smart download is on, because it is meaningless otherwise.
3. Manual delete is always available, from the book screen and from the storage screen.

DL-006's protections are **not** optional and apply to every automatic path: never the playing book, never one
with unsynced progress, never a pinned one. The database already supports the second — `hasUnsyncedChanges`
is on every progress row.

### 8. Manage local files: per-file delete, and a repair that says what it checked

> *"Manage local files will show a delete per file, and also show a repair button. A repair button will check
> the sha of each book against the server version. Then prompt to delete and redownload."*

Per-file listing with sizes and a per-file delete, plus **Repair**.

Repair's honesty depends on decision 3. If the server sends a validator, repair compares it and reports a
genuine mismatch. **If it does not, repair cannot compute "the server's sha" without downloading the whole
file to hash it — which is the re-download it was trying to avoid.** In that case repair verifies what is
verifiable locally — presence, length against the manifest, readability as a media container — and offers
re-download for anything that fails, and the button's wording says which of the two it did. A button claiming
to have checked a checksum it never obtained would be worse than no button.

## Consequences

- Four recorded deviations from PRODUCT_SPEC, all owner decisions: DL-005's phase, smart download over
  cellular, storage outside the app's private directory, and the two-state network policy.
- The storage-access chooser is a materially larger piece of work than the mandated path alone: SAF tree URIs,
  persisted permissions across reboots, and playback from a `content://` URI rather than a file path.
- Decision 3 is the only one still evidence-bound, and the next capture answers it. Nothing else waits on it —
  slices 1, 2, 4, 5 and 7 of the plan are unaffected either way.
