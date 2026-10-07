# Grouped author details — 2026-10-04

> **Historical source-scoped evidence.** Reconciled 2026-10-07: the relevant runtime PR is now merged. Original failures, draft build identities and NOT RUN statements below describe their recorded sources/dates. Later scoped results and current obligations are in the [verification register](roadmap-verification-register.md), [merge record](2026-10-05-merge-delivery.md) and [cross-repository audit](../reviews/2026-10-07-cross-repo-reconciliation.md).


**Classification:** Draft source and automated evidence; physical acceptance remains pending.
**Owner:** UI & Experience, implementation owner. Requirements: LIB-001/002/003/004, PLAY-004,
AUTH-002, PRODUCT_SPEC 5.2/17.2/21; PD-006; issue #229.
Branch `feature/author-series-standalone` is based on PR #230, not the performance or downloads candidates.

## Implemented behavior and boundaries

Home Authors cards now carry a required typed AuthorId navigation callback through HomeActions/HomeRoute
into the existing AuthorRoute, also used by Book-detail author links. Author details show distinct Series
and Standalone books groups. Genre cards retain Home focus; no Home query/filter/order/axis is rewritten
by author navigation. NavHost uses the existing Book/Series destinations and navigateUp.

The domain projection reads the full authorized Room-backed catalogue, deduplicates book identities,
includes coauthors and every series membership, and orders series by name/id and standalone books by
title/id. A series represented by the author includes **all accessible members**, including other authors'
books. Books with any series membership never appear as standalone. Primary membership and sequence
policies are unchanged. Header book count/duration/completion describe the author's own accessible books.

Author series completion requires a nonempty series, authoritative isFinished on every accessible member,
and SyncStatus.Succeeded. NeverSynced, Syncing, PartiallySucceeded and Failed cannot establish catalogue
completeness. Cached books remain available in those states, with visible “Completion not verified”
on series cards. An earlier successful timestamp alone does not certify a possibly partial refresh.
Unknown progress cannot finish a book or series. Local offline state keeps its persisted sync status;
a failed offline refresh conservatively withdraws series completion until a successful refresh.

Completed standalone books and verified complete series reuse the settled slight inward green glow,
without checkmarks, independently of decorative border settings. Finished text and series summaries
provide readable/spoken state; headings have heading semantics. Standalone cards reuse compact,
content-driven series book rows with the Play capability absent. Existing Series detail rows still supply
Play explicitly. CollectionArtwork retains confirmed-portrait and cached-cover fallback behavior.

A new profile stream immediately emits no shelf before its delayed Room query answers. Revocation and
sign-out clear the projection. The app's existing profile-lock curtain remains the lock owner. No new
endpoint, response field, schema, permission, download or playback policy is introduced.

## Automated verification and caller audit

Five AuthorShelf tests cover every membership/coauthors/deduplication, standalone order/portrait,
other-author unfinished series members, unknown progress and missing author. Two use-case tests cover all
sync statuses, revocation, delayed profile switching and sign-out. Two actual HomeScreen click tests
protect author navigation versus genre focus. Seven AuthorScreen tests cover both detail callbacks,
absence of Play, toolbar Back, finished/unverified text, loading/missing states, and native fixture renders.
The existing 25 SeriesBookCardScreenTest cases remain in the scoped run to protect retained Play/glow/geometry.

Regression proof temporarily restores the former Home author focus callback, restricts series members
to the author subset, removes profile-switch clearing, and disregards catalogue completeness. Three domain guards and two actual UI guards failed as expected (other-author membership, catalogue status,
delayed profile clearing, Home author navigation and unverified completion). Source bytes were restored.
Exact final command/results and source pin are recorded in the closeout below.

Caller audit: Authors axis → HomeActions.onAuthorSelected → HomeRoute → NavHost author destination;
BookRoute.onAuthorSelected → same destination; AuthorRoute → AuthorScreen → SeriesCard/SeriesBookCard
→ existing Series/Book destinations. ObserveAuthorUseCase is injected into AuthorViewModel and combines
LibraryRepository.observeAccessibleBooks/observeSyncState for the active profile. No Compose/ViewModel
server calls, and neither author card callback can call Play or replace the queue.

Native evidence uses synthetic metadata and missing-artwork fallbacks: English dark at 320 dp/200% text,
Norwegian light at 375 dp/130% text. Full titles and completion caveats are readable. These renders do
not establish real device contrast, TalkBack, cached network imagery, or the complete acceptance matrix.

## Physical tests to run when the phone returns

Record exact commit, APK version/code/checksum, device/API, language/font/theme, online/offline state and
observed audio. **All physical cases below are NOT RUN for this author implementation.** Use the result
format in the verification register; do not close #229 from software evidence alone.

| Case | Required phone procedure / result |
| --- | --- |
| U-08-01 | From Home Authors and Book's credited author links, open the same author; check identity, portrait fallback and groups. Include second/coauthor and missing author. |
| U-08-02 | Inspect series-only, standalone-only, mixed, coauthored and multi-series books. Distinct series identities/counts/order, no false standalone duplicates; Series detail sequence/primary policy intact. |
| U-08-03 | Finished/in-progress/unstarted/unknown books; fully completed and unfinished other-author series members; active Home search/filter/library scopes. Verify full accessible membership and successful/incomplete/failed refresh caveats, green inward glow/no checkmark and spoken completion. Disable decorative borders and check the completion cue remains. |
| U-08-04 | Tap author Series and Standalone cards; correct existing details, no unintended audio start, queue replacement or progress change. During active playback, listen throughout navigation and return. |
| U-08-05 | Before opening, set non-default Home axis/query/filter/sort and scroll. Test toolbar/system/predictive Back, Book-origin return, rotate/recreate/cold restore. Original Home state and selection align. |
| U-08-06 | Offline cached page, loading/failed/missing portrait/cover, permission revocation, lock, sign-out and slow profile switch while viewing author. No outgoing private metadata/artwork appears after the boundary; cached authorized books remain usable. |
| U-08-07 | 320/375/414/768 dp, fonts 1.0/1.3/2.0, EN/NB, portrait/landscape; light/dark/AMOLED/dynamic/artwork themes, active mini-player, reduced motion. Inspect last-row clearance, contrast, 48 dp targets and actual TalkBack order/headings/completion/Back; larger configurations may require emulator/tablet alongside the supplied phone. |

Other pending candidates retain their separate source-specific tests: PR #230 browse restoration/counts,
#231 alternating performance/quality comparison, #232 controlled transfer/recovery/race/storage/privacy.
The author branch does not contain #231/#232; installing it would remove those draft runtime fixes.
Prepare an explicitly combined verified candidate before claiming one APK covers all four lanes.

## Closeout

`ktlintFormat` and `verifyDebug -Pshelfplayer.warningsAsErrors=true` PASS (1m41s; 1,122 tasks)
including restored domain/caller/render guards, lint, detekt, release compilation and coverage gates.
The initial full gate caught the shared card's optional Modifier parameter order; it was corrected,
without a lint baseline/suppression, before this green run. No classpath change requires --rerun-tasks.
Local ignored logs retain the failed test/harness/lint runs and five expected mutation failures.

Privacy-safe native fixtures: [320 dp / 200% dark](evidence/author-320dp-font2-dark.png) and
[375 dp / 130% Norwegian light](evidence/author-375dp-font1_3-nb-light.png). All 16 added author-related
tests pass, along with the existing 25 series-card cases. CI/APK identity is recorded on the draft PR;
physical U-08-01–07 remain NOT RUN. No issue closure claimed.


### 2026-10-05 caller correction

The [observation/key correction](2026-10-05-author-observation.md) on this same author PR distinguishes
pending queries from unavailable content and prevents opaque identities colliding with section keys.
It adds two actual ViewModel/render guards; initial source-specific results above remain historical.
Author acceptance must include this correction. The dated 2026-10-05 continuation records subsequent physical results; earlier results remain historical.

The same 2026-10-05 report now logs APK2189's failed author-scroll return, its actual-screen
reversion guard and the restored strict gate. The correction retains list state through pending
queries; 19 author cases pass. Physical scroll reacceptance requires the corrected combined APK.

### Delivery prepared during the next software slice

Exact author head `fbbb69d38d7d17e3144e58906b26d2d16f9afda2`: Standard CI
[37229770267](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37229770267)
and automatic PR CI pass. Trusted signed APK workflow
[37230311081](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37230311081)
produced **0.10.6.1 (2187)** / `bookwave-debug-0.10.6.1-2187-pr234.apk`, artifact11313339633.
Artifact/APK-file SHA-256: `bee95bea5e3deda89b2073ac18a3ff5254a2c829389eed90072d96a20d36522b`.
Package `org.homebord.bookwave.debug`; signing certificate SHA-256
`c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`;
embedded author source and Loopbound `7e24529b2a9e419218d2a1423d832b1bf587c8ef` verified.
The trusted workflow itself runs from main `9ced43ae`, separately from the compiled author source.
Downloaded artifact digest, APK package/version/signature and embedded source were verified locally.
**Not installed on the phone**; it excludes #231/#232 and the subsequent Sign-in Back change.
