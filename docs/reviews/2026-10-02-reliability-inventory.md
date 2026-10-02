# Reliability inventory — 2 October 2026

**Classification:** Dated source/tracker evidence; `../roadmap.md` owns sequencing.

Inspected GitHub main `c6b52b22` and PR #205 head `fee78e12`. The tracker returned 44 open issues and one
open PR. No issue was closed by this inventory. GitHub is authoritative; older Forgejo text and August
closeout documents cannot establish current implementation status.

## Issue reconciliation

Issue numbers below belong to [the GitHub tracker](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues).
“Implemented” means source exists on main, not physical acceptance. “Proposed” does not assert that every
individual recommendation is absent from code. Grouped rows enumerate all 44 open issues exactly once.

| Issues | State / evidence | Next action and requirements |
| --- | --- | --- |
| #99 | Snapshot invalidation: Forgejo PR #69; #205 adds remembered-book observation. | Host/profile acceptance; LIB-002, AUTH-002, PD-001. |
| #100 | Route-heard ownership: Forgejo PR #59. | Headset/speaker/car sequences; PLAY-002, ROUTE-002. |
| #101 | Separate display formatter cleanup. | Preserve current output/membership selection; LIB-003. |
| #108 | Pause/Resume/Retry: Forgejo PR #55. | Verify recovery preserves bytes; DL-001/002. |
| #109 | Execution observer wired to DownloadsViewModel: Forgejo PR #94. | Waiting/retry/restart evidence; DL-001/004. |
| #110 | Volume identity, verifier, storage projection: Forgejo PR #94. | Physical removable-storage acceptance; DL-002/003. |
| #111 | PD-003 accepted; claims/device pin: Forgejo PR #94. | Shared removal/privacy acceptance; DL-003/006, section 5.2. |
| #112 | Partial-data discard: Forgejo PR #94. | Confirmation and committed-file retention; DL-001/002/003. |
| #114 | Planned semantic Android action contract. | After playback acceptance; PLAY-001, AUTH-002/003, section 5.2. |
| #116 | Planned headset automation. | After #114; ROUTE-001/002. |
| #117 | Planned widget. | After #114; section 3.3, PLAY-001, AUTH-002. |
| #118 | Planned Quick Settings surface. | After #114; PLAY-001, profile privacy. |
| #119 | Garmin evaluation/custom companion proposal. | Evaluate existing controls first; PLAY-001. |
| #120 | Queue/progress/notifications: Forgejo PR #94. | Device transitions; DL-001/004. |
| #121, #122, #123 | iOS foundation, CarPlay, Live Activity proposals. | Defer until Android correctness; establish platform requirements. |
| #124 | Sleep schedule: Forgejo PR #63. | Device/schedule acceptance; PLAY-008/009, SET-002. |
| #126 | Car indicator: Forgejo PR #67. | Projected-host evidence; PLAY-001/002. |
| #128 | Narrow continuity gate: Forgejo PR #60; #205 adds tests. | Physical headset/car acceptance; PLAY-002. |
| #130 | Phone/car layout: Forgejo PR #56. | Verify sleep-slot exception too; PLAY-001/007/008. |
| #134 | Recent-book hydration: Forgejo PR #57. | Reconcile open status against latency/access evidence; LIB-001, SYNC-002. |
| #175 | Shared-cover transition evaluation. | Defer cosmetic experiment; LIB-004. |
| #176 | Sign-in Back navigation report. | Reproduce Profiles entry first; AUTH-001/002. |
| #177, #178 | Player motion partly addressed in PR #200; app-wide scope is broader. | Reconcile predictive Back/navigation and rendered evidence; PLAY-001, section 17.2. |
| #179 | Book detail proposal. | Responsive/accessibility slice; LIB-004. |
| #180 | Settings hierarchy proposal. | Everyday controls versus diagnostics; SET-001/002. |
| #181 | Profile chooser proposal. | Lock/switch boundaries and player clearance; AUTH-002. |
| #182, #183 | Full/mini player changed in PR #200. | Compare remaining criteria and large-text rendering; PLAY-001/007/008. |
| #184 | Home hierarchy proposal. | Consolidate with #194; LIB-002. |
| #185 | Idle holder on main; #205 adds restorer tests and switch fallback. | Cold/warm/locked/empty car acceptance; PLAY-001, ROUTE-001/002, section 6.5. |
| #186 | Card appearance controls proposal. | Readable effective surfaces; SET-001/002. |
| #187 | Finished-book indicator proposal. | Repository progress truth; LIB-002, PLAY-004. |
| #188 | CI tiers: Forgejo PR #91, GitHub PRs #173/#201. | Audit residual coverage/telemetry requirements; sections 16.5/17/18. |
| #189 | Countdown/grace/sensitivity: Forgejo PRs #84/#93. | Physical metadata/sensor acceptance; PD-002, PLAY-008/009. |
| #190 | WebView flicker investigation. | Device isolation matrix; preserve local assets/playback. |
| #191 | PD-001 tree/artwork implemented; PR #203 fixed profile rows/hand-over. | Car browse/profile/privacy acceptance; PD-001, LIB-002, AUTH-002. |
| #192, #193 | Adaptive list and leading Resume-card proposals. | Consolidate under #194; LIB-002. |
| #194, #195 | Umbrella audits; player/download findings partly addressed. | Track residual findings in child slices; section 17.2, LIB-002, SET-002. |
| #196 | Queue suppression: Forgejo PR #67. | Absent Queue on projected host; no History replacement; PLAY-001, PD-001. |

## PR #205 source review

Reviewed the production diff, restorer tests, profile-restore tests and repository observation changes.
GitHub reported Preflight and verification SUCCESS at `fee78e12`; APK dispatch was skipped. No reviews or
review comments were present. This does not claim a local PR build or physical acceptance.

The extraction retains the service decisions. Tests exercise Robolectric ExoPlayer for None/Arm/ArmAndPlay,
locked profiles and in-flight replacement. Remembered-book observation is deduplicated and profile-scoped.
The controller's arm guard precedes book-change callbacks and item installation.

Remaining evidence gaps:

- The controller arm guard has a source-wiring assertion, not a behavioral controller test.
- Switching between two unlocked profiles during suspended restore is not covered by the new restorer
  tests. Existing guards check lock state and queue emptiness, not profile identity. This is an inherited
  boundary concern, not a new regression proven by this review; exercise it before accepting isolation.
- The service's `openQueue` calls `bookChanges.onBookOpened` before the restorer's final supersession check.
  A side-effect-free test lambda does not prove that a superseded real open leaves sleep/session bookkeeping
  untouched. Add integration evidence at that seam in the playback lane.
- Profile-switch fallback remains broader than cold-start restore by design.

No merge or public review submission was performed. Hardware-dependent issue closure remains pending.
