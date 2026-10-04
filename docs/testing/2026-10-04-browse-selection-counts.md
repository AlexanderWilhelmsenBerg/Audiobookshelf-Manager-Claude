# Browse selection and counts — 2026-10-04

Requirements: LIB-001/002, AUTH-002, PRODUCT_SPEC 17.2/21; PD-006/007; GitHub #227/#228.
Implementation is on `fix/browse-selection-counts`, stacked on planning PR #226. This report records
the bounded navigation/count slices; author details #229 remain queued after performance/download work.
Garmin remains parked; neither watch research nor other platforms start here.

## Reproduction and source evidence

A stable Home actions callback reproduced the reported mismatch through the actual HomeScreen pager:
Books → Series → Books ended with axis Series, and Authors → Genres → Authors ended with axis Genres.
The listener filtered settled pages against the initial captured axis. Reading the latest axis/callback
with rememberUpdatedState fixes that comparison without replaying a settled page during a tab animation.
The original continuous pill position, settled-page policy and pager/ViewModel ownership remain.

The rendered count regression failed because the Series header said `2 books` rather than `2 series`.
Counts now derive distinct book/series/group identities from the displayed authorized Room-backed rows.
Books shelf previews retain their uncapped source total. Partial refresh and offline captions keep their
caveats while naming the displayed entity; loading, failure and never-synced status remain distinct.
There is no API/endpoint, schema, profile, progress or playback policy change.

Production caller audit: HomeRoute binds HomeViewModel::onAxisChanged into HomeScreen → rememberAxisPager;
ShelfHeader → syncStatusLabel/entityCountLabel → visibleEntityCount reaches the new count projection.
Existing HomeViewModel axisRows preserves current and preview ownership and loaded-versus-empty state.

## Automated verification

- Pre-fix `browse-red-confirmed.log`: four selected tests, three failures (both return gestures plus entity
  labels); the cancelled drag passes. These are actual caller-path failures, not isolated helper tests.
- First fixed run: 33 HomeScreen/gesture/selection tests pass.
- Added count coverage: distinct identities/zero values, focused book results/profile replacement,
  partial/offline/loading states and Norwegian singular/plural/partial text pass.
- Native normal/200% text fixture renders pass. Final `ktlintFormat` and
  `verifyDebug -Pshelfplayer.warningsAsErrors=true` pass (4m32s; 1,122 tasks), including all 39 targeted
  HomeScreen/gesture/selection/count/native-render cases. No classpath change requires a forced rerun.
- The initial full gate caught obsolete `home_offline_caption` as an unused resource. Reusing that resource
  for the counted offline caption removed the error; the failed log remains evidence.
- Physical `:core:datastore:connectedDebugAndroidTest`: **27 tests passed, zero failures/errors/skips**
  on Samsung SM-S928B, Android 16 / API 36. The ignored init script uses the same disposable test-package
  override as the prior phone acceptance; it does not replace the owner's application or its data.
  Environment checker passes; Docker remains optional/absent because no endpoint fixture changes here.

Fixture-only native render evidence:

![English author count and selected axis](evidence/browse-authors-en-375dp.png)

![Norwegian author count at 200% text](evidence/browse-authors-nb-375dp-font2.png)

The fixture's three books count as one author. These are native Robolectric renders, not physical phone
or actual TalkBack evidence. The native snapshot's glass/backdrop appearance is not a real-theme contrast
acceptance claim. Private phone images/XML/database snapshots remain under ignored build evidence.

## Physical browse acceptance

The phone was connected/unlocked and started on signed debug 0.10.6.1 / code 2179. Production playback was
paused. A private pre-upgrade Room snapshot passed quick_check: 2 profiles, 522 cached books, 55 progress
rows, 124 history rows and 2 downloaded books. Only aggregate observations/hashes are shared.
The initial UI dump could time out during animation; failed dumps and earlier cached XML are not accepted
as fresh evidence. Signed fixed-APK installation and app-function cases await the delivery below.

| Case | Current evidence / remaining obligation | Status |
| --- | --- | --- |
| U-06-01 | Both return-gesture caller regressions fail before/pass after. Exact signed phone round trip pending. | Automated PASS; physical NOT RUN |
| U-06-02 | Existing gesture tests preserve adjacent changes/first-axis edge. All-axis physical traversal/last-axis edge pending. | Partial automated PASS; remaining NOT RUN |
| U-06-03 | Stable callback tap/return and cancelled short drag pass. Rapid reversals/tap during settlement pending. | Partial automated PASS; remaining NOT RUN |
| U-06-04 | Non-default initial Authors and tap/return pass. Activity recreation/detail Back acceptance pending. | Partial automated PASS; remaining NOT RUN |
| U-06-05 | Selected destination assertions and offscreen semantics tests pass. Actual TalkBack focus/announcements pending. | Semantics PASS; actual TalkBack NOT RUN |
| U-06-06 | Native normal/200% fixture evidence is logged below. Active audio/offline/orientation/reduced-motion physical matrix pending. | Physical NOT RUN |
| U-07-01 | Distinct entity identities/zero values, rendered one/many and uncapped shelf total pass. Physical catalogue comparison pending. | Automated PASS; physical NOT RUN |
| U-07-02 | English/Norwegian singular/plural, focused book results pass. Physical search/filter and spoken-label checks pending. | Automated PASS; physical NOT RUN |
| U-07-03 | Count projection replaces previous profile rows; existing repository authorization stays owner. Live library/profile/revocation matrix pending. | Partial automated PASS; physical NOT RUN |
| U-07-04 | Partial/offline/loading rendered states pass; original failed/never-synced tests remain. Live failure/partial-sync injection pending. | Automated PASS; live injection NOT RUN |
| U-07-05 | Rendered state changes and cached/offline captions pass. Physical Room updates/gestures/large text/playback pending. | Automated PASS; physical NOT RUN |

U-08 author-detail cases remain NOT RUN awaiting #229; broad release/download/performance/host acceptance
is not inferred from this browse slice. Each later phone result must identify its actual source/APK,
UTC/configuration/expected/observed evidence, and preserve the above pending portions.
