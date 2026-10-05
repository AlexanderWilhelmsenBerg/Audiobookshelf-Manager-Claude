# Android PR merge and delivery record — 2026-10-05

**Classification:** Current delivery record with source-specific acceptance and follow-up obligations.
**Runtime main:** `cd432f4296af8cfd724cc7da5ffd7e9754bb05c0`.
**Owner direction:** Focus on merging; merge despite unclear remaining acceptance and report what to watch.
This supersedes the earlier instruction to hold all drafts until every physical case is complete.
It does not turn missing tests into passes or waive a failing automated gate. No phone tests run in this merge session.

## Merged runtime PRs

| PR | Merge/source identity | Evidence | Watch/follow-up |
| --- | --- | --- | --- |
| [#230](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/230) Gesture highlight and Books/Series/Authors/Genres counts | Merge `fe23195b`; head `cf8db633`; 2026-10-05T13:50:18Z | Four rendered gesture regressions, actual-source reversion, strict verification and PR CI pass. Signed2192 fast-tap repeats, owner animation/TalkBack and selected heard navigation pass. | Other restoration paths, live catalogue/profile/permission changes and full count state matrix. |
| [#231](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/231) Automatic backdrop sampling for book rows | Merge `7dc17e33`; head `c2258229`; 2026-10-05T13:51:16Z | Strict verification and PR CI pass. Forty alternating benchmark executions show CPU P95 improvement of 8.94%/6.80%; owner saved-settings texture looks good. | Every measured CPU P95 remains above 16.7ms. Other blur/theme/older-API configurations and timing after adaptive row height remain unverified. |
| [#232](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/232) Download cancellation checkpoints and durable paused progress | Merge `5f48f581`; head `218417cc`; 2026-10-05T13:51:21Z | 87 downloads/52 caller tests, three native Android parser/Room/file guards and actual-source reversion proof pass. Forced strict verification/PR CI pass. Selected signed 2184 Pause/relaunch/discard/Resume checks pass. | Actual WorkManager stop/network-boundary races, Range/ETag faults, shared claims/credential changes, storage/reboot/timeouts and notification/audio matrices. |
| [#234](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/234) Grouped Author screen and detail-return scroll retention | Merge `5d9d8478`; head `7f5b82e4`; 2026-10-05T13:51:26Z | 19 Author guards, strict verification and PR CI pass. Selected normal/200% completion glow, mixed grouping, offline navigation and corrected scroll-return chain pass on 2191. | Other restoration/configuration, coauthor/completion edge cases and profile/privacy transitions. |
| [#235](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/235) Pushed Sign-in Back and readable toolbar title | Merge `33eb204b`; head `a55c6931`; 2026-10-05T13:51:59Z | 31 scoped cases, strict verification and PR CI pass. Isolated successful Add/reauth, IME/predictive Back, delayed-login cancellation, process death, background/recreation and refused connection checks pass. | Other authentication/error stages, widths/themes/languages and restoration configurations. |
| [#236](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/236) Fully visible book-row duration/progress metadata | Merge `cd432f42`; head `1d4ba3bf`; 2026-10-05T13:52:04Z | Four native geometry guards fail before/reverted source and pass fixed; six combined renders and strict verification/PR CI pass. Verified signed 2195 retains data/settings; normal Norwegian owner appearance and cached offline/detail/Back checks pass. | Physical 2195 200% text, focused/Author standalone coverage and other configurations/affected timing remain NOT RUN. Broader #192/#194 redesign remains open. |

All six PRs had passing verification CI at their heads and were merged in dependency order. #230 first;
then independent #231/#232/#234; #235 after #234; #236 last. No admin bypass or force push was used.
Issues remain open where broader acceptance is unperformed. The partial row fix does not close #192/#194.
The owner accepts default-settings sampling texture; the measured improvement is retained, with the
16.7ms budget still exceeded and other visual/device configurations explicitly pending.

## Phone delivery already accepted before stopping tests

Signed2195/source `eedcbd1ed44d7fcc2f5be594c76d74a45758e6f2`, version 0.10.6.1,
package `org.homebord.bookwave.debug`, artifact 11347844783.
APK SHA256: `6633cd39d16b89434b1bca4d1f0e40716b5174b9c5687c17a3b5721964e14e54`.
Signer SHA256: `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`.
Loopbound pin: `7e24529b2a9e419218d2a1423d832b1bf587c8ef`.
Packaging [run 37316723646](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37316723646)
PASS with `run_checks=false`. Exact source had local strict PASS 3m24s/1,022 tasks. The distinct checked
[run 37312350651](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37312350651)
is CANCELLED, with no final Gradle success, and is not accepted as a green source gate.

Install at 13:34:55UTC preserves eleven counts, ten non-profile table fingerprints, profiles except
normal lastUsedAt and exact settings. Normal Norwegian Home detail/Back and cached offline checks PASS
at 13:36:22UTC without Play. Owner row appearance PASS: “Looks good; bottom lines readable”.
Physical 2195 200% text/focused/Author standalone and broader configuration/timing cases remain NOT RUN.
Synthetic large-text native guards PASS. Raw catalogue captures/DBs remain private and ignored.
[Numeric identity/result evidence](evidence/merge-delivery-2026-10-05.json).

Phone testing is stopped. The phone was disconnected when restoring the temporary screen timeout
was attempted; timeout restoration is NOT DONE. Last text scale was1.0 and network settings restored.
No new functional or visual phone prompt is pending.

## Merged-main verification and delivery

Formatter and forced full `verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks`: PENDING.
Forced rerun covers the #232 Android-test classpath addition after the prior combined source gate.
Final-main Standard CI and release/security run automatically on the documentation merge; a checked signed debug APK is dispatched alongside them. Live results are available in the repository Actions page.
Started/cancelled workflows are not passes. Final-main APK is not installed or physically accepted.

## Next work

Keep download wire/storage/claim races and navigation/profile restoration in the detailed
[test inventory](2026-10-05-pr-merge-test-plan.md); no phone work until the owner offers it again.
Next software slice is #195 non-color connection status and no-results recovery, after any reproduced
playback/progress/privacy defect. iOS/Silo are excluded; Garmin stays parked.
