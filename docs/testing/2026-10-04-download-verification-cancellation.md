# Download verification cancellation — 2026-10-04

**Classification:** Active verification log; software and physical evidence are separate.
**Owner:** Offline & Downloads, implementation owner.
**Requirements:** DL-001/002/003/004, AUTH-002; PRODUCT_SPEC 12/21; PD-003/004.
**Base:** Browse PR #230 (`69d0a3ca`), retaining its gesture/count fixes; planning #226 and Android main `a20bb5b9` underneath.
**Change:** Stop cancellation during synchronous media verification from committing or clearing a part.
**Device:** Samsung SM-S928B, Android 16/API 36 — scoped download/recovery checks PASS on APK 2183; the controlled timing and full hardware matrix remain pending.

## Reproduction and scope

The actual FileDownloader, a real Room database and real app-private files reproduce cancellation while
the media verifier returns. The verifier double cancels the actual child Job; it does not merely throw a
CancellationException. Before the fix, all three initial guards fail because the part is renamed despite
cancellation: fresh body, smaller declined-range replacement and complete validated `416` part.

The transport already checks cancellation between copy buffers. Synchronous media verification is a
separate boundary after that loop. The correction checks cancellation after verification, before rename
or a stale-416 restart, saves available part bytes/response metadata without completing the file, and
rethrows cancellation. Pause remains Paused. Previously committed siblings stay intact. A later worker
still follows the normal Range/If-Range and media-verification path.

This does not make WorkManager cancellation and deletion atomic. R-120's late kernel writes and new-claim
window remain open; no cross-WorkManager lock or new execution-state owner is introduced. R-121 orphan
cleanup remains outside this slice. No endpoint, response contract, schema, dependency or permission change.

## Automated evidence

| Case | Required proof | Result |
| --- | --- | --- |
| DC-A01 | Fresh response: cancelled verifier keeps part and checkpoints bytes/response, no Complete. | PASS after correction; pre-fix and reverted correction FAIL at rename assertion. |
| DC-A02 | Declined range: 1,000-byte stale part replaced by 300 bytes, cancellation records 300 and current validator. | PASS after correction; pre-fix and reverted correction FAIL at rename assertion. |
| DC-A03 | Valid complete `416`: cancellation leaves verified part resumable, not renamed. | PASS after correction; pre-fix and reverted correction FAIL at rename assertion. |
| DC-A04 | Invalid `416` container: cancellation never clears the part or requests a replacement. | PASS after correction; reverted correction FAIL because the part was cleared. |
| DC-A05 | Unavailable owner preserves old bytes/validator; resume after each guard, real cancellation/checkpoint/restart/volume/integrity/ownership suite. | PASS — five guards/87 downloads-module tests; reverting the correction makes all five guards fail. |
| DC-A06 | Actual caller audit; formatter and strict full verifyDebug. | PASS — planning base 4m39s; final browse base 3m34s, each 1,122 tasks; actual callers audited. |
| DC-A07 | Source-matched Standard CI and trusted signed APK, source/package/signature verification. | PASS — runtime CI 37217889783; Standard+APK request 37217933865; trusted APK 37218423235; immutable runtime source/package/version/digest/signer checked. |

The ignored local logs preserve initial failures and later runs. Every fix guard must fail with the
correction reverted. Fixture identifiers are synthetic; no owner titles, filenames, server hosts or
credentials are published. Robolectric verifies filesystem/Room decisions, not the Android media parser,
WorkManager timing, audible playback or device storage behavior.

## Physical test queue — scoped results below, remaining steps NOT RUN

Prepare disposable, authorized same-server A/B profiles; a multi-file audio fixture with known sizes and
committed first track; a controlled server/proxy with captured headers. Use the existing contracted
download endpoints. Record exact APK/source/signature, API/device, fixture configuration, UTC timestamps,
observed bytes/state and privacy-safe evidence. Never clear/uninstall the owner's app or delete owner media
to prepare a fixture. Changes to server responses/storage must use disposable fixtures.

| Case | Steps and required result | Register |
| --- | --- | --- |
| DC-P01 | Verify signed APK, upgrade in place, About/source and retained profiles/settings/downloads/progress. | Q-01/A-08/D-09; PARTIAL PASS: in-place upgrade/About/counts and original downloads/claims/progress; full preference baseline NOT RUN; paused MediaSession holder was not retained by APK replacement. |
| DC-P02 | Download normally, Pause repeatedly near the last byte and media-validation interval; check Book and Downloads stay Paused with truthful bytes, no premature Downloaded, no new final file after observed cancellation. Resume verifies and commits once. A timing attempt without proof of the verifier phase does not accept the specific boundary. | D-03/D-10; PARTIAL PASS: real mid-file Pause/explicit Resume; controlled last-byte/verifier-phase acceptance NOT RUN. |
| DC-P03 | Controlled slow validation/storage fixture: trigger actual worker cancellation during valid container verification and invalid complete-416 verification. Confirm no rename or stale-part deletion after cancellation, current partial checkpoint and no full replacement request. If the phase cannot be observed/controlled with the shipped build, record NOT RUN and use an isolated device harness; do not infer it from random taps. | D-10; NOT RUN. |
| DC-P04 | Range/If-Range `206` resume after Pause, then force a changed-validator `200` replacement; cancel near validation. Recorded percent may decrease to actual replacement bytes; committed sibling retained; clean resume does not splice old/new bytes. | D-03/D-12; NOT RUN. |
| DC-P05 | No ETag response, short/invalid body and complete/stale `416` responses. Pause and Retry through process restart. No unvalidated resume, invalid media never playable; known current response metadata retained only with available storage. | D-12; NOT RUN. |
| DC-P06 | Pause, force-stop/SIGKILL/relaunch and reboot as separate cases. Paused bytes/claims persist; Resume is explicit; constraints/backoff/waiting remain truthful and restart safely. Kill during verification separately does not prove cooperative cancellation. | D-01/D-03; PARTIAL PASS: Paused force-stop/relaunch and explicit Resume; SIGKILL/reboot, verification-phase kill and constraints/backoff NOT RUN. |
| DC-P07 | Last-claim Stop/Remove near end with slow disk; partial bytes and manifest removed, no late orphan final copy. Add B's claim during removal and repeat; B's valid claim/media are retained or any cancellation race is explicitly recorded. Check startup orphan sweep only on disposable media. | D-05/D-10/R-120; PARTIAL PASS: ordinary last-claim removal of test copy; slow-disk/late-write/new-claim races NOT RUN. |
| DC-P08 | Shared in-flight A/B transfer: A Stop releases its claim without cancelling B; no shared Pause. Sign out/remove A between files and before cover; remaining eligible B supplies credentials without constraint relaxation; denied/expired B never authorizes a request. | D-05/D-11; NOT RUN, plus all DEV-DL-11 steps. |
| DC-P09 | Two independent downloads, live Book/Downloads/notification progress, notification tap, denied notification permission, waiting/backoff/terminal Retry; one Pause does not stop the other. | D-01/D-02/D-04; PARTIAL PASS: one transfer and two stored rows, Book/Downloads Pause/Resume; two transfers/notifications/waiting/backoff/Retry NOT RUN. |
| DC-P10 | Internal and removable volume, remove/reinsert same/different card during validation/cancellation, low space/write failure. Missing owner preserves last known bytes, unavailable differs from corrupt, no guessed fallback/deletion. Use removable hardware only if available. | D-08/D-09; PARTIAL PASS: internal-volume transfer, exact file sizes and native full verifier; removable/low-space/write-failure cases NOT RUN. |
| DC-P11 | Play real committed offline audio while another book downloads/pauses/retries/removes; listen across chapters/background/lock screen and inspect progress/outbox. No queue, position, audio or entitlement interruption. Five cached starts and concurrent-transfer stress remain required. | P/Q-05/D-03/D-05; NOT RUN. |
| DC-P12 | Actual TalkBack (percent spoken once, Pause/Resume/Stop/Retry labels), English/Norwegian, 200% font, portrait/landscape and reduced motion; all confirmation actions reachable and no clipping. Switch/lock profiles and another-server matching item ID; no private title/failure leak. | D-06/D-07; NOT RUN. |
| DC-P13 | API 26/31/34/36 and Android 15+ long dataSync timeout: separately record parser/WorkManager stop, partial recovery and constraints. Also run the environment preflight and :core:datastore:connectedDebugAndroidTest with its isolated instrumented app; record the exact source, results and isolation. No timeout/security result inferred from this cancellation guard. | D-14/Q-02; PARTIAL PASS: API36 preflight and 27 isolated datastore/security tests; remaining API/timeout/parser-stop matrix NOT RUN. |

The full [D-01–D-15 register](roadmap-verification-register.md) remains authoritative. D-13's unclaimed-copy
space behavior and D-15's smart-download/retention matrix still need their existing tests; they are not
accepted or redesigned by this change. Only the scoped observations below accept this binary. All remaining steps above are NOT RUN.
Prior APK 2179/2181 results do not accept this binary.

## Caller audit

BookViewModel's download/resume/retry path and DownloadsViewModel row actions reach DownloadBookUseCase
and WorkManagerDownloadScheduler. BookDownloadWorker.doWork calls BookDownloader.download, whose current
claim resolver calls FileDownloader.download for each incomplete file. Both final verification and the
complete-416 admission check reach the new cancellation boundary. Book-level markComplete remains after
the files, so rethrown cancellation cannot continue to cover/book completion. Pause persists Paused then
cancels; Stop uses existing claim-aware removal. Neither entry starts a second download-state owner.

## Software result — before browse-base integration

`ktlintFormat verifyDebug -Pshelfplayer.warningsAsErrors=true --max-workers=4` passed in 4m39s with
1,122 tasks (442 executed, 579 from cache, 101 up-to-date). Lint, detekt, unit/integration tests,
debug aggregate/redaction coverage, release and benchmark Kotlin compilation pass. No classpath change
requires forced rerun. All 87 downloads-module cases pass, including 32 FileDownloader cases, 17 real
BookDownloader cases, 17 repository cases, nine storage, ten verifier and two failure-mapping cases.

With the correction actually reverted, all five new Job-cancellation guards fail: four rename after
cancellation; invalid complete-416 verification clears the part. The fixed source was always restored
before the full gate. No device, parser, audible playback or WorkManager timing result is implied.
[Numeric software evidence](evidence/download-verification-cancellation.json) omits hostname/private strings.

The branch is based on browse PR #230 (`69d0a3ca`) to retain the owner's installed gesture/count fixes
in the downloads APK. It is independent of experimental blur PR #231. The combined exact runtime source
`ace3da2bab10016943f7a25e0fb6c4a1e39adf3d` passed the same strict formatter/verifyDebug command in 3m34s,
with 1,122 tasks (59 executed, 15 from cache, 1,048 up-to-date). That repeated gate is required by the
new browse integration; the downloads-module code/evidence is unchanged. CI/artifact results follow below.
Scoped phone results follow. [Draft PR #232](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/232)
contains this bounded correction; no merge or whole-requirement acceptance is claimed.

## Signed delivery and phone evidence — APK 2183

The trusted APK workflow resolved and checked PR #232's immutable runtime source
`ace3da2bab10016943f7a25e0fb6c4a1e39adf3d` before checkout/signing. Its workflow control revision is
trusted main, not the APK source. Package `org.homebord.bookwave.debug`, version `0.10.6.1`/2183,
embedded source `ace3da2bab10`, Loopbound `7e24529b2a9e419218d2a1423d832b1bf587c8ef`.
APK SHA-256 `5a565604d41f07b1897437d4fd572a12693bfbe4e8e267584cfdbd77492730c8`;
signer SHA-256 `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`.
Artifact 11309325674's archive digest matched. About independently matched the embedded source/version.

[Runtime PR CI](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37217889783),
[requested Standard verification](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37217933865)
and [trusted signed APK](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37218423235)
all passed. Later documentation commits do not change the tested binary's runtime source.

| Observed case | Exact evidence and limit |
| --- | --- |
| Environment/security | `scripts/check-local-environment.sh` passed for phone/JDK/SDK; optional Docker absent. `:core:datastore:connectedDebugAndroidTest --max-workers=4`: 27 tests, zero failures/skips, 1m39s, 74 tasks. The isolated instrumented app did not clear owner data. |
| In-place upgrade | Owner debug 2181 → signed 2183 using `adb install -r --user 0`; no uninstall/clear. Two profiles, one server, two original books/files/claims and 55 progress rows retained. Server/download/file/claim/progress fingerprints match before install and after cleanup. Full profile-row fingerprints changed; no pre-upgrade preference snapshot exists, so byte-identical profile/settings retention is not claimed. |
| Normal transfer | Previously absent, currently entitled 21-track fixture, 148,140,184 audio bytes, internal owner. Initial transfer and later explicit Resume both reached Complete; every final size equals expected/downloaded bytes, no parts remain. No owner titles/IDs/hosts/filenames published. |
| Pause/restart/Resume | At 17:30–17:32 UTC: six committed files retained; 5,807,993-byte part matches Room. Book remained Paused after force-stop and relaunch, shown in active/queued section with Resume and two original stored rows. Explicit Resume completed all 21 files. Interrupted file had no saved ETag/Last-Modified, so this does not prove byte reuse or a captured Range/206 response. |
| Partial-data discard | Separate transfer at 17:35–17:36 UTC: seven completed files plus a 7,635,182-byte part. Cancel (Behold) preserved all file rows/sizes and claim; confirmed discard removed only unfinished bytes, retained all seven completed files and one claim, book stayed Paused, listening progress unchanged. |
| Native full verification | Fixed log event reported two books/two files/zero broken first, then three books/23 files/zero broken after Resume. This proves the native full verifier ran, not server checksum equivalence, audible playback, or cancellation within native verification. |
| Cleanup | At 17:38 UTC, fixture-specific UI removal deleted only the new fixture's claim/manifest/files and observed physical directory. Original two download/file/claim fingerprints and all 55 listening-progress rows match the baseline. No Play command was issued. |

Private UI/SQLite snapshots stay in ignored `build/phone-downloads-2183`; temporary SQLite copies were
deleted after each read-only check. Raw captures and private fixture configuration are not committed.
The first pause attempt completed before observation because of a helper's XML-element truth-value bug;
the helper was corrected before the two successful pauses. Other helper guards rejected stale/ambiguous
UI, an unnecessary browse toggle, or empty unstarted-file URIs; those attempts are not app failures
or passed app tests. Search was closed and the original Books shelves view restored. Font scale stayed
at its baseline 1.0; stay-awake setting was restored to its exact baseline 15. The temporary UI-capture
package was removed for user 0; the owner app and original downloaded media remain installed.

The prior paused MediaSession holder did not persist across APK replacement; opening Home/Details did
not issue Play. Listening progress remained intact, but active playback/holder continuity during upgrade
is not accepted. Controlled verification cancellation, changed response/416 fixtures, Range tracing,
SIGKILL/reboot, sharing/credentials/concurrent transfers, removable/low-space storage, timeout, audible
playback and accessibility matrices remain NOT RUN. R-120–R-123 stay open; no whole D-case is closed.
