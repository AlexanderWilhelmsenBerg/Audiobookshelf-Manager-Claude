# BookWave Android roadmap

**2026-10-08 watch feedback:** Phone setup and immediate Download admission still failed on hardware;
on-watch login/catalogue worked. The former -1002 was also synthesized for schema rejection, so it
did not establish a content-type cause. Setup now has typed failure guidance and a foreground watch
status view; watch errors wrap/page and Companion has explicit help. Actual download success remains
unverified. [Current findings and WD01–08 retests](testing/2026-10-08-garmin-watch-diagnostics.md).


**2026-10-08 setup recovery:** Owner-reported pairing/URL failures now have a focused fix: explicit watch Accept/Cancel, code resend/cancel/expiry and phone-driven Sidecar setup. Garmin controls/dialogs and Android sleep schedule reuse glass; sleep enable directly expands time controls. [Regression and physical acceptance register](testing/2026-10-08-garmin-setup-recovery.md). Hardware retest pending; the reported freeze remains unattributed.

**Classification:** Active plan — canonical sequencing authority.
**Updated:** 2026-10-08 for the selected provider/device controls and publishers. Android PR #240 and Garmin PRs #5/#6 remain the merged transport baseline. The original candidate `6239a148` is incorporated; Garmin PR #5 provides its counterpart. [Cross-repository audit](reviews/2026-10-07-cross-repo-reconciliation.md).

This is the only document ordering the next work. [PRODUCT_SPEC](../PRODUCT_SPEC.md) supplies requirements;
[product decisions](product-decisions.md) and accepted [ADRs](adr/) own settled behavior. GitHub is the
delivery authority. Android remains the primary delivery scope; iOS/Silo are excluded. Garmin now has a separate Companion-first implementation lane; the owner has selected a separate BookWave Audio Provider using existing WatchShelf Sidecar for the requested device controls. Provider/control/feed runtime is implemented, with physical acceptance pending; the watch face remains later.

The Android tracker has **46 open issues: 42 Android, three excluded iOS and cross-repository Garmin #119**; 23 closed tickets (22 substantive closures). Garmin has four open issues: existing acceptance/integration [#2](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/2) and [#3](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/3), selected provider/device controls [#7](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/7) and future watch face state [#8](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/8). This reconciliation closes no issues. Merged Android PR #240 and Garmin PR #5 deliver the transport implementation; Garmin PR #6 retains test binaries; physical watch acceptance remains pending. Garmin PR #1 provides the underlying stored model. [All issue dispositions](reviews/2026-10-07-cross-repo-reconciliation.md).

## Delivered and remaining acceptance

Existing main includes compact series rows with inward green completion glow, dated/progress/chapter
History with local rolling checkpoints, paused sleep-timer countdowns and Extra high/Ultra high shake
sensitivity (#220/#222); playback/profile/car ownership fixes (#205/#211/#213/#216/#217); and download
execution, recovery, claims and replacement-byte checkpointing (#207/#209/#215/#218/#221).
Car/headset, bedside sensors, power loss, account/sync and storage matrices remain in the
[verification register](testing/roadmap-verification-register.md). Historical issue36 is GitHub #128;
its source fix is merged, with its heard-route/projected-host gate still open.

## Runtime PRs merged on October 5

| PR | State | Handled and evidence | Still to verify |
| --- | --- | --- | --- |
| [#230](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/230) | Merged `fe23195b` | Gesture highlight and Books/Series/Authors/Genres counts. Four rendered gesture regressions, actual-source reversion, strict verification and PR CI pass. Signed2192 fast-tap repeats, owner animation/TalkBack and selected heard navigation pass. | Other restoration paths, live catalogue/profile/permission changes and full count state matrix. |
| [#231](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/231) | Merged `7dc17e33` | Automatic backdrop sampling for book rows. Strict verification and PR CI pass. Forty alternating benchmark executions show CPU P95 improvement of 8.94%/6.80%; owner saved-settings texture looks good. | Every measured CPU P95 remains above 16.7ms. Other blur/theme/older-API configurations and timing after adaptive row height remain unverified. |
| [#232](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/232) | Merged `5f48f581` | Download cancellation checkpoints and durable paused progress. 87 downloads/52 caller tests, three native Android parser/Room/file guards and actual-source reversion proof pass. Forced strict verification/PR CI pass. Selected signed 2184 Pause/relaunch/discard/Resume checks pass. | Actual WorkManager stop/network-boundary races, Range/ETag faults, shared claims/credential changes, storage/reboot/timeouts and notification/audio matrices. |
| [#234](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/234) | Merged `5d9d8478` | Grouped Author screen and detail-return scroll retention. 19 Author guards, strict verification and PR CI pass. Selected normal/200% completion glow, mixed grouping, offline navigation and corrected scroll-return chain pass on 2191. | Other restoration/configuration, coauthor/completion edge cases and profile/privacy transitions. |
| [#235](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/235) | Merged `33eb204b` | Pushed Sign-in Back and readable toolbar title. 31 scoped cases, strict verification and PR CI pass. Isolated successful Add/reauth, IME/predictive Back, delayed-login cancellation, process death, background/recreation and refused connection checks pass. | Other authentication/error stages, widths/themes/languages and restoration configurations. |
| [#236](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/236) | Merged `cd432f42` | Fully visible book-row duration/progress metadata. Four native geometry guards fail before/reverted source and pass fixed; six combined renders and strict verification/PR CI pass. Verified signed 2195 retains data/settings; normal Norwegian owner appearance and cached offline/detail/Back checks pass. | Physical 2195 200% text, focused/Author standalone coverage and other configurations/affected timing remain NOT RUN. Broader #192/#194 redesign remains open. |
| [#238](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/238) | Merged `36c25043` | Four non-color connection symbols and scoped no-results recovery; native/source-reversion/contrast checks and strict PR/main gates pass. | U-09-01–05 effective-background, width/player, state and heard-continuity phone cases remain NOT RUN; broader #195 stays open. |

All seven runtime changes had passing PR CI at their merge heads. The owner explicitly requested merging despite remaining
uncertainty and reporting what to watch; these gaps are carried forward rather than labelled PASS.
The measured sampling change is retained on that basis. The 16.7ms scrolling target is still unmet.
[Merge record and watch list](testing/2026-10-05-merge-delivery.md) retains exact heads/merge commits.
Historical dated reports preserve their original failures and source-specific results.

## Latest phone delivery and testing policy

Verified signed **APK2195**, combined runtime `eedcbd1e`, includes all six production changes. The owner
accepts the corrected last-line appearance: “Looks good; bottom lines readable”. In-place upgrade keeps
all recorded counts, progress/History/download fingerprints and exact settings. Normal Norwegian
detail/Back and cached offline navigation pass without requesting Play. This is not a final-main phone run.

Phone testing has stopped at the owner's request. Continue repository work without the phone. Automate
functional tests; ask the owner only for visual judgement when needed. Selected TalkBack checks are
completed per owner confirmation; no further manual TalkBack session is requested. Unperformed wider
speech cases are not converted to passes. Physical 2195 200% text remains NOT RUN, with native large-text
geometry guards passing. [Row evidence](testing/2026-10-05-book-row-metadata.md).

The six-change main gate and APK2196 remain historical evidence. The recorded October 5 source `36c25043` includes
#238 and passes main Standard CI37330126779, release/security37330126716 and checked APK37330127469.
That run publishes **APK2198**; its Actions artifact digest is recorded in the
[reconciliation report](reviews/2026-10-07-cross-repo-reconciliation.md). It has not been installed or
physically accepted. Artifact digest is not a separately verified APK checksum/signer. No phone test is
claimed for this reconciliation.

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

### 2. Verify the merged navigation and browse-count fixes

**Requirements:** LIB-001/002, AUTH-002; PD-006; U-06/U-07.
#227 gesture selection and #228 entity counts are implemented on main through #230. Corrected fast
fling/newer-tap sequences, owner animation and selected TalkBack/count checks pass. Remaining restoration,
zero/one/many plurals, live profile/library/authorization and loading/partial-state cases stay recorded in
the [merge test inventory](testing/2026-10-05-pr-merge-test-plan.md). Reproduce new failures before making
another implementation change; merged status alone does not close either issue.

### 3. Resolve measured scrolling cost

**Owner:** UI & Experience, with Test & Acceptance review. **Requirements:** LIB-002; specification 17.3/21;
ADR-0025/0026, R-25/R-27.

Merged PR #231 retains the measured automatic backdrop-sampling change after owner texture acceptance
and explicit authorization to merge with residual uncertainty. Other configurations and fresh timing
after #236 remain follow-up work while hardware-dependent reliability checks are pending. See the
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

A bounded verification-cancellation correction is merged in [PR #232](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/232)
on main after browse PR #230:
real Room/filesystem guards reproduced synchronous validation renaming or clearing a part after worker
cancellation. The [download test log](testing/2026-10-04-download-verification-cancellation.md) tracks the
checkpoint/rethrow correction, passing strict/CI/signed-APK gates and scoped API36 phone evidence:
normal 21-track transfer, Pause/force-stop/relaunch/Resume, cancel/confirm partial discard, native verification
and test-copy cleanup. Controlled verifier timing, response/storage/sharing/playback/accessibility cases
remain NOT RUN; no full download acceptance is claimed.
This lane proceeds independently of scrolling work; prior phone results retain their original scope.
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

1. #229 Author details is implemented on main through #234: common author destination, Series and
   Standalone groups, coauthor/all-membership projection, truthful completion glow and retained scroll.
   Selected normal/200% layouts, mixed groups, offline routes and corrected return chain pass. Keep
   remaining restoration/completion/profile/privacy cases in the [merge inventory](testing/2026-10-05-pr-merge-test-plan.md).
2. #176 pushed Sign-in Back/title is implemented on main through #235. Selected Add/reauth, IME,
   predictive Back, delayed-login cancellation, process-death/recreation and error checks pass on the
   isolated recorded runtime. Remaining authentication/configuration cases stay open.
   **Merged #195 child slice:** PR #238 delivers distinct connection symbols and scoped Clear search/
   Reset filters/Clear selection. Native/source-reversion and contrast tests, strict verification and
   PR/main CI pass; [U-09-01–05](testing/2026-10-05-home-status-recovery.md) remain pending on hardware.
   Next independent Android software slice is **#194 profile/player clearance and effective-theme
   preview parity**. It is planned, not implemented; reproduce existing states before selecting a fix.
3. #236 fixes general-row bottom metadata clipping under #192/#194. Owner normal-font appearance
   passes on 2195; large-text native guards pass. Continue profile/player clearance and real-theme preview
   parity checks before comparing
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
automation. Garmin transport is tracked separately below; future watch playback commands reuse this action contract.

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

## Garmin Companion lane — Phase 2 implemented, hardware acceptance pending

The owner authorized finishing the Android bridge and matching receiver. The original nine-commit
candidate is incorporated with captured playback ownership, synchronous privacy/generation guards,
reconnect clearing, strict field validation, correlated persistence acks and bounded paused-state retries.
Merged Android [PR #240](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/240) supplies the bridge. Garmin [PR #5](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/5) implements foreground
Communications transport, nonce/sequence rejection, durable clear tombstones, snapshot persistence and
truthful received/stored UI. Both repos carry [the wire contract](garmin-transport-contract.md).

Android regression/source-reversion evidence and final gates are recorded in the
[bridge delivery log](testing/2026-10-07-garmin-bridge.md). Garmin target builds, test-enabled compilation
and export pass; [PR #6](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/6) publishes source-labelled test artifacts. Current Run No Evil execution is recorded in [the provider delivery log](testing/2026-10-08-garmin-provider.md); physical G-01/G-02 tests remain NOT RUN under
[Garmin #2](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/2) and
[#3](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/3); no phone/watch acceptance is inferred.

Next Garmin obligation is pairing/interop, persisted-state restore, privacy while connected/offline,
ack loss, reconnect/lifecycle and BLE/battery acceptance. Receiver operation is foreground only;
disconnected watches cannot receive a privacy clear until reconnect. #119 stays open as the umbrella.
After transport acceptance: allowlisted commands through #114; watch face and Running Data Field. Provider events, Android Settings Force sync and complications are implemented without automatic Play.
WatchShelf remains usable during transition. The owner-selected BookWave Audio Provider uses
existing WatchShelf Sidecar; removing/replacing Sidecar is not selected.
No Garmin priority over Android reliability is inferred. The next independent Android slice remains
#194 profile/player clearance and effective-theme preview parity.

### Garmin device management — implemented, physical acceptance pending

The owner requested Playback → Devices with an inline watch menu, connected dot/last connected,
last successful sync, Force sync, a watch-download dialog and a New download picker of completed
Android books. Watch sessions must represent actual watch listening. The current snapshot bridge
does not provide these features. [Requirements, external API boundary and GD-01–10 test inventory](garmin-device-management.md).
The owner selected a separate BookWave Audio Provider using existing WatchShelf Sidecar, tracked by
[Garmin #7](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/7). Unmodified WatchShelf has no remote download/inventory interface.
The approved sequence is provider/account/wire fixtures → Android/provider download/inventory vertical
slice → real listening events/Force sync → validated complication publishing. [Garmin #8](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/8)
and the [feed plan](garmin-watchface-state-plan.md) expose PHONE/GARMIN data for a future watch face
through BookWave's on-watch Complications, with source/freshness/privacy, rather than direct ABS polling.
Watch face and Data Field implementation remain later. The provider, device controls and publisher are implemented; [delivery/tests](testing/2026-10-08-garmin-provider.md) and [installation guide](garmin-install-for-testing.md) record the exact delivery and unperformed hardware cases.

## Loopbound APK packaging recovery — 2026-10-07

Checked APK run [37623427096](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37623427096)
failed private GitHub checkout with the old stored credential. The repository URL and pinned source
`7e24529b2a9e419218d2a1423d832b1bf587c8ef` were valid. The owner replaced LOOPBOUND_READ_TOKEN with
a repo-scoped GitHub fine-grained Contents:Read token. Attempt2 passes Loopbound checkout/tests/build,
strict debug verification and signed packaging, producing APK2199 with Loopbound. This is authentication
recovery, not evidence of a missing source pin or an Android compilation failure. Checked bridge APKs retain the same pin; source/gates and the current artifact record are linked from the bridge log; no phone installation is requested.

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


## Book-row clipping follow-up

PR #236 is merged. Verified signed APK2195 retains data/settings and passes normal Norwegian
detail/Back and cached offline checks. Owner finding: “Looks good; bottom lines readable”. Native
large-text geometry guards pass; physical 2195 200%/focused/Author and other timing/configuration cases
remain NOT RUN. Broader #192/#194 compact-list/sort work remains open.
[Exact row evidence](testing/2026-10-05-book-row-metadata.md).

## Verified implementation delivery — 2026-10-08

Garmin provider/publishers are merged in [PR10](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/10), commit `19cc9a67db505fb9389a8861373d97b3c037072b`. All nine PR CI checks pass at `e6829ade`; main CI37699273330 also passes. Matching Android device controls pass full strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` locally: 1024 tasks, BUILD SUCCESSFUL in2m50s. All37 Garmin tests and38 Room migration tests pass, with actual-source A-B-A reversion proof. The bridge uses Room22 and adds no external dependency/classpath change. Its implementation branch is `codex/garmin-provider-controls`; GitHub PR delivery remains authoritative.

[Garmin testing artifacts](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37699273330) contain both supported target PRGs and packages. The [installation guide](garmin-install-for-testing.md) and GD/GF register cover testing; all physical cases remain NOT RUN. Final simulator reruns stalled, so the earlier49 native passes do not establish final-source/native or hardware acceptance.

Android device controls are merged in [PR243](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/243), `fe16456b`, after CI37699681083 passed. The request-recovery follow-up prevents an accepted request from blocking a user retry when fresh watch inventory reports no matching cache/job. Native claimed sync IDs survive interruption until actual completion/failure. GD04/06/10 remain physical acceptance obligations.

The recovery and rendered-test classification fixes are merged in [PR244](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/244) after CI37704059996 passed. Final strict local verification passes (1024 tasks); all38 Garmin debug and302 app release tests pass. Garmin recovery PR12 and its main CI also pass. Device/feed acceptance remains pending; the installation guide covers the existing AMOLED43 and47/51 mm target groups.
