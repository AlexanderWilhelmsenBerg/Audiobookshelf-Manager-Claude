# Restarted download cancellation: truthful durable progress

Requirements: DL-001/002/003; roadmap R-123; related #108. Offline & Downloads implementation lane.

## Reproduced defect

AUTO-DL-12-01 uses real coroutine cancellation, Room and filesystem writes with a synthetic gateway.
A stored 1,000-byte part (2,000 expected bytes) restarts without a validator. After writing 300 bytes,
the next Pause left the manifest at 1,000 bytes: reconstructed progress would be 50% instead of 15%.
A declined range had the same failure; cancelling immediately after truncation retained 1,000 instead
of zero. All three guards failed against unchanged main. The unavailable-owner and before-sink cases
passed; neither licenses erasing progress merely because a request started.

## Correction and callers

The cancellation wrapper remembers a fresh sink only after FileOutputStream opens successfully.
With the original storage owner available and the part still present, it saves the actual length,
including decreases after confirmed truncation. With the owner absent, the last durable count remains.
Cancelling before sink creation preserves the existing part. Persistence remains NonCancellable and
cancellation is rethrown. No unvalidated Range request, destructive migration or committed-media
removal is introduced. The normal and 416 clean-restart requests both supply the same owner check.

BookDownloader reaches FileDownloader. The Downloads row, book button and notification coordinator
already reconstruct progress with durableDownloadProgress when live execution progress is absent.
This correction therefore updates their common durable input without a new execution adapter.
No endpoint, response shape, dependency or permission changes; existing gateway contracts remain.

## Automated verification

| Case | Evidence / expected outcome |
| --- | --- |
| No-ETag restart, second real cancellation | Part and manifest 300 bytes; book remains Paused; durable percent 15; cancellation rethrown; committed sibling unchanged. |
| Declined validator/range, second cancellation | Request asks from 1,000; declined response replaces part; durable bytes/percent reflect 300/15. |
| Cancel immediately after truncation | Existing empty part saves zero durable bytes/percent. |
| Owner becomes unavailable after replacement | Retain 1,000 known bytes/50%; no false downgrade or sibling removal. |
| Cancel before opening replacement sink | Original 1,000-byte part and manifest stay intact. |

All 27 FileDownloader cases and all 82 download-module tests passed (zero failures/errors/skips).
Formatter and full verifyDebug warnings-as-errors passed in 2m 31s (1,119 tasks). No classpath changes.
The initial full gate caught a test-helper coroutine-style violation; coroutineScope corrected it without
suppression, and the final gate reran the module tests. Logs remain in ignored build/no-etag-evidence.
The initial test-fixture compile mismatch was corrected before the meaningful three-failure red run.

## Missing physical tests — NOT RUN, logged at the owner's request

| ID | Procedure | Required observation |
| --- | --- | --- |
| DEV-DL-12-01 | Controlled no-ETag server: pause at a known byte count, resume, pause again before regaining it. | Actual part, Downloads/book row and notification agree; no unvalidated range; committed prior audio retained. |
| DEV-DL-12-02 | Repeat with changed ETag/declined Range. | Replacement bytes and displayed paused percent agree; no spliced media. |
| DEV-DL-12-03 | Force-stop/reopen after the second pause. | Paused state and smaller durable count reconstruct correctly; explicit resume safely restarts/resumes as justified. |
| DEV-DL-12-04 | Remove the owning card during replacement/cancellation; reinsert intact. | Unavailable state retains last known manifest; intact copy re-verifies; no corruption label/deletion or silent move. |
| DEV-DL-12-05 | Slow disk, concurrent removal/new claim, network errors and permission/credential handoff. | Record late-write races separately under R-120/R-122; protect claims and committed media. |
| DEV-DL-12-06 | Repeat while listening and with concurrent downloads, metering and denied notification permission. | Audible continuity, independent notifications and truthful controls; no new network-constraint behavior. |

These checks need controlled server/storage/headset fixtures, not merely a connected phone. They remain
pending; #108 and the broader hardware issues stay open. Shared-cache/Silo implications remain deferred
for separate research; iOS stays low priority. Continue the reliability roadmap with these limits recorded.
