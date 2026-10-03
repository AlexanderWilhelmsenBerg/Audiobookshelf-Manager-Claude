# Roadmap verification register

**Classification:** Test inventory and execution log; sequencing remains in [the roadmap](../roadmap.md).
**Created:** 2026-10-03. The owner will supply a phone later. All current device rows below are **NOT RUN**.

This register covers the CI/reliability delivery, the historical issue #36 solution, and the remaining
functional/release checks in PRODUCT_SPEC sections 17, 21 and 25. Merged code and passing JVM tests do not
transfer physical acceptance from an older APK. GitHub #128 is historical Forgejo #36; GitHub PR #36 is an
unrelated privileged-write contract change.

## Record a result for every case

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
| A-01 | `ktlintFormat`, then `verifyDebug -Pshelfplayer.warningsAsErrors=true`; force `--rerun-tasks` after classpath changes. | Combined #211–#216 candidate `c6236833` passed: all 1,119 tasks executed in 7m 21s; app 521, playback 511, datastore 30 and library 129 tests, zero failures/errors. Rerun for a subsequent implementation change. |
| A-02 | Release lint, `testReleaseUnitTest`, release assembly, SBOM and vulnerability checks. | Main `d3596b2f` completed all tiers; combined `d81de778` release tests reran all 394 tasks in 1m 56s, 1,376 tests with zero failures/errors. Record the final main run separately. |
| A-03 | CI policy fixtures, Actionlint and Bash syntax: main/PR/manual cancellation, previous-main classification, scoped cache pruning and immutable Room schemas. | All 16 policy tests passed, including real-Git stacked-PR/schema fixtures. Observe a trusted main seed and the next PR restore/save timings; first main seed `18c2e618` passed. |
| A-04 | Prove each regression fails without its fix; inspect actual production callers. | R-115 generation/acceptance/lock/outbox, scheduled-car suppression, phone title and R-124 Book observer/Pause regressions have recorded red/green evidence. #128's actual monitor-to-service regression failed 3/5 on main `3e699786`, then passed 5/5 with the lifecycle latch. All 68 focused cases, formatter, playback ktlint and detekt passed; its combined full gate is recorded with the delivery. |
| A-05 | Audiobookshelf contract fixtures, missing required fields/unknown fields, compatibility failures and migration tests. | Included in the full gate. Live selected-server-version acceptance remains pending; no new endpoint or schema was introduced by #211–#216. |
| A-06 | `:core:datastore:connectedDebugAndroidTest`. | **NOT RUN**; requires the supplied phone/emulator. Record passcode/encryption and lifecycle instrumented cases from the task's report. |
| A-07 | Domain/core and security-policy coverage. | Debug/JVM gate enforces the existing 80% domain/core rule. R-125's separate 90% redaction hook is still a follow-up, not accepted from that gate. |
| A-08 | Exact final-main APK: trusted `apk.yml`, stable signing, built version/About identity and downloadable artifact. | Required with the final CI handoff for each delivery batch. Log the immutable built SHA and actual checks; never describe an assemble-only APK as verified. |

For branch-only/main builds, use **Build APK** directly: the PR verification workflow's optional APK
handoff requires an open PR number. Set `variant=debug` and `run_checks=true` when initiating final checks
and packaging together. If the exact immutable SHA already has passing Standard/main verification, an
assemble-only packaging run may reuse that evidence, but link both runs. Keep signing in the existing
trusted workflow. Retain the APK and checksum for the later phone run; confirm upgrade compatibility on
the phone before installing over existing data. Keep the ordinary Loopbound bundle unless explicitly
testing a build without it, and record its source SHA.

## Playback, sessions and profiles — all device cases NOT RUN

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
| C-07 | Focus has paused ExoPlayer, then sleep expires before late continuity recovery; repeat manual Pause/Stop/book/profile change. | Timer expiry and newer intent cannot be undone by an automatic Play. This cross-policy scenario needs a regression/device run; source review alone is not a pass. |
| C-08 | Car browse/player surfaces, profiles, output indicator, absent Queue, artwork and same-count invalidation. | Run every step of [combined drive acceptance](../android-auto-pd001-drive-acceptance.md) and [browse invalidation](../android-auto-browse-invalidation-acceptance.md). PD-001 root is exactly Continue → Series → Authors → Profiles. No Library/History replacement. |

## Sleep — all device cases NOT RUN

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

## Downloads/storage — all device cases NOT RUN

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
| D-11 | Shared transfer after original profile signs out/is removed. | Reproduce R-122; retaining B still sees truthful transfer/result. Decide any corrective ownership policy from evidence. |
| D-12 | Pause/resume from no-ETag server, changed validator, interrupted/invalid/partial response. | Never resume bytes without a validator; truthful restart percent; no corrupt committed copy. R-123. |
| D-13 | Last claim belonged to a removed profile. | Record retained orphan/space behavior; R-121 cleanup and recovery UX remain separately scoped. |
| D-14 | Long throttled transfer on Android 15+ approaching dataSync timeout. | Record timeout/stopped reason, scheduling and partial-data recovery; R-118 remains investigation until exercised. |
| D-15 | Smart next-book, metered/charging/storage constraints, completion verification and retention cleanup. | Policy respects settings, accessible next item and physical-copy ownership; completion is atomic and verified. DL-002/005/006. |

## UI, security, compatibility and release function — device/server cases NOT RUN

| ID | Scenario | Required evidence |
| --- | --- | --- |
| U-01 | 320/375/414/768 dp, scales 1.0/1.3/2.0, portrait/landscape, long English/Norwegian, light/dark/AMOLED/dynamic/background packs. | Apply the [UI triage matrix](ui-roadmap-triage.md): effective contrast, minimum/content geometry, player clearance, theme-preview parity, missing/loading/failed covers and reduced motion. #194/#195. |
| U-02 | TalkBack order/labels/values, whole-book/chapter seek, nested actions, 48 dp targets and keyboard/Back. | Full controls remain reachable and coherent. Record actual speech/focus rather than a semantics-tree-only result. |
| U-03 | Root and pushed Sign in; success/cancel/drafts and predictive/system/toolbar Back. | Explicit navigation context gives the expected destination; root has no Back arrow. #176's implementation slice remains planned. |
| U-04 | Empty/filter/no-results recovery, offline/loading/error, connection status and app appearance. | States and recovery remain distinct; active appearance and a non-color status cue are required. Unimplemented #195 residuals stay planned. |
| U-05 | WebView/provider/version, opaque background, reduced motion, standalone/Haze isolation. | Reproduce #190 on an affected device before selecting a permanent mitigation; record provider and each matrix result. |
| Q-01 | Existing-install upgrade, About identity/version, stored profiles/passcode/progress/downloads and Loopbound bundle. | APK signer/version allow an in-place upgrade; data survives; About and artifact describe actual bytes/source. Installation is pending, not proven by assembly. |
| Q-02 | Auth expiry/reauthentication, locked profiles, app-switcher privacy and controller/exported-command boundaries. | Offline data/passcode survive ordinary reauth; unauthorised controllers cannot browse/clear privileged state; no secrets/private metadata in shared logs. AUTH-002/003/004, section 5.2. |
| Q-03 | Selected Audiobookshelf versions: local/remote progress/history, server compatibility and offline sync. | Fixture-backed endpoints match live selected versions; missing fields/capabilities fail compatibly, with no invented endpoints or ignored TLS checks. |
| Q-04 | Metadata/cover/match/scan, permission denial, admin user writes, database-only removal versus source-file deletion. | Repository and UI enforce permission; write errors are typed; confirmation precisely describes action; source deletion is safe or absent. Section 25. |
| Q-05 | Cached local player and library startup, 2,000-book list frame timing, baseline profile and download/playback stress. | Run [benchmark procedures](../benchmark.md): cached start/interactive under 1 s where specified, recorded frame timing and no ANR. Use the benchmark variant rather than inferring these from a debug APK. |
| Q-06 | API 26/31/34/36, Bluetooth/wired routes, DHU and actual car/Automotive where available. | Record each supported configuration separately; an unavailable host remains NOT RUN. |

Run the current scenarios above against the new APK. Historical evidence in `device-test-0.9.14.md`
remains dated evidence, not an instruction to reproduce its obsolete hierarchy or uninstall/signing workarounds.
Keep release section 25 pending until its applicable functional, security and quality cases are recorded.

## Deferred scope

Silo/shared-cache implications research remains a separate deferred decision. iOS (#121–#123) is low
priority and stays after Android correctness acceptance and the planned Android system surfaces. No iOS
implementation or hardware acceptance is claimed by this delivery.
