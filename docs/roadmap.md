# BookWave Android roadmap

**Classification:** Active plan — canonical sequencing authority.
**Reconciled:** 2026-10-04 against GitHub main `9ced43ae` (merged through PR #233),
all open PRs/issues, current build source and dated phone/performance evidence.
PRs #226/#233's planning/documentation reconciliations are merged. Runtime PRs #230/#231/#232/#234/#235 remain drafts with acceptance gaps.

This is the only document that orders the next work. [PRODUCT_SPEC](../PRODUCT_SPEC.md) supplies
requirements, [product decisions](product-decisions.md) own settled behavior, and accepted ADRs own
architecture. GitHub has been authoritative since the 2026-10-01 cutover; unqualified issue/PR numbers
below are GitHub numbers. Historical Forgejo provenance does not create another implementation queue.
The tracker has **46 open issues: 42 active Android, three excluded iOS and parked Garmin #119**.
There are 23 closed tickets, including one accidental ticket (22 substantive closures).
Open issues can contain merged implementation and missing acceptance; this reconciliation closes none.

## Delivered baseline and remaining gates

| Area | Delivered on main | What is still open |
| --- | --- | --- |
| Series, History and sleep | PRs #220/#222: compact, content-driven series rows; inward green completion glow without a checkmark; dated/progress/chapter History and local rolling checkpoints; playing-only timer creation, frozen countdown during pauses, Extra high/Ultra high sensitivity. | #187 covers other card families too. Full appearance/TalkBack, sensor/bedside, power-loss and account/sync matrices are not accepted by the selected phone checks. |
| Playback and car continuity | PRs #205/#211/#213/#216/#217: guarded restore/profile/transport ownership, correct book title and car timer presentation, schedule eligibility and projection lifecycle latch. | Heard headset/projected car, idle restore, controller/privacy and two-hour acceptance. Historical Forgejo issue #36 is GitHub #128; its source regression is fixed, its hardware gate is open. |
| Downloads | Existing execution observer, recovery actions, claims/device pin and discard flow; PRs #207/#209/#215/#218/#221 add UI wiring, current-claim credentials and actual replacement-byte checkpointing. #111 is closed. | Physical transfers, storage, sharing/privacy and R-119–R-123. Closed ownership implementation does not accept its device matrix. |
| CI and security | Main cache seeding, verification tiers, debug aggregate coverage, release/security checks and the existing 90% redaction gate are wired. PR #224 records 96.72% redaction coverage. | #188's residual audit/timing evidence; no controlled CI speedup is claimed. Quick alone is insufficient for runtime acceptance. |
| Phone/performance | Build 2179: selected compact-series checks and 27 connected datastore cases pass. Benchmark 1.5.0 repairs API-36 discovery; eight benchmark executions pass. | Startup fixture meets <1 s; list CPU P95 is 19.145 ms before / 20.560 ms in the profile experiment, above the 16.7 ms comparison budget. Manual cached-audio startup and concurrent download/playback stress are NOT RUN. |
| Delivery | Main `a20bb5b9` passed delivery workflows and produced 2180. Draft browse 2181 and downloads 2184 were installed/tested with retained data; each report identifies its exact runtime. PR #226 is merged at `8de931f0`. | PR #233/main debug, release/security and signed APK2186 pass; exact identity is in the reconciliation report. A main APK does not contain draft fixes; later phone acceptance remains source-specific. 2180 was superseded without a phone run. |

## Merge review and work handled

| PR | State | Handled / remaining |
| --- | --- | --- |
| #226 / #233 | Merged, `8de931f0` / `9ced43ae` | Canonical Android ordering, issue inventory, PD-006/007 and repository documentation reconciliation. No runtime change or issue closure. |
| [#230](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/230) | Draft, based on main | Gesture highlight and axis-specific counts implemented; 39 targeted tests, strict verification and selected API-36 phone checks pass. Actual TalkBack, other detail/restoration paths, live English/search/filter/profile/permission/update cases remain. #227/#228 stay open. |
| [#231](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/231) | Draft, based on #230 | Backdrop-sampling candidate and control-trace analysis prepared; no measured speedup or physical visual-quality acceptance. Alternating control/candidate runs are required before merge. |
| [#232](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/232) | Draft, based on #230 | Cancellation checkpoint guards and stopped-progress precedence implemented. 87 downloads and 52 caller tests pass; APK2184 ordinary Pause/relaunch/discard/Resume/file verification passes. Controlled phase/response/race/storage/credential/notification/accessibility cases remain. |
| [#234](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/234) | Draft, based on #230 | Grouped author destination from Home/Book details; 16 new guards, retained series cases, strict local and exact-head Standard CI pass. Physical U-08-01–07 remain NOT RUN. |
| [#235](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/235) | Draft, based on #234 | #176 root/pushed route Back, separate wizard action, 48 dp target and measured title height; software guards exist and U-03-01–07 phone acceptance remains NOT RUN. |

The draft dependencies are **main → #230 → #231, #232 or the author-details branch**;
performance, downloads and author implementation are independent siblings; Sign-in Back is stacked on the author branch. [Author evidence](testing/2026-10-04-author-details.md) records pending physical acceptance.
Green CI alone does not clear explicit acceptance gaps. Do not merge drafts or close their issues from
this document's evidence import. Source/APK details and the repository-wide audit are in the
[reconciliation report](reviews/2026-10-04-documentation-reconciliation.md).

Use the [2178](testing/2026-10-04-phone-2178.md) and [2179](testing/2026-10-04-phone-2179.md)
reports for exact tested builds and subcases. The [verification register](testing/roadmap-verification-register.md)
owns every required case/result; [risks](risks.md) own unresolved failure modes. Dated reports retain their
original FAIL/NOT RUN entries even when a later run passes.

## Ordered delivery lanes

Any reproduced playback, progress, privacy or permission defect takes precedence over performance,
presentation and new surfaces. Work one bounded requirement group at a time. Missing car/storage hardware
does not block independent software work: carry its acceptance gate forward and record the missing case.

### 1. Finish playback, progress and sleep reliability acceptance

**Owners:** Playback & Lifecycle; Android System & Auto. **Requirements:** PLAY-001/002/004/005/007/008/009,
ROUTE-001/002/003, AUTH-002/003, LIB-002/003; specification 5.2, 6.5, 17 and 21.

- Begin the next phone delivery with Q-01/A-08: verify the exact signed APK, in-place upgrade/data retention,
  About/source identity and the affected smoke cases. Reproduce the inherited warm Starting state before
  selecting a playback fix; it is an observation, not a diagnosed cause.
- Complete P-01–P-12 and C-01–C-08 with controlled local/remote/profile fixtures: offline outbox reconciliation,
  server/local history, power loss/reboot, multi-file chapters and the two-hour soak. Preserve captured profile
  ownership, paused intent and progress-loss limits. Selected offline SIGKILL recovery already passed on 2178;
  that is not power-loss, remote or whole-matrix acceptance.
- #128/#100/#185/#126/#196/#130/#99/#191 await heard-route and projected-host evidence. Run the
  [drive checklist](android-auto-pd001-drive-acceptance.md), [browse checks](android-auto-browse-invalidation-acceptance.md)
  and [continuity matrix](reviews/2026-10-03-issue-128-continuity-review.md). Preserve PD-001's
  Continue → Series → Authors → Profiles root; no History or replacement Queue. Unknown projection reads
  preserve the positive lifecycle latch; they do not independently disconnect or repeat arrival.
- #124/#189 await S-01–S-05 and the [sleep runbook](device-test-sleep-schedule.md): schedules/civil boundaries,
  phone notification/lock screen, car suppression, grace ownership and actual sensor/bedside false positives.
  A timer starts only with playing audio; pause/focus loss/buffering freeze its countdown. A car connection
  suppresses presentation/new automatic timers while preserving existing/manual timers (PD-002).
- #134 already has recent-book hydration (historical Forgejo PR #57). Verify bounded latency, item/tag admission
  and unsynced progress before deciding issue closure; do not add an uncontracted endpoint or another refresh owner.

**Exit:** every applicable case has build-specific evidence; any discovered defect gets a guarded fix.
Do not close the host, sensor or account criteria with source/JVM evidence alone.

### 2. Fix the reported navigation and browse-count bugs

**Owner:** UI & Experience. **Requirements:** LIB-001/002, AUTH-002; specification 16.2/17.2/21; PD-006.

The owner selected these small functional fixes as the next implementation slices after any critical
playback/progress/privacy defect. Hardware-dependent reliability acceptance remains open alongside them.

1. [#227 gesture selection](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/227): reproduce Books → Series → Books, where the
   pill reaches Books but Series stays highlighted. The actual stable-callback route regression confirmed
   the settled-page listener captured the initial axis. PR #230 reads the latest axis/callback while keeping
   its listener stable; return, cancelled and rapid/mixed-intent checks pass. Preserve continuous motion
   and settled semantics; remaining TalkBack/restoration acceptance stays open.
2. [#228 entity counts](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/228): Books counts books, Series counts series,
   Authors counts authors and Genres counts genres within the active authorized library/search/filter
   scope. Focused book results count books. Retain uncapped Books shelf totals, localized plurals and
   truthful loading/partial-sync status; count labels remain Room-backed.

Both issues now have implementation in [draft PR #230](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/230)
on `fix/browse-selection-counts`, based on main after PR #226 merged.
The [browse delivery report](testing/2026-10-04-browse-selection-counts.md) records failing pre-fix guards,
actual caller wiring and scoped automatic/device results. These fixes are not merged or blanket-accepted;
carry pending phone/TalkBack/configuration portions forward. Signed debug 2181 was installed in place with progress/data
retained. All-axis, rapid/mixed gesture, Series Back, large-text/orientation/reduced-motion/offline and
active media-state checks pass in their recorded scope. The measured scrolling candidate is now draft PR #231; pending TalkBack, live English/profile/permission and broad appearance cases
remain explicit obligations rather than being inferred from this phone run.

**Exit:** U-06-01–06 and U-07-01–05 have source-gate and applicable physical gesture/localization/TalkBack
evidence; fix gesture selection before counts, then return to measured performance/download work.

### 3. Resolve measured scrolling cost

**Owner:** UI & Experience, with Test & Acceptance review. **Requirements:** LIB-002; specification 17.3/21;
ADR-0025/0026, R-25/R-27.

Draft PR #231 prepares one bounded sampling candidate; physical comparison remains the next performance
step while hardware-dependent reliability checks are pending. See the
[PERF-01–10 log](testing/2026-10-04-card-blur-sampling.md); no speedup or visual acceptance is claimed.
Inspect saved frame traces, isolate flat-list/card rendering cost, make one bounded change and compare on the
same device/fixture/compilation mode. Report CPU timing and actual frame overruns separately. Passing benchmark
methods do not accept the exceeded comparison budget. Keep manual cached-player latency and ANR stress pending
until measured with real offline audio/transfers.

The [generated app-profile experiment](reviews/2026-10-04-generated-baseline-profile.md) demonstrated no gain;
its file stays under `benchmark/profiles/`, outside the production consumer. Library profiles already ship.
Memory is a baseline without an acceptance threshold. Neither observation justifies an automatic profile
installation, paging rewrite or speculative cache-size change.

**Exit:** a controlled result shows the effect of the change, with the remaining budget failures disclosed
and no readability, accessibility, offline-cover or playback regression.

### 4. Accept download recovery, storage and ownership

**Owner:** Offline & Downloads. **Requirements:** DL-001/002/003/004/005/006, AUTH-002; specification 5.2;
PD-003/004. **Cases:** D-01–D-15 in the verification register and [reliability acceptance](testing/reliability-acceptance.md).

| Issues/risk | Next action |
| --- | --- |
| #108/#109/#112/#120, R-119 | Exercise Pause/Resume/Retry, waiting/backoff/restart, confirmed partial discard, independent transfers, live screen/notification progress and denied notification permission. Preserve committed media and ordinary non-destructive Retry. |
| #110, R-116 | Remove/reinsert the same and a different volume; verify unavailable storage is distinct from corruption and that any internal fallback is disclosed. |
| Closed #111, R-119 | Prove profile claims/device pin, last-claim removal, shared retention and inaccessible metadata on the device. Preserve the settled physical-copy model. |
| R-120 | Reproduce cancellation late writes and new claims during removal with controlled slow storage before adding synchronization. |
| R-121 | Separately settle the policy and scope space recovery for a copy orphaned by the last profile's removal; do not silently delete it. |
| R-122 | Run the [credential handoff matrix](reviews/2026-10-03-shared-download-ownership.md) after original-owner sign-out/removal. Only currently eligible same-server/item claims authorize each request. |
| R-123 | Run the [restart/second-cancellation cases](reviews/2026-10-04-download-restart-progress.md) for no ETag and refused range; visible percentages reflect actual replacement bytes. Never resume unvalidated bytes to preserve a monotonic percent. |

A bounded verification-cancellation correction is prepared in [draft PR #232](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/232)
on `fix/download-cancellation-commit`, stacked on browse PR #230:
real Room/filesystem guards reproduced synchronous validation renaming or clearing a part after worker
cancellation. The [download test log](testing/2026-10-04-download-verification-cancellation.md) tracks the
checkpoint/rethrow correction, passing strict/CI/signed-APK gates and scoped API36 phone evidence:
normal 21-track transfer, Pause/force-stop/relaunch/Resume, cancel/confirm partial discard, native verification
and test-copy cleanup. Controlled verifier timing, response/storage/sharing/playback/accessibility cases
remain NOT RUN; no full download acceptance is claimed.
This lane proceeds independently of the unaccepted scroll candidate; no prior phone result accepts it.
The phone audit also found a paused Book percentage mismatch (28% displayed versus 31% from durable
bytes). The R-123 follow-up now guards stale/terminal progress precedence in both actual Book/Downloads
callers and passes the strict gate/CI. Signed APK2184 physically matches Book/Downloads to durable
bytes after Pause, force-stop/relaunch and partial discard (45% → 41%); explicit Resume completes.
The [same log](testing/2026-10-04-download-verification-cancellation.md) separates this scoped acceptance
from remaining controlled replacement, concurrent attempt, storage and notification cases. R-120's WorkManager stop/delete and new-claim windows remain open; the correction
adds no cross-owner lock.

**Exit:** physical recovery, authorization, file integrity and displayed state agree. Fix reproduced gaps;
do not build a second execution adapter or persist WorkManager's execution state as another Room owner.

### 5. Deliver residual Android UI and accessibility slices

**Owner:** UI & Experience. **Requirements:** AUTH-001/002, LIB-002/003/004, PLAY-001/007/008, SET-001/002;
specification 17.2/21. Follow the [child-slice triage](testing/ui-roadmap-triage.md), not duplicate audits.

1. [#229 author details](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/229) is the first larger UI slice (PD-006):
   Authors-axis cards and Book-detail author links open the same existing author destination. Its
   Room-backed projection now includes Series and Standalone books, coauthor/all-membership handling and truthful
   completion. Series cards open series details; standalone cards open book details. Preserve genre focus,
   origin axis/query/filter/sort/scroll on Back, cached portraits, locked/profile boundaries and player clearance.
   A completed series needs all accessible members finished; filtered/author-only subsets cannot complete it.
   Use the inward green cue without checkmarks and readable/spoken completion information. #229
   now has implementation and automated evidence on `feature/author-series-standalone`, stacked on #230.
   [Author verification log](testing/2026-10-04-author-details.md) records grouping, caller, privacy and native-render
   coverage plus every pending phone case. The [2026-10-05 caller correction](testing/2026-10-05-author-observation.md)
   adds explicit pending-query state and collision-free section keys (19 author cases total). APK2189 supplies selected route/grouping evidence but fails author scroll restoration. The new state-retention correction passes its regression/full gate; an exact-source phone repeat is required. Remaining U-08 portions stay open; #229 stays open.
   No endpoint/schema change. While these candidates await physical acceptance, software work has advanced to #176's root/pushed Sign-in Back context on `fix/sign-in-navigation-back` (stacked on #234).
2. #176 now has explicit navigation capability, root/pushed toolbar Back, independent wizard Back and
   adaptive title height. [Sign-in verification](testing/2026-10-04-sign-in-back.md) logs software coverage
   and pending U-03-01–07 phone acceptance; it remains a draft and the issue stays open. The current focus is phone acceptance for the existing PRs before further software work. The next queued
   software slice is #195's non-color connection state/no-results recovery. Reproduce landscape player contrast, clipping/player
   clearance and preview/theme mismatch.
   Whole-book/chapter seek labels already exist; verify actual TalkBack rather than reimplementing labels.
3. Under #194, prove content-driven flat rows/profile clearance and real-theme preview parity before comparing
   Continue/flat-row/profile/Appearance designs. Consolidate #192/#193/#184 with that audit; do not open a
   parallel redesign queue. Retain #179 Book detail, #180 Settings and #181 profile child scopes.
4. #187 audits every book-card family, including Home/focused results; the series/shared-card slice is delivered.
   Preserve authoritative finished state, the subtle inward green cue without a checkmark, and readable/spoken
   completion information. #186's decorative appearance controls must not hide semantic completion.
5. #182/#183 and parts of #177/#178 landed in PR #200. Verify remaining motion, predictive Back, large text and
   reduced motion before further implementation. #175 shared-cover transitions remain a later cosmetic experiment.
6. Reproduce #190 on an affected device with provider/version, standalone rendering, opaque background,
   reduced motion and Haze isolation before choosing a permanent WebView mitigation. Keep #101 display formatting
   separate from primary series membership/ordering.

**Exit:** U-01–U-05 and U-08-01–07 record applicable 320/375/414/768 dp, 1.0/1.3/2.0 text, English/Norwegian, landscape,
theme/artwork/offline/error, TalkBack and reduced-motion evidence. The 2179 series subset is not this whole matrix.

### 6. Add Android system surfaces through one action contract

**Owner:** Android System & Auto. **Requirements:** PLAY-001/004, ROUTE-001/002, AUTH-002/003;
specification 3.3/5.2. **Prerequisite:** relevant playback/profile/permission correctness accepted.

Implement #114's typed shortcut/automation contract using existing remembered-book and resume-freshness owners.
Validate exported parameters, lock/profile access and stale intent; never accept arbitrary media URLs or credentials.
Then #117 widget and #118 Quick Settings project the same state/actions, followed by #116's opt-in headset
automation. Garmin #119 is parked outside this execution lane; see the owner-evaluation gate below.

**Exit:** each alternate entry reaches the same guarded behavior and passes offline, locked-profile,
process-recreation and playback-continuity checks. No second player, progress or timer owner.

### 7. Close public-release acceptance

**Owner:** Test & Acceptance, with Build & Dependencies. **Requirements:** specification 17/18/21/25.

Run this acceptance lane alongside optional system-surface work; PRODUCT_SPEC 25 owns release scope.
Complete the remaining API-26/31/34/36, server-version/permission, management, biometric/privacy,
release/R8, signed-upgrade and quality cases Q-01–Q-06 and PRODUCT_SPEC 25. A verified debug APK,
successful source gates or one phone configuration do not prove a public release. Retain the accepted
release decisions and source-file-deletion boundary; do not reopen completed feature phases.

**Exit:** applicable acceptance is evidenced, release artifacts/signing/identity/security are verified,
and unresolved risks are explicitly dispositioned before publication.

## Parked Garmin proposal — owner evaluation first

[#119](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/119) is **low priority / parked**
under PD-007. The owner will test the existing watch app and its sidecar for a while to learn the tradeoffs.
No BookWave Garmin research, prototype, development or agent-run watch acceptance starts until the owner
returns with findings and explicitly resumes the lane. Do not create a separate research child issue now.

Retain the future goal: BookWave-managed preparation/transfers to fēnix 8, downloaded playback without
the phone and later progress reconciliation through BookWave with Audiobookshelf. Replacing a separate
helper is a target with unproven feasibility, not an accepted transport/transcoder/hosting design.
Fully independent watch downloads/direct server sync are outside the initial target. Phone remote controls
remain optional; #114 governs that adapter only. This parked proposal does not block an Android release.

## Dependency and CI maintenance alongside the delivery lanes

The [latest-stable upgrade plan](latest-stable-upgrade-plan.md) owns dependency migration gates; this roadmap
owns product priority. Phases 4/5 are complete at the compatible frontier. Phase 6 (images/effects) is the
next dependency lane: re-resolve stable targets and isolate Coil/Haze changes, retaining offline artwork,
scrolling/memory and host evidence. Do not mix upgrades into the measured rendering fix or #190's diagnosis.
ADR-0011 continues to gate the build/compiler/API major migration; phases 7–9 remain staged. Benchmark #223
was a proven harness repair, not completion of Phase 7. No new upstream version check is claimed here.

#188 audits residual CI tier/coverage/timing requirements on the active `.github/workflows/` source.
Measure queue, verification and cache restore/save separately. Preserve trusted cache writes, schema guards,
secret scanning, existing thresholds and complete Standard acceptance; avoid another CI foundation project.

## Delivery and evidence rules

- Add policy/contract tests before changing behavior; inspect production callers and alternate entries.
  Prove regression tests fail without their fix. Keep Room/API/profile/privacy owners and typed errors intact.
- Run `ktlintFormat`, then `verifyDebug -Pshelfplayer.warningsAsErrors=true`; add `--rerun-tasks` after
  classpath changes. Do not report completion with a failing required gate.
- Keep implemented, automatically verified and physically accepted statuses distinct. Log every required
  case as PASS/FAIL/NOT RUN/BLOCKED with exact source/APK, configuration and evidence; never promote an
  unavailable device/host or a historical result to a new build's pass.
- When initiating final merge checks, also produce a debug APK through trusted `.github/workflows/apk.yml`.
  Link exact-head verification and packaging, verify signer/source/version/checksum/Loopbound identity, and
  record phone upgrade/smoke separately. A docs-only reconciliation does not itself require a new phone run.
- Update affected roadmap, decision/risk/compatibility prose and issue criteria with each delivery.
  Preserve dated plans/reviews as history; they do not independently order new work.


## 2026-10-05 owner-observed book-row clipping correction

Owner actual-settings texture judgement on signed2193 PASS; bottom metadata remains clipped in
both2192/2193. The [partial #192/#194 correction](testing/2026-10-05-book-row-metadata.md) lets
general rows grow with text while bounding their covers. Four native-render guards pass, fail with
actual-source reversion, and pass after restoration; strict verifyDebug passes. Fixed signed delivery
and physical regression/appearance checks remain pending. The broader compact-list/sort design is
still open. Earlier performance numbers retain their measured source/layout scope.
