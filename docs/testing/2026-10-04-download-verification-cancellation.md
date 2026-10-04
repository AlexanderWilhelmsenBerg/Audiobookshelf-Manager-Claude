# Download verification cancellation — 2026-10-04

**Classification:** Active verification log; software and physical evidence are separate.
**Owner:** Offline & Downloads, implementation owner.
**Requirements:** DL-001/002/003/004, AUTH-002; PRODUCT_SPEC 12/21; PD-003/004.
**Base:** Android main `a20bb5b9`, with planning PR #226 (`5be5280f`) as documentation base.
**Change:** Stop cancellation during synchronous media verification from committing or clearing a part.
**Device:** NOT RUN — owner will reconnect the phone after software work.

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
| DC-A06 | Actual caller audit; formatter and strict full verifyDebug. | PASS on planning base — 4m39s/1,122 tasks. Final browse-base integration gate pending. |
| DC-A07 | Source-matched Standard CI and trusted signed APK, source/package/signature verification. | Pending. |

The ignored local logs preserve initial failures and later runs. Every fix guard must fail with the
correction reverted. Fixture identifiers are synthetic; no owner titles, filenames, server hosts or
credentials are published. Robolectric verifies filesystem/Room decisions, not the Android media parser,
WorkManager timing, audible playback or device storage behavior.

## Physical test queue — all NOT RUN

Prepare disposable, authorized same-server A/B profiles; a multi-file audio fixture with known sizes and
committed first track; a controlled server/proxy with captured headers. Use the existing contracted
download endpoints. Record exact APK/source/signature, API/device, fixture configuration, UTC timestamps,
observed bytes/state and privacy-safe evidence. Never clear/uninstall the owner's app or delete owner media
to prepare a fixture. Changes to server responses/storage must use disposable fixtures.

| Case | Steps and required result | Register |
| --- | --- | --- |
| DC-P01 | Verify signed APK, upgrade in place, About/source and retained profiles/settings/downloads/progress. | Q-01/A-08/D-09; NOT RUN. |
| DC-P02 | Download normally, Pause repeatedly near the last byte and media-validation interval; check Book and Downloads stay Paused with truthful bytes, no premature Downloaded, no new final file after observed cancellation. Resume verifies and commits once. A timing attempt without proof of the verifier phase does not accept the specific boundary. | D-03/D-10; NOT RUN. |
| DC-P03 | Controlled slow validation/storage fixture: trigger actual worker cancellation during valid container verification and invalid complete-416 verification. Confirm no rename or stale-part deletion after cancellation, current partial checkpoint and no full replacement request. If the phase cannot be observed/controlled with the shipped build, record NOT RUN and use an isolated device harness; do not infer it from random taps. | D-10; NOT RUN. |
| DC-P04 | Range/If-Range `206` resume after Pause, then force a changed-validator `200` replacement; cancel near validation. Recorded percent may decrease to actual replacement bytes; committed sibling retained; clean resume does not splice old/new bytes. | D-03/D-12; NOT RUN. |
| DC-P05 | No ETag response, short/invalid body and complete/stale `416` responses. Pause and Retry through process restart. No unvalidated resume, invalid media never playable; known current response metadata retained only with available storage. | D-12; NOT RUN. |
| DC-P06 | Pause, force-stop/SIGKILL/relaunch and reboot as separate cases. Paused bytes/claims persist; Resume is explicit; constraints/backoff/waiting remain truthful and restart safely. Kill during verification separately does not prove cooperative cancellation. | D-01/D-03; NOT RUN. |
| DC-P07 | Last-claim Stop/Remove near end with slow disk; partial bytes and manifest removed, no late orphan final copy. Add B's claim during removal and repeat; B's valid claim/media are retained or any cancellation race is explicitly recorded. Check startup orphan sweep only on disposable media. | D-05/D-10/R-120; NOT RUN. |
| DC-P08 | Shared in-flight A/B transfer: A Stop releases its claim without cancelling B; no shared Pause. Sign out/remove A between files and before cover; remaining eligible B supplies credentials without constraint relaxation; denied/expired B never authorizes a request. | D-05/D-11; NOT RUN, plus all DEV-DL-11 steps. |
| DC-P09 | Two independent downloads, live Book/Downloads/notification progress, notification tap, denied notification permission, waiting/backoff/terminal Retry; one Pause does not stop the other. | D-01/D-02/D-04; NOT RUN. |
| DC-P10 | Internal and removable volume, remove/reinsert same/different card during validation/cancellation, low space/write failure. Missing owner preserves last known bytes, unavailable differs from corrupt, no guessed fallback/deletion. Use removable hardware only if available. | D-08/D-09; NOT RUN. |
| DC-P11 | Play real committed offline audio while another book downloads/pauses/retries/removes; listen across chapters/background/lock screen and inspect progress/outbox. No queue, position, audio or entitlement interruption. Five cached starts and concurrent-transfer stress remain required. | P/Q-05/D-03/D-05; NOT RUN. |
| DC-P12 | Actual TalkBack (percent spoken once, Pause/Resume/Stop/Retry labels), English/Norwegian, 200% font, portrait/landscape and reduced motion; all confirmation actions reachable and no clipping. Switch/lock profiles and another-server matching item ID; no private title/failure leak. | D-06/D-07; NOT RUN. |
| DC-P13 | API 26/31/34/36 and Android 15+ long dataSync timeout: separately record parser/WorkManager stop, partial recovery and constraints. No timeout result inferred from this cancellation guard. | D-14/Q-02; NOT RUN. |

The full [D-01–D-15 register](roadmap-verification-register.md) remains authoritative. D-13's unclaimed-copy
space behavior and D-15's smart-download/retention matrix still need their existing tests; they are not
accepted or redesigned by this change. Physical cases above remain NOT RUN until device-specific evidence
is recorded. Prior APK 2179/2181 results do not accept this binary.

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

The branch will be rebased onto browse PR #230 (`69d0a3ca`) to retain the owner's installed gesture/count
fixes in the downloads APK. That source is independent of experimental blur PR #231; the final combined
strict gate/CI and signed artifact are recorded separately when complete. All DC-P cases remain NOT RUN.
