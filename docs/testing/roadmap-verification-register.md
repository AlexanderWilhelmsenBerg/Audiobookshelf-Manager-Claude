# Roadmap verification register

**Classification:** Test inventory and execution log; sequencing remains in [the roadmap](../roadmap.md).
**Created:** 2026-10-03. The owner supplied an API-36 phone the same day. The
[first phone execution](2026-10-03-phone-acceptance.md) records 27 passing instrumented cases, selected
phone subcases, five failed benchmark cases and remaining NOT RUN steps. The
[2179 continuation](2026-10-04-phone-2179.md) supersedes the benchmark harness failure for its measured
configuration: eight executions and 27 datastore cases pass, but scrolling remains over budget.
**Reconciled:** 2026-10-04 through merged main `9ced43ae` (PR #233), plus source-scoped draft evidence. Parent rows are inventories, not blanket passes;
use each dated report for its exact tested APK/scope. Signed main APK2180 was superseded without installation. Browse draft APK2181 and downloads draft
APK2184 have their own bounded phone results; neither establishes final-main acceptance. Signed main APK2186 remains uninstalled; author/Sign-in candidates have separate source-scoped evidence.

This register covers the CI/reliability delivery, the historical issue #36 solution, and the remaining
functional/release checks in PRODUCT_SPEC sections 17, 21 and 25. Merged code and passing JVM tests do not
transfer physical acceptance from an older APK. GitHub #128 is historical Forgejo #36; GitHub PR #36 is an
unrelated privileged-write contract change.

## Current candidate obligations

The [2026-10-05 merge test plan](2026-10-05-pr-merge-test-plan.md) is the current supplement for
PRs #230/#231/#232/#234/#235. It records selected later results without promoting earlier builds
to final-main acceptance. Main through #233 contains documentation reconciliation; runtime PRs
remain drafts. Existing dated rows below retain their original evidence scope.

- #230: 100ms fling → newer Books tap FAIL on APK2189/2191. `cf8db633` fixes the request ordering;
  four rendered cases, actual production-source reversion and strict verifyDebug pass. New signed
  phone acceptance is pending. Selected English/Norwegian counts/search and cached offline checks pass.
- #231: 40 alternating benchmark executions are complete with measured improvement; every CPU P95
  still exceeds 16.7ms. Visual quality, playback, accessibility, old APIs and startup/memory remain open.
- #232: ordinary APK2184 Pause/relaunch/discard/Resume and size verification retain their narrow PASS.
  Controlled verifier-phase/response/race/storage/credential/notification/accessibility cases remain open.
- #234: 19 author guards; selected mixed/standalone/completed/offline routes pass. APK2189 scroll
  return FAIL is corrected in `0c8fb994` and repeated successfully on signed APK2191. Remaining
  U-08 privacy, restoration, configuration, actual TalkBack and heard playback are NOT RUN.
- #235: selected isolated synthetic Sign-in Back/large-text/delayed-login cancellation/process-death
  checks pass on `2d567b17`; remaining successful Add/reauth, recreation, predictive/IME, configuration
  and actual TalkBack cases are NOT RUN. Current corrected-parent strict/CI source is `5dac7e17`.
- API36 datastore/Keystore: 27/27 PASS on combined `2d567b17`. Signed upgrades preserve progress,
  History and downloads; active profile lastUsedAt changes. No active-playback upgrade acceptance.

Human checks pause the phone automation. Each answer is recorded with case, source/APK and findings;
unanswered/preselected options are not results. The first completed Author-card check on APK2191
is awaiting the owner's observation. The corrected combined source `4f2edb36` is building separately.

## Record a result for every case

The owner's new series/history/sleep prerequisite has its own complete
[case log](2026-10-03-series-history-sleep.md). The [2178 continuation](2026-10-04-phone-2178.md)
records the exact revised completion/large-text/last-row, paused timer and offline rolling History/process
recovery subcases that passed; other physical cases remain NOT RUN. Do not transfer those results to a
later APK. The [compact-card revision](../reviews/2026-10-04-compact-series-cards.md) has fresh native/JVM
evidence and its own missing phone matrix. The [Benchmark 1.5.0 repair](../reviews/2026-10-04-benchmark-api36.md)
has source/strict forced-gate evidence and a completed physical rerun on 2179. Historical failures retain
their dated result; see that continuation for library-only control, app-profile experiment and budget limits.
Under PD-002, C-07's focus-paused timer must remain frozen rather than expire during the pause.
Test late continuity recovery against that frozen timer and newer intent; test actual expiry only after
audio resumes and consumes the saved remainder.

Use the case IDs below, including the individual steps of linked runbooks. Record the exact APK commit,
version/code, variant, signer, CI run and artifact checksum; Android/API, device, host/DHU, headset and
WebView provider versions; timezone and relevant settings; expected and observed behavior; result
(`PASS`, `FAIL`, `NOT RUN` or `BLOCKED`), UTC start/end, and evidence location. State why a case is blocked.
Use fixture accounts/media and redact private metadata and credentials from shared evidence.

```text
Case / runbook step:
APK commit, version/code, variant, signer, SHA-256:
CI run / artifact:
Android/API, device, host/headset/provider, timezone/settings:
Expected:
Observed:
Result and reason:
UTC start/end:
Evidence / linked defect:
```

A phone run starts with `./scripts/check-local-environment.sh` and an authorized device in `adb devices`.
Record unavailable tools/hosts instead of treating them as passing. Use API 26/31/34/36, portrait/landscape,
phone and tablet/foldable width, offline/metered transitions, low storage and process death. One supplied
phone covers its own configuration; leave the other API/host rows pending until exercised.

## Automated baseline and delivery checks

| ID | Required check | Current evidence / remaining action |
| --- | --- | --- |
| A-01 | `ktlintFormat`, then `verifyDebug -Pshelfplayer.warningsAsErrors=true`; force `--rerun-tasks` after classpath changes. | Combined #211–#216 candidate `c6236833` passed all 1,119 tasks in 7m 21s. The #128 follow-up source at `297965b3` passed the forced full gate in 6m 59s: all 1,119 tasks executed, app 521 and playback 516 tests, zero failures/errors. Subsequent documentation does not change that source; record current-head CI and final-main results too. |
| A-02 | Release lint, `testReleaseUnitTest`, release assembly, SBOM and vulnerability checks. | Main `d3596b2f` completed all tiers; combined `d81de778` release tests reran all 394 tasks in 1m 56s, 1,376 tests with zero failures/errors. Record the final main run separately. |
| A-03 | CI policy fixtures, Actionlint and Bash syntax: main/PR/manual cancellation, previous-main classification, scoped cache pruning and immutable Room schemas. | All 16 policy tests passed, including real-Git stacked-PR/schema fixtures. Observe a trusted main seed and the next PR restore/save timings; first main seed `18c2e618` passed. |
| A-04 | Prove each regression fails without its fix; inspect actual production callers. | R-115 generation/acceptance/lock/outbox, scheduled-car suppression, phone title and R-124 Book observer/Pause regressions have recorded red/green evidence. #128's actual monitor-to-service regression failed 3/5 on main `3e699786`, then passed 5/5 with the lifecycle latch. All 68 focused cases, formatter, playback ktlint and detekt passed; its combined full gate is recorded with the delivery. |
| A-05 | Audiobookshelf contract fixtures, missing required fields/unknown fields, compatibility failures and migration tests. | Included in the full gate. Live selected-server-version acceptance remains pending; no new endpoint or schema was introduced by #211–#216. |
| A-06 | `:core:datastore:connectedDebugAndroidTest`. | **PASS 27/27 on API 36, repeated 2026-10-04 on the [2179 continuation](2026-10-04-phone-2179.md)**, using an isolated test application ID after the ordinary package hit a signer conflict. All names, scope and failed-install evidence are in the [phone report](2026-10-03-phone-acceptance.md). This tier does not test the whole app lifecycle. |
| A-07 | Domain/core and security-policy coverage. | PR #224 wires both the unchanged 80% aggregate and 90% redaction rule into ordinary verification. Fresh redaction coverage is 96.72%; four EventLog cases and the hook-removal regression passed. See [security evidence](../reviews/2026-10-04-security-coverage.md). |
| A-08 | Exact final-main APK: trusted `apk.yml`, stable signing, built version/About identity and downloadable artifact. | Main `8beec05c` passed trusted debug/cache, release/security and signed APK workflows. Installed 0.10.6.1 (2175) bytes match the artifact and About identifies that source; see [identity/CI record](2026-10-03-phone-acceptance.md). The [2178 continuation](2026-10-04-phone-2178.md) records an in-place 2177→2178 upgrade with data retained. Final main `a20bb5b9` produced verified debug 2180 (APK SHA-256 `e8fa3cab6707cd3c72470d779889ee037402bf1930efad4e1e2a177e3d156e99`); installation, About and smoke were NOT RUN before it was superseded. Later draft 2181/2184 results are recorded below. The [browse delivery](2026-10-04-browse-selection-counts.md) records trusted signed 2181/source `00ee58c6`, 2179→2181 upgrade with unchanged pre-launch data/progress, and scoped phone smoke; About UI remains NOT RUN. Repeat handoff identity and upgrade checks for each later delivery. |

2026-10-04 follow-up: compact series guards failed the old layout twice, then all 26 card/screen cases
passed with native captures. Its full gate passed, followed by PR #222 and main CI. The isolated Benchmark
pin change then passed formatter, strict harness/target assembly and `verifyDebug --rerun-tasks` with
warnings as errors: 1,268 tasks executed in 8m56s, app 547 tests with zero failures/errors. The [2179 continuation](2026-10-04-phone-2179.md) records the completed phone/benchmark rerun.
PR #224 then passed its full gate (2,182 debug/JVM cases); #225 passed the final source gate.
Main `a20bb5b9` passed [release/security](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37199316812),
[debug cache seed](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37199316530)
and [signed APK](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37199316879).
A-08/Q-01 apply to each new final-main APK; superseded 2180 needs no redundant install, and draft 2184 does not accept main; earlier phone results retain their original scope.

For branch-only/main builds, use **Build APK** directly: the PR verification workflow's optional APK
handoff requires an open PR number. Set `variant=debug` and `run_checks=true` when initiating final checks
and packaging together. If the exact immutable SHA already has passing Standard/main verification, an
assemble-only packaging run may reuse that evidence, but link both runs. Keep signing in the existing
trusted workflow. Retain the APK and checksum for the later phone run; confirm upgrade compatibility on
the phone before installing over existing data. Keep the ordinary Loopbound bundle unless explicitly
testing a build without it, and record its source SHA.

## Playback, sessions and profiles

The [phone report](2026-10-03-phone-acceptance.md) passes local offline/restart, configured system skips,
mini/full/notification entry and rotation subcases. Remote, route, profile-race and two-hour acceptance
remain NOT RUN; complete each parent matrix before closing it.

| ID | Scenario | Pass condition / requirement |
| --- | --- | --- |
| P-01 | Stream and play a complete local copy; disconnect/reconnect network and restart offline. | Playback chooses a valid source, retains position and reports unavailable media truthfully. PLAY-001/004/005/006. |
| P-02 | Foreground/background, screen off, rotation, Activity recreation and notification entry. | One live session continues; mini/full player reattaches without issuing Play or replacing the queue. Run every step of [session reattachment](../device-test-issue-75.md). |
| P-03 | Two-hour continuous playback, with foreground/background and route changes. | No crash/ANR, silent interruption or growing resource problem; record checkpoints and interruptions. R-09, section 17.3. |
| P-04 | Controlled process termination at a known nonzero position, then reopen/resume; repeat local/remote. | No more than ten seconds of progress loss; ordinary forced-stop/restoration does not manufacture autoplay. Record timestamps and server/local state. PLAY-004. |
| P-05 | Offline listening, reconnect, retry sync, and compare server history/progress. | Captured-profile sessions are uploaded once with correct listened time/position; no cross-profile attribution or duplicate history. PLAY-005. |
| P-06 | Multi-file chapters, seek across boundaries, end of book, speed and configured skips. | Global timeline and resumed position remain correct; Previous/Rewind/Fast-forward use configured intervals, clamped at zero. PLAY-003/007, GitHub #197 / PR #204. |
| P-07 | Audio-focus loss/return, calls, noisy/wired unplug, Bluetooth reconnect and explicit output selection. | Respect focus and pause intent; explicit route wins; merely connected devices do not prove the heard route. PLAY-002, ROUTE-001/002. |
| P-08 | Switch A → B, A → B → A, lock/remove profile during holder/candidate/queue/final identity/local session opening. | Superseded restore never publishes old book, title, timer, position or live session. Returning to A does not reauthorize it. AUTH-002, R-115. |
| P-09 | New Play/Pause/Stop while an idle restore is suspended. | Latest transport wins; a deliberately paused book stays paused; no stale installation/restart. R-115. |
| P-10 | Reject durable session opening; then accept another book while a timer runs. | Rejection preserves old live session/baseline/timer; acceptance resets the old timer before installation. A prepared server/local row stays captured-owner scoped; outgoing close already sent is not reversible. R-115. |
| P-11 | Cold car restore with an existing item, empty/locked/missing candidate, Never, Arm and ArmAndPlay. | Existing playback survives; Never is metadata-only, Arm stays paused, ArmAndPlay obeys route/lock policy; no unauthorized library exposure. #185. |
| P-12 | Car-only switch, same-active-profile selection and stale browse subscriptions. | Same profile is a no-op; outgoing progress flushes; unlocked switch stays paused; locked profile is not exposed; old titles/artwork/children are evicted. #99/#191. |

## Historical #36 / GitHub #128 — headset continuity

The source review found a departure edge when a positive projection is followed by an inconclusive
provider read and then a positive disconnection. The focused follow-up must prove the actual service
callback cleans up ownership once; **Unknown alone must not disconnect**. Keep #128 open for hardware
acceptance even after that regression passes.
Run and log every step of the [focused review's complete matrix](../reviews/2026-10-03-issue-128-continuity-review.md)
alongside C-01–C-08; its focus/boundary order, two-headset, fallback, late-return and diagnostic cases are required.

| ID | Scenario | Required evidence / pass condition |
| --- | --- | --- |
| C-01 | Play through a heard Bluetooth headset, then connect projected Auto; repeat USB/wireless. | Same book/position continues in that headset. No unintended phone/car reroute, pause or duplicate Play. |
| C-02 | Deliberately pause before connecting, and pause while continuity recovery is pending. | Remains paused after connect, focus return and provider/controller updates. |
| C-03 | Headset merely connected while speaker is heard; explicitly choose speaker or car before arrival. | No inferred headset takeover; preserve explicit choice and actual heard-route ownership. |
| C-04 | Projection → Unknown → NotConnected; repeat Automotive → Unknown → NotConnected in an appropriate host. | Unknown preserves ownership; confirmed disconnect releases it and balances connection/timer bookkeeping exactly once. Automated injection plus a device/provider trace if reproducible. Do not claim the provider failure occurred if it could not be induced. |
| C-05 | Projection → Unknown → Projection; repeated disconnect and multiple car-controller clients. | No false departure/rearrival, duplicate recovery or negative/stale connection count; final-client cleanup still works. |
| C-06 | Lost focus on car arrival, rebind/recreate the car controller, then disconnect or reconnect. | Recovery respects profile/transport and route intent; no lingering car state suppresses later schedule eligibility. |
| C-07 | Focus pauses ExoPlayer with an active timer, then late continuity recovery arrives; repeat manual Pause/Stop/book/profile change and actual expiry after resumed audio. | Paused timer stays frozen. Recovery respects newer intent; actual expiry cannot be undone by automatic Play. This cross-policy scenario needs a regression/device run; source review alone is not a pass. |
| C-08 | Car browse/player surfaces, profiles, output indicator, absent Queue, artwork and same-count invalidation. | Run every step of [combined drive acceptance](../android-auto-pd001-drive-acceptance.md) and [browse invalidation](../android-auto-browse-invalidation-acceptance.md). PD-001 root is exactly Continue → Series → Authors → Profiles. No Library/History replacement. |

## Sleep

Manual phone expiry, extend/cancel and title/countdown subcases passed in the
[phone report](2026-10-03-phone-acceptance.md). Lock-screen/headset, scheduled/grace and all car steps remain NOT RUN.

Run **every step** of [scheduled sleep and active-timer projections](../device-test-sleep-schedule.md),
including the following delivery-specific cases. These rows supplement its notification, sensor, grace,
overnight, civil-clock and lifecycle steps rather than replacing them.

| ID | Scenario | Pass condition |
| --- | --- | --- |
| S-01 | Manual/automatic timer, phone mini/full player, expiry/cancel/extend and recreation. | Book title remains intact; Sleep action owns countdown and announces a useful remaining time; updates preserve playback. PLAY-008. |
| S-02 | Notification/lock screen/headset timer projection while disconnected, then connect/disconnect Auto. | Countdown title/action is present on the intended non-car surfaces; Auto always has book title and no timer action/command. Shared phone notification hides it during Auto and restores it after disconnect. PD-002. |
| S-03 | Enter/cross nightly window while Auto is connected; suspend timer creation across connection/rebinding. | No new automatic timer; existing automatic/manual timers survive; manual in-app creation works. |
| S-04 | Disconnect inside window while playing, while paused, and after a manual cancellation. | Only eligible active playback arms one ordinary timer; no audio starts; manual suppression persists. |
| S-05 | Boundary/end/overnight/next occurrence, timezone/wall-clock changes, service/process recreation. | Eligibility and durable suppression follow the civil window; no extra timer, lost manual timer or implicit Play. |
| S-06 | Shake during/after grace, grace Off, sensitivity levels, sensing disabled and schedule end. | Only eligible gestures restart; expired grace/disabled sensing/window end cannot restart playback. |

## Downloads/storage

The [verification-cancellation log](2026-10-04-download-verification-cancellation.md) records reproduced
late verification/rename and invalid-416-clear regressions with actual Job cancellation, real Room and
filesystem. DC-A01–07 pass; DC-P01–13 distinguish scoped API36 phone evidence from remaining physical
obligations. APK2183 passes real transfer, Pause/force-stop/relaunch/Resume, cancel/confirm partial discard,
native full verification, cleanup and 27 isolated security tests. Controlled timing, response, sharing,
storage, playback and accessibility matrices remain NOT RUN; D-10's asynchronous removal/new-claim
acceptance stays open. APK2183 exposed 28% versus 31% from durable bytes. The correction on APK2184 passes actual caller
guards and numeric Book/Downloads Pause, force-stop/relaunch, discard and Resume assertions (45% → 41%).
Controlled R-123 response/replacement/old-attempt cases remain pending. Use this log alongside
D-01–D-15, not as whole-matrix acceptance.

Stored-copy verification navigation was sampled in the [phone report](2026-10-03-phone-acceptance.md).
APK2183 adds bounded transfer/recovery/discard evidence in the log above. Remaining steps below are
NOT RUN; existing complete copies and one disposable fixture do not accept every transition.

| ID | Scenario | Pass condition / requirement |
| --- | --- | --- |
| D-01 | Offline/metered Wi-Fi-only queue, regain Wi-Fi, retryable backoff and process restart. | Waiting/Retrying/Running/Complete are truthful and recover automatically; terminal failure offers Retry. DL-001/004, #108/#109. |
| D-02 | Retry a Failed Book item while WorkManager starts/runs; late completion and missing execution. | Live percent reaches at most 99 until durable completion; no stale Failed label/disabled Retry; durable fallback remains coherent. R-124. |
| D-03 | Pause mid-file, kill/relaunch, Resume; cancel/refuse/confirm partial discard separately. | Paused and partial bytes persist; valid resumptions reuse bytes; cancellation changes nothing; confirmed discard preserves committed media. #108/#112, R-119. |
| D-04 | Two transfers plus stored books, notification tap and denied notification permission. | Independent progress, stable stored section and useful Downloads navigation; denied permission does not block UI. #120. |
| D-05 | A/B share a copy/transfer; A removes or stops while B retains; last claim removal and device pin. | Shared bytes/transfer survive A; shared copy offers no Pause; final removal respects committed ownership and pin. PD-003/004, R-119. |
| D-06 | TalkBack and 200% text on Book download percent/ring and Pause/Stop/Keep prompt. | Percent spoken once, all actions reachable, no clipping; Pause is persisted before cancel and sharing refusal never stops B. R-119. |
| D-07 | Hidden copy and matching item IDs on another server; switch/lock/sign out profile. | Generic inaccessible row; no hidden title/author/failure detail; server/item identity does not grant access. AUTH-002, DL-003. |
| D-08 | Download to card, remove/reinsert intact or changed card, permission loss and low space. | Unavailable is distinct from corrupt; preserve bytes/manifest; intact card re-verifies without redownload; truthful disclosed fallback and conservative deletion. #110. |
| D-09 | Upgrade existing downloads/database and legacy/unknown volume ownership. | Preserve data/schema and claims; migrations succeed; no destructive migration or guessed volume ownership. |
| D-10 | Slow-disk removal/cancel late writes and new claim during removal. | Reproduce and record R-120 before choosing additional locking; no claim or valid media is lost. |
| D-11 | Shared transfer after original profile signs out/is removed. | Four real downloader regressions reproduced R-122; current eligible claims now authorize file/cover requests. Run every [DEV-DL-11 case](../reviews/2026-10-03-shared-download-ownership.md); hardware results remain NOT RUN. |
| D-12 | Pause/resume from no-ETag server, changed validator, interrupted/invalid/partial response. | Never resume bytes without a validator; truthful restart percent; no corrupt committed copy. R-123. |
| D-13 | Last claim belonged to a removed profile. | Record retained orphan/space behavior; R-121 cleanup and recovery UX remain separately scoped. |
| D-14 | Long throttled transfer on Android 15+ approaching dataSync timeout. | Record timeout/stopped reason, scheduling and partial-data recovery; R-118 remains investigation until exercised. |
| D-15 | Smart next-book, metered/charging/storage constraints, completion verification and retention cleanup. | Policy respects settings, accessible next item and physical-copy ownership; completion is atomic and verified. DL-002/005/006. |

The [shared-transfer review](../reviews/2026-10-03-shared-download-ownership.md) logs the 16 access-policy
cases, all 17 downloader cases (including six new regressions), and granular D-11/D-12 device steps.
Validated owner-change resume and no-ETag restart passed with real Room/filesystem fixtures. This does not
prove physical WorkManager restart, network metering, permission revocation or visible progress.
Its forced full formatter/verification gate passed in 7m 30s with all 1,119 tasks executed and 2,133 tests,
zero failures/errors. Use the final current-head CI/APK record for the device run; AUTO-DL-12-01 is now reproduced and corrected in the
[second-cancellation review](../reviews/2026-10-04-download-restart-progress.md). Its six granular
physical cases remain NOT RUN at the owner's request; continue the roadmap without treating them as passed.

## UI, security, compatibility and release function

The [phone report](2026-10-03-phone-acceptance.md) records About/installed-byte identity, first-page
Loopbound rendering, player text/rotation samples and a landscape contrast concern. The
[2178 continuation](2026-10-04-phone-2178.md) records its in-place upgrade and selected revised subcases.
The [2179 continuation](2026-10-04-phone-2179.md) repeats all27 connected tests and passes selected compact
series/glow/large-text/last-row cases. Benchmark1.5.0 passes the five historical methods, a library-profile
control and two app-profile experiments. Startup meets the fixture target; P95 frame cost still exceeds
the comparison budget. The generated app profile is retained outside production because no benefit is
established. Remaining UI/security/server/manual-performance matrices and final-main delivery acceptance stay pending.

| ID | Scenario | Required evidence |
| --- | --- | --- |
| U-01 | 320/375/414/768 dp, scales 1.0/1.3/2.0, portrait/landscape, long English/Norwegian, light/dark/AMOLED/dynamic/background packs. | Apply the [UI triage matrix](ui-roadmap-triage.md): effective contrast, minimum/content geometry, player clearance, theme-preview parity, missing/loading/failed covers and reduced motion. #194/#195. |
| U-02 | TalkBack order/labels/values, whole-book/chapter seek, nested actions, 48 dp targets and keyboard/Back. | Full controls remain reachable and coherent. Record actual speech/focus rather than a semantics-tree-only result. |
| U-03 | Root and pushed Sign in; success/cancel/drafts and predictive/system/toolbar Back. | Explicit navigation context gives the expected destination; root has no Back arrow. #176 is implemented on PR #235, stacked on #234; [Sign-in log](2026-10-04-sign-in-back.md) records software evidence and pending physical U-03-01–07. |
| U-04 | Empty/filter/no-results recovery, offline/loading/error, connection status and app appearance. | States and recovery remain distinct; active appearance and a non-color status cue are required. Unimplemented #195 residuals stay planned. |
| U-05 | WebView/provider/version, opaque background, reduced motion, standalone/Haze isolation. | Reproduce #190 on an affected device before selecting a permanent mitigation; record provider and each matrix result. |
| U-06 | Gesture navigation — #227. | Implemented on the browse branch; see the dated browse delivery report for scoped source/render/phone results and missing acceptance. |
| U-07 | Browse counts — #228. | Implemented on the browse branch; see the dated browse delivery report for scoped source/render/phone results and missing acceptance. |
| U-08 | Author details — #229. | Draft implementation with automated/caller/native-render evidence in [author log](2026-10-04-author-details.md), plus [pending-query/key correction](2026-10-05-author-observation.md). Selected later phone subcases and remaining U-08 obligations are in the [current merge plan](2026-10-05-pr-merge-test-plan.md); issue stays open. |
| Q-01 | Existing-install upgrade, About identity/version, stored profiles/passcode/progress/downloads and Loopbound bundle. | APK signer/version allow an in-place upgrade; data survives; About and artifact describe actual bytes/source. 2178 installed bytes and 2177→2178 in-place upgrade/data retention passed in the linked continuation; repeat against the new signed APK. Earlier About/Loopbound first-page results retain their original build scope. |
| Q-02 | Auth expiry/reauthentication, locked profiles, app-switcher privacy and controller/exported-command boundaries. | Offline data/passcode survive ordinary reauth; unauthorised controllers cannot browse/clear privileged state; no secrets/private metadata in shared logs. AUTH-002/003/004, section 5.2. |
| Q-03 | Selected Audiobookshelf versions: local/remote progress/history, server compatibility and offline sync. | Fixture-backed endpoints match live selected versions; missing fields/capabilities fail compatibly, with no invented endpoints or ignored TLS checks. |
| Q-04 | Metadata/cover/match/scan, permission denial, admin user writes, database-only removal versus source-file deletion. | Repository and UI enforce permission; write errors are typed; confirmation precisely describes action; source deletion is safe or absent. Section 25. |
| Q-05 | Cached local player and library startup, 2,000-book list frame timing, baseline profile and download/playback stress. | Run [benchmark procedures](../benchmark.md): cached start/interactive under 1 s where specified, recorded frame timing and no ANR. Use the benchmark variant rather than inferring these from a debug APK. |
| Q-06 | API 26/31/34/36, Bluetooth/wired routes, DHU and actual car/Automotive where available. | Record each supported configuration separately; an unavailable host remains NOT RUN. |

## Browse acceptance — planned 2026-10-04; scoped implementation/results below

Owner decisions PD-006/007 define the acceptance obligations. #227/#228 implementation and scoped results now exist on PR #230; #229 has a draft implementation stacked on #230, with all physical acceptance still pending.
The original issue-registration change claimed no app-function or phone test. The subsequent #227/#228
implementation and its scoped automatic/device results are tracked in the
[browse delivery report](2026-10-04-browse-selection-counts.md); use its exact APK and pending portions.
#229's author-detail cases have scoped software evidence in the [author log](2026-10-04-author-details.md); **physical acceptance remains NOT RUN**. Use the existing result template for exact
APK/configuration, expected/observed behavior, UTC/evidence and regressions. Guard actual callers; future
bug tests must fail on the old behavior. Source/UI semantics checks do not replace physical gesture,
TalkBack or rendering evidence. All new work preserves Room/profile/playback ownership and adds no endpoint/schema here.

### Gesture navigation — #227

| Case | Scenario | Required result | Current result |
| --- | --- | --- | --- |
| U-06-01 | Books → Series → Books | After each settle content/pill/icon-label highlight/selected semantics agree. Exercise the actual HomeScreen/ViewModel callback path; record the reported failure before the fix. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-06-02 | All axes and edge overspill | Traverse Books/Series/Authors/Genres in both directions; Books/Genres overspill springs back without committing another axis. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-06-03 | Interrupted gesture and tap intent | Partial/cancelled drags, rapid reversals/repeated swipes and a tab tap during settlement end at the final destination without oscillation or stale highlights. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-06-04 | Restoration and return from details | Restore a non-default axis, recreate the Activity and return from Book/Series/Author details; displayed content and selection remain aligned. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-06-05 | Selected accessibility state | Exactly one settled destination is selected; actual TalkBack focus/announcement matches the visible page, including cancelled gestures. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-06-06 | Appearance and playback continuity | Normal/200% text, portrait/landscape and reduced motion, offline and active audio; record privacy-safe state capture/video and no audio/queue/progress interruption. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |

### Browse counts — #228

| Case | Scenario | Required result | Current result |
| --- | --- | --- | --- |
| U-07-01 | Distinct entity fixtures | Zero/one/many books/series/authors/genres, duplicates and coauthors; each noun/count matches the displayed entities, Books shelves retain uncapped source totals. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-07-02 | Scope and localization | Search/filter/focused-book results and English/Norwegian zero/one/many/spoken labels; focused results count books and other axes count their own entities. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-07-03 | Library/profile authorization | Switch library/profile and revoke/hide items; no inaccessible content count or stale prior-profile total is exposed. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-07-04 | Sync and unloaded states | Loading, success, partial success, failure and never-synced fixtures; retain partial caveat and never show fabricated zero counts for unloaded pages. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |
| U-07-05 | Updates, gestures and accessibility | Axis taps/swipes, Room updates and cached offline state; count settles with displayed axis, stays readable at large text, is spoken correctly and preserves playback. | Scoped automatic/phone results and remaining portions in [dated report](2026-10-04-browse-selection-counts.md). |

### Author details — #229

| Case | Scenario | Required result | Current result |
| --- | --- | --- | --- |
| U-08-01 | Both route entry points | Home author card and Book-detail author link open the same existing destination with the correct author and Series/Standalone sections; missing author state remains truthful. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |
| U-08-02 | Grouping and identity | Series-only, standalone-only, mixed, coauthored and multi-membership fixtures; stable identities/order, no false standalone duplication and no primary-series/sequence policy change. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |
| U-08-03 | Completion truth | Finished/in-progress/unstarted/unknown books and complete/partial/unknown series, filters and incomplete catalogue; all accessible series members determine completion. Inward green cue/no checkmarks plus readable/spoken state. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |
| U-08-04 | Existing detail destinations | Series and standalone taps open correct existing detail routes; details navigation never issues Play, replaces the queue or changes progress. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |
| U-08-05 | Back and recreation | Toolbar/system/predictive Back restore Home axis/query/filter/sort/scroll, both entry points survive Activity recreation and remain reachable. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |
| U-08-06 | Offline and privacy states | Offline cached data, loading/error/missing covers, locked/switched/revoked profile; no stale private author/book information, UI API calls or unauthorized artwork access. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |
| U-08-07 | Adaptive/rendered accessibility | 320/375/414/768 dp; font 1.0/1.3/2.0; English/Norwegian; portrait/landscape; light/dark/AMOLED/dynamic/artwork; active mini-player; reduced motion; 48 dp targets and actual TalkBack ordering/completion speech. | Scoped source/JVM/render coverage in [author log](2026-10-04-author-details.md); physical acceptance NOT RUN. |

Garmin #119 is low priority / parked under PD-007. No Garmin test campaign, research child issue or
implementation acceptance is scheduled until the owner returns with evaluation findings and resumes that
lane. Its eventual device/transfer/progress matrix must be agreed then; there is no Garmin PASS claim here.

Run the current scenarios above against the new APK. Historical evidence in `device-test-0.9.14.md`
remains dated evidence, not an instruction to reproduce its obsolete hierarchy or uninstall/signing workarounds.
Keep release section 25 pending until its applicable functional, security and quality cases are recorded.

2026-10-05 U-08-05: signed combined APK2189 failed author-scroll restoration after the Book/Author/Series
Back chain. The [correction log](2026-10-05-author-observation.md) records the actual-screen red/fixed/reverted
guard and strict gate. Physical repeat on a corrected combined APK remains pending.
