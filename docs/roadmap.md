# BookWave roadmap

**Classification:** Active plan — canonical sequencing authority.
**Reconciled:** 2026-10-03 against GitHub main `81a06e1`, including merged PRs #205–#210.

This is the only document that answers what BookWave should work on next. `PRODUCT_SPEC.md` supplies
requirement IDs, `product-decisions.md` owns settled product choices, and accepted ADRs own architecture.
GitHub is authoritative following the 2026-10-01 cutover. Unqualified issue/PR numbers below refer to GitHub;
historical Forgejo numbers are explicitly labelled.

The owner selected CI wait/storage improvements as the immediate lane, followed by reliability acceptance.
Preserve playback continuity, progress, profile privacy and offline media before adding surfaces or platforms.
An open issue is not proof that implementation is missing. See the
[dated issue inventory](reviews/2026-10-02-reliability-inventory.md) for its 44-issue snapshot and evidence;
#111 closed with #209 on 2026-10-03, leaving 43 open issues at this reconciliation.
Merged code, automated verification and physical acceptance are separate statuses.

## 0. CI efficiency before feature expansion

- #212 implements automatic main cache seeding in the PR container/job; retain one debug verification per merge
  and the main workflow's release/security checks. Main push classification, schema immutability and secret
  scanning compare against the previous main SHA, preserving classpath-forced reruns.
- Automatic checks supersede older runs on the same PR/main ref. Manual runs remain independent. Keep PR
  cache cleanup and prune obsolete main home-state generations without deleting shared content blobs.
- #214 implements the debug/JVM coverage gate with the same filters/modules/80% threshold, explicit release/benchmark
  compilation on PRs and release unit tests on main. Its app unit sandbox fix isolates production collectors;
  preserve the existing overspill regression. R-125's unenforced 90% redaction rule remains a separate follow-up.
- Record queue delay, verification duration and cache restore/save time separately under #188. Quick remains
  formatting evidence; Standard retains the full regression gate and local `verifyDebug` includes assembly.
- **Deferred by the owner:** the Silo/shared Gradle cache pilot waits for separate implications research.
  Keep GitHub-hosted runners and current dependency/task caches. Remote-cache wiring, credentials, hosting
  and task-output archive exclusions are outside the active lane; reliability work can proceed independently.

These implementations are in review, not merged into this baseline. Local full gates passed; CI checks
belong to each PR's current head. After merge, verify the first trusted main seed/release run and measure
the next PR's restore/save timings before claiming a cloud performance improvement. The schema preflight
also has real-Git fixtures for stacked PRs, published-schema edits/deletions and new versions.

**Owner:** Build & Dependencies. **Requirements:** specification 16.5, 17.1/17.3 and 18.

## 1. Playback and Android Auto acceptance

- PR #205's idle car restore, remembered-book observation, resume-tile invalidation and paused
  profile-switch fallback are merged. The follow-up profile-identity guard rejects a suspended restore
  after switching unlocked profiles. PR #216 adds mutation-time A → B → A
  invalidation, transport ownership, captured-profile storage and guarded book/timer/session acceptance.
  Keep R-115's physical acceptance and the documented server/local preparation and outgoing-close limits.
- #211 keeps the book title during phone timer presentation. #213 includes that work and keeps timer metadata
  out of Android Auto, suppresses new scheduled starts while Auto is connected, preserves existing/manual
  timers, and rechecks schedule eligibility on disconnect without starting audio. Review #211 before #213;
  both remain unmerged. Use PD-002 and `device-test-sleep-schedule.md` for physical acceptance.
- Accept #128/#100 (headset continuity/route ownership), #185 (idle restore), #126/#196 (output state/Queue),
  #130 (phone/car controls), and #99/#191 (browse/profile invalidation). Principal implementations already
  exist on main. PR #205 adds follow-up tests and behavior.
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

**Review integration order:** #214 supplies the shared coverage/app-unit prerequisite for #212/#215/#216.
Review it first, then retarget those three PRs to updated main. Review #211 before #213 because #213 includes
its presentation changes. The Silo decision does not block any of these PRs; verify the combined candidate before
device acceptance. No PR merge is implied by this plan.

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
profile claims. Its device checks are R-119. PR #215 covers R-124's Book observer/Pause wiring gap with five
actual ViewModel scenarios that fail when the observer projection is removed. The residual risks are:

| Risk | Next evidence/action | Scope boundary |
| --- | --- | --- |
| R-120 asynchronous cancel / new-claim race | Reproduce late writes and a new claim arriving during removal on a slow disk. | Do not introduce a cross-WorkManager lock without evidence. |
| R-121 last-profile orphan copy | Separately scope the owner decision and space-recovery UX. | Unclaimed-copy cleanup remains outside the Phase 3 downloads work. |
| R-122 shared transfer's original profile | Reproduce A stopping, B retaining a claim, then A signing out/being removed. | Do not choose re-enqueue versus claim resolution from source inference alone. |
| R-123 resume without ETag | Exercise Pause/Resume with a no-ETag fixture and verify the truthful percent restart. | Preserve committed media; never resume bytes without a validator merely to keep percent monotonic. |

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
- **#190 WebView flicker:** run its provider/version, opaque-background and Haze-isolation matrix on an
  affected device before choosing a permanent mitigation. The upstream explanation remains a hypothesis.
- **#188 CI:** tiers landed in Forgejo PR #91, then GitHub PRs #173/#201. Audit remaining coverage/telemetry
  requirements against current workflows. Retain full Standard regression acceptance; Quick alone is not a
  merge gate. Local `verifyDebug` still includes assembly.
- **#124/#189 sleep:** schedule and countdown/grace/sensitivity landed in Forgejo PRs #63/#84/#93. The
  #211/#213 review lane is above; notification, sensor, grace and lifecycle device acceptance remains pending.
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
