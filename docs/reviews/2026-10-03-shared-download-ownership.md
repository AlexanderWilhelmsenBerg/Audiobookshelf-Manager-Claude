# Shared download ownership — 2026-10-03

**Classification:** Reproduced regression, correction and functional verification log.
**Baseline:** GitHub main `b7266a3d`; log the final candidate APK SHA when executing.
**Owner:** Offline & Downloads. **Requirements:** DL-001/002/003/004, AUTH-002/004, §5.2, PD-003/004.
**Physical status:** NOT RUN. The owner will supply a phone later.

## Reproduction and correction

R-122 originally described a source-derived possibility. Four new `BookDownloaderTest` scenarios failed
on the baseline: removal of the queued owner before execution, removal between files, authentication
expiry while another profile still claims, and fetching with no remaining claim. The real downloader,
Room manifest/profile cascade and filesystem ran with a scripted credential-aware download gateway.
The original 11 downloader cases stayed green; the reproduction result was 4 failures in 15 cases.

`DownloadClaimAccess` uses the queued profile only while it remains an eligible claimant. For every audio
file and optional cover request it checks current claims, exact server identity, saved authentication and
download permission, plus catalogue visibility. An audio file must remain in the claimant's downloadable
file list. It rechecks profile/grant/claim after the potentially suspended asset lookup. It never substitutes
the UI's active profile, rewrites claims, replans files or changes WorkManager/network constraints.

Only authentication/authorization failures consider another claimant. Each call uses a bounded claim
snapshot, and an account rejected during one book run is skipped for subsequent files/artwork. Network,
storage and compatibility errors retain their ordinary failure/retry handling; cancellation is rethrown.
Already authorized HTTP bodies are not revoked mid-copy. A new claim arriving after a request's candidate
snapshot is considered at the next request/run; final-check/removal races remain R-120, not solved here.

## Automated cases and evidence

The policy test initially delegated directly to the queued owner: 13 of 15 cases failed. With access
checks and bounded fallback, all 16 final `DownloadClaimAccessTest` cases pass. These exact case names are
the automated checklist (the final artwork case was added after the initial reproduction):

| ID | Case / result |
| --- | --- |
| AUTO-DL-11-01 | Queued owner wins while still eligible — PASS. |
| AUTO-DL-11-02 | Released queued owner replaced by a remaining claimant — PASS. |
| AUTO-DL-11-03 | Reauthentication blocks original owner without a network attempt — PASS. |
| AUTO-DL-11-04 | Matching item claim from a different server grants no access — PASS. |
| AUTO-DL-11-05 | Current download grant required for an existing claim — PASS. |
| AUTO-DL-11-06 | Catalogue access checked for the replacement claimant — PASS. |
| AUTO-DL-11-07 | Stale/excluded file never fetched for another claimant — PASS. |
| AUTO-DL-11-08 | Claim released during asset lookup prevents transfer — PASS. |
| AUTO-DL-11-09 | Permission revoked during asset lookup prevents transfer — PASS. |
| AUTO-DL-11-10 | Profile removed during asset lookup prevents transfer — PASS. |
| AUTO-DL-11-11 | Unclaimed/forgotten copy never uses active profile — PASS. |
| AUTO-DL-11-12 | Authentication/permission failures try other claimants once; later files skip denied owner — PASS. |
| AUTO-DL-11-13 | All expired claimants fail without retry loop — PASS. |
| AUTO-DL-11-14 | Network/compatibility failures do not switch credentials — PASS. |
| AUTO-DL-11-15 | Cancellation rethrown without a second owner attempt — PASS. |
| AUTO-DL-11-16 | Artwork requires book access without replanning audio — PASS. |

All 17 `BookDownloaderTest` cases pass. Six new cases cross the actual downloader/file/Room/filesystem
boundary: the four reproduction cases plus validated partial resume and safe no-ETag restart after owner
removal. Both partial cases retain the already committed first file and never fetch it again. The validated
case sends offset 4 with the stored ETag and commits the complete 8-byte fixture; the no-ETag case sends
offset 0, truncates/replaces the part and commits 8 bytes rather than splicing old/new bodies. The existing
11 cases still cover weighted progress, failure retention, temporary storage loss, retry, last/shared claim
removal, card ownership, orphan sweep, partial discard and absent-card refusal.

The complete `:data:downloads:testDebugUnitTest` suite passed all 77 cases; formatter passed. The policy's
Kover class counters are 38/38 lines and 40/42 branches (100% lines, 95.2% branches), measured with
`:domain:koverXmlReportGate`; this is class evidence, not a new global coverage gate. Catalogue as well as
transport network/compatibility failures are checked before any credential fallback. The forced full
`ktlintFormat verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks --max-workers=4` gate passed in
7m 30s: all 1,119 tasks executed and 2,133 JVM/Robolectric cases passed with zero failures/errors (app 521,
playback 516, domain 315, downloads 77). Current-head CI and final-main APK evidence accompany the delivery.
Three initial full runs failed Detekt's loop-jump, nesting and test
parameter-shadowing rules. Flattening the loop and naming the fixture parameter passed both domain and
download production/test analysis variants plus the complete domain suite. Logs: ignored
`build/shared-download-evidence/{before,policy-before,after,focused,verify,verify-complete,static-and-policy,
verify-final,affected-static,verify-passed}.log`. Failed evidence is retained. These are simulated gateway/profile-policy results,
not a live Audiobookshelf authentication or physical WorkManager acceptance claim.

## Production reachability

`DownloadBookUseCase` records the authorizing claim and preferred owner. Manual/automatic callers both use
the existing `DownloadScheduler` binding. `WorkManagerDownloadScheduler` captures the same unique
(server,item) job and network constraints; `BookDownloadWorker.doWork` calls `BookDownloader.download`.
The audio loop and cover helper both invoke `DownloadClaimAccess.withOwner`; actual file transfer still
uses `FileDownloader` and the existing `DownloadApi`. Hilt resolves `ProfileRepository`, `DownloadRepository`
and `BookAssetSource` through their existing bindings. No endpoint, schema or dependency was added.

## Every required physical case — NOT RUN

Use the [register result template](../testing/roadmap-verification-register.md) for each row, including
APK commit/version/code/signer/checksum, CI run, API/device, fixture account/media configuration, timestamps,
expected/observed outcome, status and evidence. Use fixture credentials and sanitize captures. Repeat the
applicable rows on API 26/31/34/36; unavailable configurations remain NOT RUN.

| ID | Steps | Required outcome |
| --- | --- | --- |
| DEV-DL-11-01 | Queue under A while Wi-Fi constraints block execution; B claims; A stops then signs out/is removed; restore Wi-Fi. | B's transfer starts/completes without rescheduling under the UI's active profile or losing B's claim. |
| DEV-DL-11-02 | Start a multi-file transfer under A; B claims; stop/remove A while the first body is arriving. | Current authorized body may finish; later files/artwork use eligible B; no committed file is fetched/deleted again. |
| DEV-DL-11-03 | Expire/revoke A's server credentials after queueing, with B valid and entitled. Repeat with download permission revoked. | One failed account attempt per run, then B succeeds; no login loop, private metadata leak or claim rewrite. |
| DEV-DL-11-04 | Repeat with both claimants unauthenticated or B's catalogue/download access revoked. | No unauthorized fallback; truthful failure/reauthentication, committed/partial bytes retained; explicit sign-in and Retry recovers. |
| DEV-DL-11-05 | Pause an ETag-backed partial after one committed file; remove original owner; Resume as retaining B. | If-Range guarded offset reused, committed file unchanged, final copy verifies/playable offline; progress reconstructs. |
| DEV-DL-11-06 | Repeat using a no-ETag fixture, then a changed-validator fixture; pause the restarted body again before it reaches the previous part size. | Current part safely restarts, live and durable paused percent reflect actual bytes; committed files remain; no mixed/corrupt audio. R-123. |
| DEV-DL-11-07 | Kill/relaunch the UI process before execution and during owner handoff; restart the phone separately. | WorkManager/Room reconstruct the same shared job and eligible claims; no active-profile dependency or duplicate transfer. |
| DEV-DL-11-08 | Use a Wi-Fi-only transfer; change owners while offline/metered, then regain Wi-Fi. | Ownership handoff never relaxes constraints or silently moves to cellular; Waiting/Running states stay truthful. |
| DEV-DL-11-09 | Select unrelated C, including the same item ID on another server; remove A, retain entitled B. Also remove B's access during lookup. | Only a still-eligible same-server claimant authorizes new requests; current C/title visibility never grants access. |
| DEV-DL-11-10 | On slow storage, remove the final claim and add a claim while cancellation settles; record late writes and lookup/removal ordering. | Record R-120 separately; no assertion that this change provides a cross-WorkManager lock. Verify valid media/claims preserved and retry/sweep behavior. |
| DEV-DL-11-11 | Keep another book playing while two downloads run; stop A's shared claim, then test B's final Stop/Pause/Resume. | Playback uninterrupted; shared Pause refusal, profile-scoped Stop, final removal and independent notification/progress state stay correct. |
| DEV-DL-11-12 | During handoff remove/reinsert the storage card; compare internal-copy fallback and denied notifications. | Preserve owner volume/manifest/bytes, no false corruption or redownload; stored preference and Downloads UI remain truthful. |

All phone UI, foreground notification, background quotas, process restart, removable-card and audible
playback outcomes remain pending. This change does not close #108/#109/#110/#120 or their hardware gates.
R-121 orphan cleanup remains separately scoped; Silo is deferred and iOS stays low priority.

### Additional R-123 automated follow-up — 2026-10-04

AUTO-DL-12-01 has since reproduced stale bytes on a second cancellation after a fresh/declined-range
replacement. The [follow-up review](2026-10-04-download-restart-progress.md) records the correction,
automated guards and six missing physical cases. The earlier completed-restart evidence above remains
valid; visible progress, process restart and absent-card hardware acceptance remain pending.
