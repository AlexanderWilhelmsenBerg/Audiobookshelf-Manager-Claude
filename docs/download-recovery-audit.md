# Download and offline recovery audit

**Classification:** Active audit / planning evidence.  
**Issue:** #104 — BW-DL-01.  
**Reviewed against:** `main`, 2026-09-07.  
**Runtime changes:** none in this document/branch.

This audit asks a narrower question than "how should downloads work?": **when the existing download system is
not in the happy path, what can BookWave truthfully tell the listener and what is the safest action?**

The current architecture is substantially better than the current recovery UI. The file protocol preserves
partials, verifies before atomic commit, shares one physical copy between profile claims, checks free space
before enqueue/resume, and keeps WorkManager execution separate from the manifest. Most of the missing work
is therefore state projection and action semantics, not a downloader rewrite.

## Current ownership model

Five facts must remain separate:

1. **Physical copy** — one book copy on the device, keyed by server + item.
2. **Profile claims/authorization** — `requestedBy`; several profiles may reference the same copy.
3. **Durable manifest/file state** — queued/paused/failed/complete plus per-file bytes/state/URI/validators.
4. **WorkManager execution state** — enqueued, constraint-blocked, running, backoff/retrying, cancelled,
   succeeded/failed.
5. **Library/local availability projection** — what a profile may see/play.

A recovery UI can combine those facts for presentation. It must not merge them into one stored enum simply
because a row wants one label.

## What is already correct and should be preserved

### Partial bytes survive ordinary failure

Files are written to `.part`, resumed with Range/If-Range when safe, and only renamed to their final media
name after verification. A failed request records the bytes already present instead of deleting them. A
retry is therefore genuinely a resume when the server permits it.

**Rule:** ordinary network/server failure must never offer or perform "start over" as the primary recovery.
Preserve the part and retry/resume first.

### Pause is intentionally different from failure

`DownloadState.Paused` is durable because WorkManager cancellation alone cannot tell the next process whether
the user intentionally stopped the transfer. Automatic/smart-download paths deliberately leave Paused
alone.

**Rule:** Paused is user intent, not an error. It gets Resume, never automatic retry and never error styling.

### Free space is checked before a new/resumed user request

`DownloadBookUseCase` re-checks the active profile's download grant and allocatable storage before enqueueing.
That is the correct boundary for Resume too: a week-old paused download may no longer be authorized and the
volume may no longer have space.

### Verification is non-destructive

Startup manifest verification and the explicit full verification action mark a broken committed file/book
failed but do not delete it. The existing aggregate message is careful to say nothing was deleted.

**Rule:** verification may downgrade trust; deletion/re-download requires a separate explicit decision.

### Profile visibility and physical sharing are deliberately different

The device-level Downloads screen lists physical copies even when the active profile cannot see a title. A
hidden row retains size/actions while masking book metadata. Keep this privacy boundary.

## Findings

### F1 — The failure reason is stored and then thrown away before UI

`DownloadedBookEntity.failureSummary` already exists and `DefaultDownloadRepository.markFailed` writes the
`AppError.summary`. The entity comment explicitly says it exists for the storage screen.

`DownloadMappers.toDomain`, however, does not copy it into `OfflineBook`; `OfflineBook` has no such field.
`DownloadsViewModel` therefore can only expose `isFailed: Boolean`.

The user gets a red incomplete row with no explanation even though the exact safe summary is already durable.
This affects network failures, server/auth failures, verification failures and storage failures alike.

**Recommendation:** expose a typed/safe failure presentation through the domain/download repository boundary.
The minimal slice can reuse the already-persisted summary and requires **no Room migration**. Do not expose a
Throwable, URL or raw HTTP response to UI.

### F2 — Failed rows show a Pause action instead of Retry

`DownloadsScreen` publishes the pause/resume icon for every row where `!isComplete`. The choice is only based
on `isPaused`:

- Paused -> Play/Resume icon;
- everything else incomplete, including Failed -> Pause icon.

`DownloadsViewModel.onPauseToggled` then calls `PauseDownloadUseCase` for that Failed row. The use case does
not require a running job; it marks the manifest Paused and cancels any work.

So the first action offered on a failed download can turn **Failed -> Paused** instead of retrying it. That
also clears the persisted failure summary.

**Recommendation:** derive actions from a presentation state, not `!isComplete`. A terminal/recoverable
failure gets Retry; Paused gets Resume; actively running gets Pause; a constraint-blocked/enqueued item gets
an appropriate Cancel/Pause decision only if product rules say that is useful.

### F3 — WorkManager can be retrying while the manifest says Failed

`BookDownloader` marks the manifest Failed whenever a file attempt returns an error. `BookDownloadWorker`
then returns `Result.retry()` for retryable `AppError`s, using exponential backoff.

Those are intentionally different state owners, but the UI currently reads only the manifest. During
WorkManager backoff it can therefore say **Failed** while the system is already going to retry.

Likewise, WorkManager constraints can hold a request waiting for unmetered/Wi-Fi while the manifest does not
explain "waiting for Wi-Fi".

**Recommendation:** add a read-only execution-state projection from WorkManager and combine it with the
manifest in the app/UI boundary. Do **not** persist WorkManager's ENQUEUED/BLOCKED/RUNNING/backoff states into
the manifest.

Suggested presentation precedence:

1. Complete and verified enough to play;
2. Paused by user;
3. Running;
4. Waiting for network constraint;
5. Retrying/backoff, with last safe failure reason if useful;
6. Terminal/recoverable Failed;
7. Queued/starting;
8. Unknown/inconsistent -> conservative recovery state.

### F4 — `DownloadState.Running` is not the reliable execution source

The model defines `Running`, but production transfer state is fundamentally owned by WorkManager and
per-file progress. Searches of current production code do not show a dependable book-level transition that
makes `OfflineBook.state == Running` the authoritative answer while a worker is active.

That is another reason not to "fix" F3 by adding more writes to the manifest. Treat Running as historical
model vocabulary unless/until a concrete durable meaning is specified; use the worker projection for live
execution.

### F5 — Removable-volume absence is classified as file failure

Current storage selection uses app-specific directories from `getExternalFilesDirs`, not SAF arbitrary
folders. A missing selected volume falls back to internal storage for **new** downloads.

Existing file URIs remain absolute. Startup `DownloadVerifier` checks every complete `file://` URI with
`File.isFile`/length. If an SD card is physically absent, those files look missing and are marked Failed even
though the bytes may be perfectly intact and return when the card is reinserted.

This conflates:

- corrupt/missing file;
- temporarily unavailable storage.

It also means a later card reinsertion does not automatically restore the manifest's Complete file states.

**Recommendation:** introduce a derived/recorded storage-availability fact and treat an absent removable
volume as **Unavailable**, not Failed/Corrupt. Reinsertion should re-verify and restore availability without
redownloading intact bytes.

This slice needs design work on durable volume identity. A book currently stores absolute file URIs while the
selected volume UUID is a global setting for future writes; robustly recognizing an absent historical volume
may justify adding/storing the volume UUID per physical copy. If that becomes durable schema, it requires a
Room migration with a conservative backfill/Unknown value.

### F6 — Missing selected card silently changes the destination for new downloads

`StorageVolumes.rootFor(chosenUuid)` returns null when the card is gone and `roots()` falls back to internal.
The stored setting still names the missing UUID. The picker only lists currently available volumes, so the
screen can have a selected UUID with no matching radio row while new downloads are actually written to
internal storage.

That behavior is safe for bytes but not transparent to the user.

**Recommendation:** while the selected volume is unavailable, show a clear state such as:

> Selected storage is unavailable. New downloads will use internal storage until it returns.

Do not silently rewrite the user's selected UUID to internal; preserving the preference lets the card regain
its role when reinserted.

### F7 — Device-wide Downloads screen actions are still profile-claim actions

The screen's stated purpose is device storage: it lists every physical copy, including a title-hidden row
owned by another profile. But `onRemove` resolves the **active profile** and calls `OfflineFiles.remove` for
that profile's claim only.

If the active profile does not own the hidden copy, no claim is removed; the physical bytes remain. The
screen can therefore present a delete action for a device copy it cannot actually delete.

The same mismatch exists for pin state: `OfflineBook.isPinned` means **any** profile has pinned the shared
copy, while the screen writes the active profile's request. A globally-looking pin can therefore fail to
clear because another profile owns the pin.

This is not a reason to weaken profile authorization. It is a reason to define two different actions:

- **Book/profile surface:** Remove *my* offline claim; shared file remains if another profile needs it.
- **Device storage surface:** Remove physical copy **for all profiles on this device**, with an explicit
  warning that other profiles will need to download it again; similarly, a device-level pin should have a
  device-level meaning or expose which claim is protected without leaking titles.

The owner should approve the destructive device-wide semantics before implementation. Current wording/comments
already imply that device storage management is the intent, but the operation must be made explicit rather
than inferred.

### F8 — Verification says broken books "offer a retry", but this screen does not provide that recovery

After full verification, the ViewModel reports:

> broken book(s) ... now offer a retry

The Downloads row currently exposes the pause control described in F2, not Retry. A retry may exist from a
book detail surface, but the message is presented on this management screen and implies the recovery is
available here.

**Recommendation:** once F2's action model lands, keep the message. Until then it is inaccurate copy.

### F9 — SAF language in the model is historical/future, not current capability

`StorageRoot.Tree` and some KDoc describe a user-chosen SAF tree, while current production storage uses
app-specific directories on internal/removable volumes and `docs/gaps.md`/ADR-0020 defer arbitrary SAF
folders.

This is architecture/documentation drift, not a reason to implement SAF during recovery work.

**Recommendation:** BW-DOC-01 should ensure current docs say:

- internal/app-specific volume selection is implemented;
- arbitrary SAF directory selection remains deferred/candidate;
- URI-shaped manifest fields preserve a future seam but do not prove SAF writing is implemented.

Do not make recovery slices depend on SAF.

### F10 — Partial discard exists but is not a normal recovery decision

`OfflineFiles.discardPartials` can explicitly remove `.part` bytes while keeping the manifest/claim. That is
useful when the user wants to abandon a stuck partial or reclaim space, but it should be a secondary/destructive
recovery action, not the normal Retry path.

Recommended wording when exposed later:

- Primary: **Retry / Resume** — preserves partial bytes.
- Secondary: **Discard partial download** — tells the user how much resumable data will be lost and asks for
  confirmation.

Do not label this "Repair"; verification and discarding solve different problems.

## Recovery-state matrix

| Situation | Current evidence | Recommended UI | Primary action | Secondary | Preserve partial? | Offline-safe action? |
| --- | --- | --- | --- | --- | --- | --- |
| Complete/intact | manifest/files complete | Downloaded | Play/Open | Remove / Pin | n/a | Yes |
| User paused | `Paused` | Paused | Resume | Discard partial / Remove | Yes | Remove/discard yes; Resume may need network |
| Actively transferring | WorkManager Running + manifest | Downloading N% | Pause | — | Yes | Pause yes |
| Waiting for Wi-Fi/unmetered | WorkManager constrained/enqueued + policy | Waiting for Wi-Fi | Keep waiting | optionally allow manual cellular if policy permits | Yes | Yes |
| Retry/backoff after network/server error | WorkManager retry scheduled + failure summary | Retrying · reason | Retry now only if useful / otherwise wait | Pause | Yes | Pause yes |
| Terminal/recoverable failure | manifest Failed + no retry work | Failed · reason | Retry | Discard partial / Remove | Yes by default | Remove/discard yes |
| Initial insufficient storage | `DownloadBookUseCase` Storage failure before enqueue | Not enough space | Manage storage / choose volume | — | existing partial on Resume remains | Yes |
| Selected removable volume absent | selected UUID missing | Storage unavailable; future downloads use internal | Reinsert card / choose location | use internal explicitly | Existing card bytes untouched | Yes |
| Complete file on absent removable volume | URI points to unavailable volume | Download unavailable — storage removed | Reinsert card | explicit re-download elsewhere | Yes, on card | Yes |
| File genuinely missing/wrong size | verifier failure | Download damaged/missing files | Retry missing files | Remove | Preserve valid files/partials | Yes for remove |
| Auth expired / permission revoked | safe failure summary + profile grant | Sign in again / download no longer allowed | Reauthenticate if appropriate | Remove local claim/copy subject to authorization rules | Yes until user removes | Yes for local remove |
| Server unreachable | failure + worker retry state | Server unavailable / retrying | Wait/Retry | Pause | Yes | Pause yes |
| Process died mid-transfer | WorkManager + `.part` | Resume automatically when work continues | none unless surfaced | Pause | Yes | Yes |
| Orphan bytes, no manifest | startup sweep only | no user row | automatic safe sweep | — | n/a; no owner exists | Yes |

## Privacy/security rules

- Failure presentation may use the existing sanitized `AppError.summary`; never persist/render raw response
  bodies, tokens or credential-bearing URLs.
- A title-hidden device-storage row stays title-hidden even when it has an error.
- WorkManager input profile remains the authorization owner of queued work; a later active-profile change
  cannot replace it silently.
- A physical file is not evidence that the active profile may browse/play the book.
- A future device-wide destructive "remove for all profiles" action must be explicit about scope and must not
  expose the other profiles' book metadata.

## Recommended implementation slices

These are deliberately smaller than a "downloads redesign".

### BW-DL-02 — Expose safe download failure reason and presentation state

**Problem:** durable reason exists but is dropped; UI cannot distinguish useful states.  
**Scope:** carry safe failure summary through `OfflineBook`/repository; introduce a pure presentation model
that combines durable state with a narrow execution-state input. No visual redesign beyond what is required
to prove the model.  
**Out:** removable-volume redesign, device-wide delete semantics.  
**Dependencies:** none.  
**Storage:** no migration for failure summary; already stored.  
**Tests:** mapper/repository + pure state precedence, failed/retrying/paused/complete/queued.  
**Effort/risk:** Small–Medium / Low.  
**Sequence:** first.

### BW-DL-03 — Correct Downloads row actions: Pause / Resume / Retry

**Problem:** Failed currently offers Pause and can be converted to Paused.  
**Scope:** actions derive from BW-DL-02 state; running=Pause, paused=Resume, terminal failure=Retry; verify
aggregate copy matches available action.  
**Dependencies:** BW-DL-02.  
**Storage:** none.  
**Tests:** ViewModel/UI semantics and use-case calls; ensure Failed cannot call Pause.  
**Device:** pause running transfer, force network failure, observe retry/backoff, resume after restart.  
**Effort/risk:** Small / Low–Medium.

### BW-DL-04 — Represent WorkManager constraint/backoff state without persisting it

**Problem:** waiting/retrying work appears merely incomplete/failed.  
**Scope:** narrow `DownloadExecutionReader`/equivalent adapter over unique WorkManager work; map state and
constraint/backoff facts into BW-DL-02 presentation.  
**Out:** duplicating WorkManager state in Room.  
**Dependencies:** BW-DL-02; can be implemented with BW-DL-03 if small but should retain a separate contract.  
**Tests:** adapter mapping where WorkManager test support can prove it; pure UI projection.  
**Device:** Wi-Fi-only request on cellular/no Wi-Fi, then network return.  
**Effort/risk:** Medium / Medium because WorkManager observation/lifecycle must stay bounded.

### BW-DL-05 — Removable storage unavailable is not corruption

**Problem:** missing card makes verifier mark intact-on-card files Failed; new destination silently falls back
internal while preference still names card.  
**Scope:** storage-availability model; skip corruption downgrade when the owning volume is absent; reverify on
return; expose selected-volume-unavailable/fallback state.  
**Out:** arbitrary SAF folders.  
**Dependencies:** none functionally, but doing after BW-DL-02 makes UI projection easier.  
**Storage/migration:** likely needs durable per-copy volume identity or an equivalent robust locator; design
and migration are part of the slice. Unknown/legacy must fail conservatively without deleting bytes.  
**Tests:** storage mapper/verifier with mounted/unmounted/returned volume; migration if schema changes.  
**Device:** real removable storage strongly required. Emulator/JVM is not sufficient acceptance.  
**Effort/risk:** Medium–Large / High.

### BW-DL-06 — Make device-wide copy management actions truly device-wide

**Problem:** screen lists all physical copies but delete/pin mutate only active profile's request.  
**Scope:** settle and implement explicit "remove from device for all profiles" semantics for the device
storage screen, while profile/book surfaces keep "remove my claim" semantics. Resolve pin ownership similarly.  
**Out:** weakening content visibility or profile authorization.  
**Dependencies:** owner product decision on destructive scope.  
**Storage:** probably no schema change; repository needs an explicit device-copy operation rather than
misusing profile `release`.  
**Tests:** active owner, non-owner hidden row, two-profile shared copy, pinned-by-other-profile, last/non-last
claim, privacy.  
**Device:** ordinary device UI confirmation/removal and offline playback after shared-copy decisions.  
**Effort/risk:** Medium / High because deletion scope is destructive.

### BW-DL-07 — Optional explicit partial-discard recovery

**Problem:** resumable partials can consume substantial space after a listener decides not to continue.  
**Scope:** expose existing `discardPartials` only as confirmed secondary action with reclaimed-size preview
where feasible; primary Retry always preserves bytes.  
**Dependencies:** BW-DL-03 so action hierarchy is already correct.  
**Storage:** none.  
**Tests:** only `.part` removed, committed files preserved, manifest remains recoverable, retry restarts clean.  
**Effort/risk:** Small–Medium / Medium because it destroys resumable data.

## Widget recommendation after the audit

Do **not** build a permanent "3 downloads" widget.

A download/status widget becomes worth reconsidering only after BW-DL-02/03/04 define actionable persistent
states. The potentially useful widget is exception-driven:

- "2 downloads need attention";
- "Waiting for Wi-Fi";
- "Storage card unavailable".

Even then, a notification or launcher badge may be the better surface. The widget is not promoted by this
audit.

## Recommended sequence

1. BW-DL-02 — safe reason + presentation state.
2. BW-DL-03 — correct actions.
3. BW-DL-04 — WorkManager waiting/retrying projection.
4. BW-DL-05 — removable-volume correctness, with real device test.
5. BW-DL-06 — destructive device-wide copy semantics after explicit owner decision.
6. BW-DL-07 — optional partial-discard affordance.

BW-DL-02/03 are high-value and small. BW-DL-05 is the highest correctness risk. BW-DL-06 is deliberately
held behind a product decision because deleting a shared physical copy is the kind of convenience feature
that should not acquire semantics by accident.

## Acceptance result for BW-DL-01

The audit can close when this document is accepted and the chosen follow-up slices are represented in the
canonical roadmap/issues. No application implementation belongs in BW-DL-01 itself.
