# Phase 3 — Downloads and offline playback: plan, and what needs your decision

**Status: not started, and now fully specified.** No code exists yet. The eight decisions this document
originally asked for were **answered on 2026-08-14** and are recorded in ADR-0018; the summary is below, and
the original questions are kept underneath it so the answers can be read against what was asked.

## What Phase 3 is

`PRODUCT_SPEC.md` §Phase 3 names it **"Downloads and offline playback"** with seven deliverables — download
queue, foreground progress, resume, verification and atomic commit, local playback, offline outbox/session
sync, storage management — and three exit criteria:

1. A multi-file book downloads and plays offline.
2. Network loss and restart resume the download.
3. Forced process death loses no more than the accepted progress (§17.3: ten seconds).

## The requirements it consists of

| ID | Subject | Criteria | Repo today |
| --- | --- | --- | --- |
| **DL-001** | Manual download | 12 | ❌ nothing. The server's `download` permission is parsed off the wire and **never persisted**, so criterion 1 — "the button is visible only when the server grants it" — has no plumbing at all |
| **DL-002** | Download integrity | 6 | ❌ nothing. No manifest, no verifier |
| **DL-003** | Storage layout | 7 | ❌ nothing. The mandated path is `files/offline/<server-id>/<item-id>/<file-id>.<ext>` |
| **DL-004** | Network policy | 5 | ❌ nothing. Three settings blocks, none of which exist |
| **DL-006** | Automatic cleanup | 7 | ❌ nothing. Its "never delete the playing/unsynced/pinned book" half is Phase 3; the retention half is Phase 4 |
| **PLAY-005** | Offline session sync | 6 | ✅ **largely built.** The outbox, UUIDv4 session ids, idempotent retry and clock-skew detection landed in Phase 2 |
| **PLAY-003** | Two deferred criteria | 2 | ❌ "a missing local part prevents a false downloaded state" and "an unreadable local file stops playback safely and offers repair" — both deferred here by the Phase 2 audit because no local files exist yet |
| **SYNC-001** | `RangeDownload`, `ChecksumOrETag` capability gates | — | ❌ the capability enum exists; neither is detected |
| **SET-001/002** | Downloads settings, "verify downloads" action | — | ❌ |

§11.4 fixes the source-selection algorithm and §12 fixes the coordinator design: **twelve named job states**,
concurrency two books × two parts, `.part` temporary files, verify-then-atomic-rename, and exactly one
notification. Those are not suggestions — they are the design the acceptance criteria are written against.

## What Phase 3 inherits, and can rely on

Worth stating because it changes the size of the work: the hard parts of *progress* are done. PLAY-004's
journal, PLAY-005's outbox, the single-`MediaItem` book timeline (ADR-0016), the authenticated OkHttp stack
with its token provider, WorkManager wiring, and `LocalAvailability` as a model the UI already renders. The
download work is genuinely additive rather than a rewrite.

## Progress

| Slice | State |
| --- | --- |
| 1 — the download permission becomes real | **merged** (#18) |
| 2 — the storage layout and the manifest | **merged** (#19) |
| 3 — one file downloads, verified, atomically | **merged** (#20), and it absorbed slice 6's resume |
| 4 — a whole book, and playing it offline | **open** (#21) — the button, the worker, offline playback |
| 5 — network policy | **open** (#21) — three cellular switches, Wi-Fi always allowed |
| 6 — resume | folded into slice 3; the `RangeDownload` capability gate is still outstanding |
| 7 — verification and cleanup | not started |
| 8 — smart download | not started |

Slice 4 joins them: the book screen's download button is live, a `BookDownloadWorker` carries the transfer
past the screen with a progress notification, and a downloaded book plays with no network at all. The UI
follows the ShelfPlayer fork's `DownloadButton` — one control that cycles download → cancel → remove, with a
progress ring in place of the icon while bytes arrive.

Slice 5 rode along, because slice 4 could not honestly ship without it: a download that spends mobile data
by default is worse than no download button. Wi-Fi is always allowed and is not a setting — the owner's rule
— so each category is one switch, *may this also use cellular*, defaulting on for streaming and off for both
kinds of download.

Still not reachable: the storage screen (*Manage local files*), cleanup and smart download — slices 7 and 8.

---

## The slices, in the order they should be done

Each ends with something that works and is tested. Ranked by value per unit of risk.

### Slice 1 — the download permission becomes real *(buildable now, small)*

`user.permissions.download` is in `me.json` and is dropped on the floor. Persist it on the profile row, expose
it, and gate the book screen's download button on it. Ends with: the button is enabled for an account that may
download and honestly disabled for one that may not, which is DL-001 criterion 1 and nothing else.

Touches `ProfileEntity` (migration 16), `AuthMapper`, `Profile`, `BookScreen`. No capture needed.

### Slice 2 — the storage layout and the manifest *(buildable now, medium)*

DL-003's path scheme, filename sanitisation, path-traversal impossibility, and DL-002's manifest as a Room
entity recording server id, item id, file ids, paths, sizes, MIME types, durations and completion state. No
downloading yet — this is the shape the downloader commits into, and getting it wrong later means a migration
over user data.

Ends with: a manifest can be written and read back, and a hostile filename cannot escape the directory (tested).

### Slice 3 — one file downloads, verified, atomically *(buildable now, large)*

A `DownloadWorker` that fetches one audio file to `<name>.part`, verifies status/content-length/non-zero/
readable container, then renames. §12's job states, `.part` naming, one notification.

**This is where the exit criteria live.** Resume needs HTTP range requests — see decision 2 — but a first
version can work without them by restarting a failed part, which is correct if slower.

### Slice 4 — a whole book, and playing it offline *(buildable now, large)*

All tracks plus cover plus manifest, committed together so a crash cannot produce a false `Downloaded`.
Then `BookMediaSourceFactory` prefers local URIs, and PLAY-003's two deferred criteria become testable: a
missing part marks the download incomplete; an unreadable file stops playback safely.

Ends with **exit criterion 1**.

### Slice 5 — network policy *(buildable now, medium)*

DL-004's three settings blocks, Android's metering state as the source of truth, pause on a disallowed
network switch, debounced prompts. Needs decision 5.

### Slice 6 — resume across network loss and restart *(buildable now, medium)*

Range requests, guarded by `If-Range` against the stored `ETag`, and the `RangeDownload` capability gate. The
capture has answered decision 3: range works, and the validator exists to guard the resume with. Ends with
**exit criterion 2**.

### Slice 7 — verification and cleanup *(buildable now, medium)*

DL-002's incremental start-up verifier, the full-verification diagnostics action, and DL-006's protective half:
never remove the playing book, one with unsynced progress, or a pinned download. The database already supports
the second — `hasUnsyncedChanges` is on every progress row.

Plus decision 7's two opt-in switches: delete finished books after N days, and — when smart download is on —
delete the previous book in a series once the next one has arrived.

### Slice 8 — smart download *(buildable now, medium)*

Decision 1: at **50% of the current book**, download the next book in the same series. Its own toggle and its
own network choice, both in settings. Depends on slices 4 and 5 being done, which is why it is last.

### Not in these slices

Nothing. DL-005's Phase 3/Phase 4 conflict — PRODUCT_SPEC assigns smart download to Phase 4, ADR-0017 moved it
to Phase 3 with a different trigger — is resolved by decision 1: **Phase 3, at the halfway mark**. ADR-0018
records it as a deliberate deviation rather than an oversight.

---

## The eight decisions — **answered 2026-08-14**

All eight are settled. **ADR-0018 records them in full**, including the four places they depart from
PRODUCT_SPEC. In brief:

| # | Decision | Effect on the slices |
| --- | --- | --- |
| 1 | Smart download stays in Phase 3, triggered at **50% of the current book**, fetching the next in the series. A toggle, with its own network choice | New slice 8; supersedes ADR-0017's trigger |
| 2 | Range requests may be probed; **partial downloads wanted, verified at the end** | Slice 6 unblocked; verification is unconditional |
| 3 | Checksum/ETag — **answered by capture**: range works, `ETag` and `Last-Modified` are both sent | Slice 6 unblocked; repair is a staleness check, not a byte check |
| 4 | App folder by default, **plus a selectable folder and SD card** | Slice 2 grows: SAF tree URIs, not just a mandated path |
| 5 | **Wi-Fi always allowed; cellular a per-category toggle.** Streaming both by default, downloads and smart download Wi-Fi only | Slice 5 simplifies to one boolean per category |
| 6 | One copy per device, progress per profile; a storage screen listing everything | Slice 2; see ADR-0018 on the profile-boundary modification |
| 7 | Cleanup opt-in: delete finished books after N days, and optionally the previous book in a series | Slice 7 grows two switches |
| 8 | Per-file delete plus **Repair**, comparing against the server where it can | Depends on decision 3 |

### Decision 3, now answered

It could not be answered from the committed fixtures, because the capture envelopes recorded status and
content type and **no response headers at all**. No fixture could ever have shown an ETag. The harness was
extended to record them for `/api/items/{id}/file/{fileId}`, and the run on 2026-08-14 against Audiobookshelf
2.36.0 came back:

- `Accept-Ranges: bytes`, and `Range: bytes=0-1023` really answers `206` with a `Content-Range` and 1024 bytes.
- **`ETag` and `Last-Modified` are both present**, and so is `Content-Length`.
- An unauthenticated request is refused with `401`.

Committed as `contracts/item-file.json` and asserted in `CapturedShapesTest`.

The consequence worth stating plainly: **an ETag is a validator, not a checksum.** It changes when the file
changes, which is all HTTP guarantees, and Audiobookshelf does not document deriving it from the bytes. So
*Repair* compares the stored ETag against the server's current one and tells you whether your copy is
**stale**; it cannot tell you the bytes on disk are intact. Integrity of what was downloaded comes from
hashing it before it is committed to storage — which the app does either way — not from the server. The button
will say "check for changes on the server", because that is what it does.

### Two consequences of the answers, recorded rather than discovered later

- **A user-chosen folder survives uninstall; the app folder does not.** And an SD card can be removed, so a
  book on absent media has to read as "not downloaded" and offer a re-download rather than failing as corrupt.
- **The storage screen cannot show every title.** PRODUCT_SPEC 5.2 keeps one profile's content off another's
  screen. Everything downloaded is listed and deletable — which is the actual need — but an item the current
  profile cannot see is listed without its title: *"1 book in a library this profile cannot see — 412 MB"*.
  Full titles regardless would be a deliberate relaxation of 5.2 and should be its own decision.

---

## The original questions, for the record

## Challenges and gaps — the eight things that need your decision

Answers to these unblock everything above. Nothing here needs an engineer to answer.

### 1. Smart download: which phase, and which trigger?

PRODUCT_SPEC says automatic "download the next book" is Phase 4. ADR-0017 — your decision — moved it to
Phase 3 and described a *different* rule for when it fires than the requirement does. Two questions: do you
want it in this phase at all, and when should it fire — when you finish a book, when you start the last one,
or on a schedule?

**If you'd rather not decide now:** leaving it in Phase 4 costs nothing and slices 1–7 are unaffected.

### 2. Does your server support resuming a partial download?

Resume needs the server to answer HTTP **range requests** on the file endpoint. I can find out with one
capture run against your server — the CI harness already does this — but it is your server, so I would rather
ask before pointing a new kind of request at it. Without range support, a download interrupted at 90% starts
that file again; with it, it continues.

**What I need:** permission to add `/api/items/{id}/file/{fileId}` with a `Range` header to the capture list.

### 3. Does your server send a checksum or ETag for audio files?

Same capture answers it. If it does, DL-002 wants it stored and validated, which turns "the file is the right
size" into "the file is the right file". If it does not, size and readability are the honest maximum.

### 4. Where should downloads live?

DL-003 mandates **app-private** storage: invisible to file managers and other apps, and **deleted when the app
is uninstalled**. The alternative — external app-specific storage — is visible over USB and in a file manager,
and is *also* deleted on uninstall.

App-private is more private and is what the spec says. But if you expect to see the files, or to copy them off
the phone, say so now: changing it later means moving every downloaded file.

### 5. What should the download and streaming defaults be?

Three separate choices (DL-004):

- **Streaming**: Wi-Fi only / Wi-Fi and cellular / ask each time on cellular.
- **Manual downloads**: Wi-Fi only / Wi-Fi and cellular / ask.
- **Smart downloads**: unmetered only — the spec allows no cellular option in version 1.

My suggestion, unless you say otherwise: streaming on Wi-Fi and cellular, downloads Wi-Fi only. That matches
how most people are surprised least.

### 6. Do you need downloads shared between profiles on one device?

DL-003 allows one physical file to serve several local profiles on the same server, with per-profile
entitlements and reference counting so removing one profile does not delete another's book. It is real
complexity. If this phone only ever has your account on it, saying so removes a whole subsystem.

### 7. Should the app delete downloads on its own?

DL-006's automatic cleanup, with a retention window and a free-space reserve. The protections are not
optional and I will build them regardless — never delete the playing book, one with unsynced progress, or a
pinned one. The question is whether anything should be deleted *automatically* at all, or only when you ask.

### 8. What should "Manage local files" show?

It is a disabled row in the book menu labelled Phase 3. The Audiobookshelf app shows per-file state. Options:
a list of the book's files with sizes and a delete-per-file action; or one screen listing every downloaded
book with total sizes and a delete action. The second is more useful for reclaiming space; the first is more
useful when one file is broken.

---

## Two open defects that are not Phase 3, and should not be lost

1. **The excluded-track coordinate mismatch** (ADR-0016, PLAY-003). On a book whose playable audio files do
   not tile it from zero — an excluded file, or a gap — the player's timeline is shorter than the server's
   coordinate space, so the last stretch is unreachable and every stored position is offset. Found on a
   24:32:34 book whose last 7:52 could not be reached. The fix is to carry each track's `startOffset` in the
   media item's extras and translate between timeline and book coordinates. **This matters more than anything
   in Phase 3**, because it silently misplaces positions.
2. **The two-hour soak** has still never been run. It is a Phase 2 exit criterion and needs hardware.
