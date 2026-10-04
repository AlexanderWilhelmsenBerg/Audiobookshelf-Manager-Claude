# BookWave roadmap

**Classification:** Active plan — canonical sequencing authority.
**Reconciled:** 2026-10-04 against GitHub main `842b0971`, including merged PRs #205–#222
and the supplied-phone acceptance continuations.

This is the only document that answers what BookWave should work on next. `PRODUCT_SPEC.md` supplies
requirement IDs, `product-decisions.md` owns settled product choices, and accepted ADRs own architecture.
GitHub is authoritative following the 2026-10-01 cutover. Unqualified issue/PR numbers below refer to GitHub;
historical Forgejo numbers are explicitly labelled.

The selected CI wait/storage improvements are implemented; reliability acceptance is the next lane.
Preserve playback continuity, progress, profile privacy and offline media before adding surfaces or platforms.
An open issue is not proof that implementation is missing. See the
[dated issue inventory](reviews/2026-10-02-reliability-inventory.md) for its 44-issue snapshot and evidence;
#111 closed with #209 on 2026-10-03, leaving 43 open issues at this reconciliation.
Merged code, automated verification and physical acceptance are separate statuses.
The owner supplied an API-36 phone on 2026-10-03. The [first pass](testing/2026-10-03-phone-acceptance.md)
records 27 passing instrumented tests and local playback/restart/manual timer subcases. Five benchmark
cases failed in the harness; actual car/headset, transfer/account fixtures and two-hour soak remain pending.
Use the [verification register](testing/roadmap-verification-register.md)
to log every required case and its evidence. Include a verified debug APK with the final CI handoff for each
delivery batch; keep iOS development on hold alongside Silo, as requested on 2026-10-04.

## 0. CI efficiency before feature expansion

### Owner-requested prerequisite: series, history and sleep fixes

Before returning to the reliability queue, deliver the owner's 2026-10-03 series readability/finished-state
fix, offline rolling History checkpoints with chapter/date/progress detail, and paused timer / playing-only
creation fixes with Extra high / Ultra high motion sensitivity (PD-002 amendment, PD-005). Track each required
test in [the fix log](testing/2026-10-03-series-history-sleep.md); the [Hallmark findings](reviews/2026-10-03-series-screen-hallmark.md)
cover the series-only slice of #194. Keep the broader UI issues open. Resume reliability afterwards;
the Silo research deferral and low iOS priority remain unchanged. PR #220 delivered these fixes and the
owner's inward green completion cue; PR #221 corrected R-123's stale byte count after a fresh download
restart and a second cancellation. The [2178 phone continuation](testing/2026-10-04-phone-2178.md)
records completion-state/large-text/last-row checks, paused timer behavior and abrupt offline process
recovery with less than four seconds of estimated loss. Missing hardware/server/bedside tests stay open.
The owner's subsequent compact-card revision is tracked in
[its review and verification log](reviews/2026-10-04-compact-series-cards.md). Normal rows must be compact
without restoring fixed-height truncation; large text remains content-driven.

- #212 implements automatic main cache seeding in the PR container/job; retain one debug verification per merge
  and the main workflow's release/security checks. Main push classification, schema immutability and secret
  scanning compare against the previous main SHA, preserving classpath-forced reruns.
- Automatic checks supersede older runs on the same PR/main ref. Manual runs remain independent. Keep PR
  cache cleanup and prune obsolete main home-state generations without deleting shared content blobs.
- #214 implements the debug/JVM coverage gate with the same filters/modules/80% threshold, explicit release/benchmark
  compilation on PRs and release unit tests on main. Its app unit sandbox fix isolates production collectors;
  preserve the existing overspill regression. The [R-125 security follow-up](reviews/2026-10-04-security-coverage.md)
  hooks the existing 90% redaction rule into ordinary verification; its unchanged report reaches 96.72%.
- Record queue delay, verification duration and cache restore/save time separately under #188. Quick remains
  formatting evidence; Standard retains the full regression gate and local `verifyDebug` includes assembly.
  [Three dated timing samples](reviews/2026-10-03-ci-timing-baseline.md) establish the measurement fields,
  not a controlled performance claim.
- **Deferred by the owner:** the Silo/shared Gradle cache pilot waits for separate implications research.
  Keep GitHub-hosted runners and current dependency/task caches. Remote-cache wiring, credentials, hosting
  and task-output archive exclusions are outside the active lane; reliability work can proceed independently.

The CI implementations are merged. Local full gates passed; CI checks belong to each PR's current head.
After each merge, verify the trusted main seed/release run and measure
the next PR's restore/save timings before claiming a cloud performance improvement. The schema preflight
also has real-Git fixtures for stacked PRs, published-schema edits/deletions and new versions.

**Owner:** Build & Dependencies. **Requirements:** specification 16.5, 17.1/17.3 and 18.

## 1. Playback and Android Auto acceptance

- PR #205's idle car restore, remembered-book observation, resume-tile invalidation and paused
  profile-switch fallback are merged. The follow-up profile-identity guard rejects a suspended restore
  after switching unlocked profiles. PR #216 adds mutation-time A → B → A
  invalidation, transport ownership, captured-profile storage and guarded book/timer/session acceptance.
  Keep R-115's physical acceptance and the documented server/local preparation and outgoing-close limits.
- Merged #211 keeps the book title during phone timer presentation. The integrated #213 keeps timer metadata
  out of Android Auto, suppresses new scheduled starts while Auto is connected, preserves existing/manual
  timers, and rechecks schedule eligibility on disconnect without starting audio.
  Use PD-002 and `device-test-sleep-schedule.md` for physical acceptance.
- Accept #128/#100 (headset continuity/route ownership), #185 (idle restore), #126/#196 (output state/Queue),
  #130 (phone/car controls), and #99/#191 (browse/profile invalidation). Principal implementations already
  exist on main. PR #205 adds follow-up tests and behavior.
- #128 is historical Forgejo #36. Its focused review found that Projection → Unknown → NotConnected could
  lose the departure edge, while Unknown → Projection could repeat arrival. Preserve the existing positive
  lifecycle latch across unreadable provider results. The five real monitor-to-service cases and
  [detailed review/device matrix](reviews/2026-10-03-issue-128-continuity-review.md) supplement the continuity
  suite; physical entry/departure and audible routing remain pending.
- Use [the combined drive checklist](android-auto-pd001-drive-acceptance.md) and
  [browse invalidation checks](android-auto-browse-invalidation-acceptance.md). Include headset Previous,
  Rewind and Fast-forward from merged PR #204. Record the APK commit and device/host versions.
- Preserve PD-001's **Continue → Series → Authors → Profiles** root. History is absent from car browse;
  #196 requires the standard Queue affordance to be absent, not replaced by History.
- Reproduce failures on the candidate build before changing routing. Explicit selection wins over inferred
  routes; a merely connected headset is not heard-route evidence; a deliberately paused book stays paused.

**Owners:** Android System & Auto and Playback & Lifecycle, with Test & Acceptance review.
**Requirements:** PLAY-001/002/004/007, ROUTE-001/002, AUTH-002, LIB-002/003, specification 5.2 and 6.5.
**Gate:** physical headset/car evidence remains required; JVM tests cannot close this gate.

**Integration evidence:** #214 supplied the shared coverage/app-unit prerequisite for #212/#215/#216;
#211 supplied #213's phone presentation change. The combined candidate `c6236833` passed the forced
`ktlintFormat verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks --max-workers=4` gate in
7m 21s, with all 1,119 tasks executed. #217's projection-lifecycle correction then passed the forced full
gate in 6m 59s and merged; main `b7266a3d` passed trusted debug seed, release/security and signed APK runs.
Merged #218's R-122 correction adds its own [regression and full-gate evidence](reviews/2026-10-03-shared-download-ownership.md).
Main `8beec05c` passed trusted debug/cache, release/security and signed APK workflows. The installed
2175 APK matches those bytes; the [phone report](testing/2026-10-03-phone-acceptance.md) records its limited
physical acceptance. Keep #128 open: this run did not exercise a heard headset or projected Auto.
Current-head PR and post-merge main checks remain authoritative.
Silo remains deferred, and physical acceptance remains open.

## 2. Verify the merged download reliability lane

Forgejo PR #94 (`8ea2122f`) already merged the former implementation queue. The table below is the
merged implementation **awaiting physical/device acceptance**. Assess remaining open-issue closure against
that evidence; #111's closed ownership work retains its physical follow-up under R-119.
Forgejo issue numbers are the historical tracker's and are matched to GitHub by title and order; only #19 is
corroborated by code comments (`BW-DL-04 / #19`), so treat the others as inferred.

| GitHub issue | Forgejo issue | Implemented behavior | Remaining acceptance |
| --- | --- | --- | --- |
| #108 | #18 | State-owned Pause / Resume / Retry, originally Forgejo PR #55 | Failure/restart actions preserve partial bytes. |
| #109 | #19 | Aggregate WorkManager waiting/retry observation | Constrained/retrying work recovers across process restart. |
| #110 | #20 | Volume identity, conservative verifier and storage projection | Card removal/reinsertion and disclosed internal fallback without redownload (R-116). |
| #111 | #21 | Shared copy, profile claims and device pin. PD-003 settles ownership. | Last-claim removal, shared-copy retention and metadata redaction. |
| #112 | #22 | Confirmed partial-data discard, separate from Retry | Confirmation preserves committed media; ordinary Retry is non-destructive. |
| #120 | #29 | Active queue, live progress and notification navigation | Independent transfers, state transitions and denied notification permission. |

#111 is **decided and implemented**: PD-004 (the owner's decision on tap behaviour, profile-scoped removal and
the in-flight Pause / Stop prompt) landed through GitHub PRs #207 (claim-aware removal, floored percent,
domain recovery actions, `.part` bytes recorded on cancel) and #209 (Book button percent and ring, Pause / Stop /
Keep prompt, Paused and Resume, claim-aware Downloads removal). Pause is offered only for a copy no other
profile claims. Its device checks are R-119. Merged PR #215 covers R-124's Book observer/Pause wiring gap with five
actual ViewModel scenarios that fail when the observer projection is removed. The residual risks are:

| Risk | Next evidence/action | Scope boundary |
| --- | --- | --- |
| R-120 asynchronous cancel / new-claim race | Reproduce late writes and a new claim arriving during removal on a slow disk. | Do not introduce a cross-WorkManager lock without evidence. |
| R-121 last-profile orphan copy | Separately scope the owner decision and space-recovery UX. | Unclaimed-copy cleanup remains outside the Phase 3 downloads work. |
| R-122 shared transfer's original profile | Four downloader regressions reproduced the gap. Current eligible claimants now authorize each file/cover; run the [device matrix](reviews/2026-10-03-shared-download-ownership.md). | Keep the same WorkManager request/network constraints; no active-profile fallback or blind account retry. Physical acceptance remains pending. |
| R-123 resume without ETag | Second-cancellation stale bytes reproduced for no-ETag and declined-range restarts; correction saves actual replacement length only with the owner available. See [review and missing tests](reviews/2026-10-04-download-restart-progress.md); screen/notification/restart/card acceptance stays pending. | Preserve committed media and absent-volume progress; never resume bytes without a validator merely to keep percent monotonic. |

Fix reproducible gaps in these paths. Do not build another execution adapter, persist WorkManager state into
Room or reopen settled physical-copy ownership. Verify server-and-item identity at the storage/active-profile
join: identical item IDs across servers are not authorization.

Use [reliability acceptance](testing/reliability-acceptance.md). Keep physical checks pending when hardware
is unavailable; do not close issues solely because code merged. This lane can proceed independently while
car acceptance awaits hardware.

**Owner:** Offline & Downloads. **Requirements:** DL-001/002/003/004/006, AUTH-002, specification 5.2, PD-003.

## 3. Remaining Android work

- **Accessibility/UI:** reconcile #194/#195 findings into concrete child slices. PR #200 already changed
  mini/full players and motion (#182/#183 and part of #177/#178). Assess residual criteria rather than
  reapplying earlier designs. Prioritize contrast, clipping, player clearance and recovery before polish;
  require narrow/wide, 2.0 font scale, TalkBack and reduced-motion evidence. The
  [concrete child slices](testing/ui-roadmap-triage.md) distinguish current code from missing work, include
  #176's root/pushed Sign-in Back context, and sequence #194's geometry/clearance/preview fixes before polish.
- **Acceptance follow-ups:** the supplied phone's landscape player text over bright artwork needs a
  controlled contrast check; an inherited warm Starting state needs a defined reproduction. The benchmark
  harness fails process discovery on this phone's truncated `pgrep` names; repair/verify that compatibility
  in the Build & Dependencies lane before claiming performance metrics or shipping a baseline profile.
  The [focused Benchmark 1.5.0 repair](reviews/2026-10-04-benchmark-api36.md) advances that harness lane;
  actual startup/scroll/memory/profile reruns remain pending while no phone is reported by ADB.
- **#190 WebView flicker:** run its provider/version, opaque-background and Haze-isolation matrix on an
  affected device before choosing a permanent mitigation. The upstream explanation remains a hypothesis.
- **#188 CI:** tiers landed in Forgejo PR #91, then GitHub PRs #173/#201. Audit remaining coverage/telemetry
  requirements against current workflows. Retain full Standard regression acceptance; Quick alone is not a
  merge gate. Local `verifyDebug` still includes assembly.
- **#124/#189 sleep:** schedule and countdown/grace/sensitivity landed in Forgejo PRs #63/#84/#93. The
  #211/#213 implementation lane is above; notification, sensor, grace and lifecycle device acceptance remains pending.
- **#101:** keep display-only series formatting cleanup separate; preserve primary selection and ordering.

## 4. System surfaces, then iOS

After Android correctness acceptance, implement #114's typed action contract using existing remembered-book
and resume-freshness owners. Validate exported parameters, profile access and lock behavior; no arbitrary
media URLs, credentials or unrestricted item execution.

Then implement #117 (widget) and #118 (Quick Settings) as projections, followed by #116's opt-in headset
automation. #119 first evaluates Garmin's Control Phone path; custom work needs a demonstrated gap.

Keep #121–#123 last: selective portable model/domain seams, native iOS shell/authentication, cached library,
native Apple playback, progress correctness, then offline transfers. Live Activity and CarPlay follow proven
native playback. No wholesale KMP conversion or shared UI is implied.

Dependency work follows `latest-stable-upgrade-plan.md` separately. Phases 4/5 are at their documented
compatible frontier; Phase 6 is next in that lane, and ADR-0011 still gates the build-platform upgrade.

## Verification and status discipline

- Work one requirement group at a time; add policy/contract tests first and inspect production callers.
- Prove regression tests fail without their fix; run formatter and
  `verifyDebug -Pshelfplayer.warningsAsErrors=true`, with `--rerun-tasks` for classpath changes.
- Record automated, source-review and device evidence separately, including failed/unavailable checks.
- Update roadmap, relevant risks, compatibility docs and issue status together. Preserve historical provenance.
- PR #93 owns resume freshness; PR #149 owns remembered identity; Forgejo PR #57 owns recent-book hydration
  (#134). These are not new implementation tasks.
