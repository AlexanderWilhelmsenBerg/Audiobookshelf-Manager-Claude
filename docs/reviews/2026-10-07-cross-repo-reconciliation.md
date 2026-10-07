# Cross-repository roadmap and documentation reconciliation

**Classification:** Dated source/tracker/document audit, not physical acceptance or runtime implementation.
**Date:** 2026-10-07. Requirements: PRODUCT_SPEC17/18/19/21/25; AUTH-002/003, PLAY-001/004/005, LIB-002/003, DL-001–006 for retained acceptance boundaries.

> This audit preserves the pre-implementation snapshot. Android PR #240 and Garmin PRs #5/#6 later merged on October 7; current delivery, gates and remaining acceptance are in the [bridge delivery log](../testing/2026-10-07-garmin-bridge.md) and [roadmap](../roadmap.md). Candidate-only and failed-checkout statements below are historical.

## Exact source and delivery snapshot

| Area | Observed state |
| --- | --- |
| Android main | `36c25043151b74bf2041b708d2255fb2086a1e29`, PR #238 merged2026-10-05T15:06:44Z. Prior six runtime PRs230/231/232/234/235/236 are also merged. |
| Android candidate | `mcp/garmin-phase-2-mobile-bridge`, `6239a14827374e78fab77e4f9eaeee7f8ca63307`, nine commits/14files relative to main. Owner confirmed this branch is the intended work. No open PR or Actions result returned for this head at the snapshot; not delivered on main. |
| Garmin main | `d8de6fb8be4788e2818312cee5c0a64721710352`, [PR1](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/1) merged2026-10-06T21:30:50Z; PRhead `055f0d0f8c55b92afebe58bbb4c59c319481bd79`. |
| Android tracker | 46open/23closed issues; 42Android +3excluded iOS +1Garmin umbrella. Zero open PRs. No issues closed by this reconciliation. |
| Garmin tracker | PR1 merged; [issue2](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/2) Phase 1 acceptance and [issue3](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/3) Phase 2 interoperability opened to track unfinished work. Two open issues; zero open PRs at snapshot. |

## Source-scoped verification and APK handoff

| Gate | Result and limits |
| --- | --- |
| Android current-main Standard | [37330126779](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37330126779) SUCCESS on36c25043; complete strict debug gate. |
| Android current-main release/security | [37330126716](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37330126716) SUCCESS on36c25043. |
| Android checked packaging | [37330127469](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37330127469) SUCCESS; publishes `bookwave-debug-0.10.6.1-2198.apk`, artifact11354307910, Actions archive digest `2902e9292f115825b9c9249853ec1f352e4269f2275958e7ae92cc5878d33717`. Archive digest is not a separately verified APK file/signature. Not downloaded/installed/physically accepted by this reconciliation. |
| Garmin PR gate | [37522590573](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37522590573) SUCCESS at055f0d0: guardrails, fenix843mm/fenix847mm compilation, test-enabled compilation, package export. |
| Garmin main gate | [37534424183](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37534424183) SUCCESS atd8de6fb8. Run No Evil execution/simulator/physical watch acceptance remain NOT RUN. Temporary CI signing is not stable store identity. |
| Android bridge | Eight test methods in source; no executed/green-head result established. SDK/classpath change requires forced full verification before delivery. |

Latest recorded phone APK2195 has scoped normal-font appearance/retention/offline evidence; it excludes #238. APK2196 is downloaded, not installed, and also excludes #238. Do not transfer those phone passes to2198. No phone/watch campaign runs here. Historical dated reports remain source-specific.

## Reconciled roadmap

Android priority remains reliability and logged acceptance, followed by residual UI/performance/download slices. #238 is merged, so the next bounded Android software slice is #194 profile/player clearance and effective-theme preview parity. System-action contract114 precedes widgets/Quick Settings/headset automation. Public-release acceptance remains separate.

Garmin Companion Phase 1 is merged/build-verified, not runtime accepted. Finish simulator/physical acceptance and jointly review Phase 2 contract/Android candidate before implementing the Garmin counterpart. Commands, legitimate-event reconciliation/Force sync, complication, watch face and Data Field remain later. WatchShelf/Sidecar coexistence is initial policy; optional provider/helper replacement is deferred. No new priority above Android, implementation, release or accepted protocol is inferred.

iOS/Silo are excluded. Historical PD-007 remains dated provenance with a current supplement. [Android integration boundary](../garmin-integration.md) and [Garmin dependency map](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/reconciliation.md) carry the cross-repository details.

## Every open Android issue disposition

Issue numbers below are GitHub IDs. Migrated Forgejo references in original bodies are historical provenance; issue36 corresponds to GitHub128. No original closure criteria are silently deleted.

| Issue | State at reconciliation | Remaining obligation |
| --- | --- | --- |
| [#99](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/99) [BW-AUTO-01] Make Android Auto browse invalidation shape-aware and snapshot-based | Browse invalidation delivered; host acceptance open | Existing snapshot/shape-aware browse invalidation is the current implementation; retain PD-001 root and profile authorization. Exercise the current Android Auto browse invalidation/drive checklists; a phone or JVM tree is not head-unit acceptance. |
| [#100](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/100) Replace HeadsetHold inference with route-heard ownership | Ownership policy delivered; heard-route acceptance open | RouteHeardOwnership is the single app authority; the old HeadsetHold proposal is historical. #128 continuity work consumes that owner. Real sink/focus/headset/car/explicit-intent evidence remains required; source/unit results do not prove audible routing. |
| [#101](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/101) Centralize series display-label formatting | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#108](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/108) [BW-DL-03] Correct download row actions: Pause, Resume and Retry | Implemented recovery actions; acceptance pending | State-owned Pause/Resume/Retry behavior and Book observer/Pause wiring are already delivered. PR #232 additionally preserves cancellation checkpoints and durable paused percentages. Selected2184 recovery checks PASS; D-01–D-15 wire/WorkManager/claim/storage/notification matrices remain open. Do not add a competing execution-state owner. |
| [#109](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/109) [BW-DL-04] Project WorkManager waiting and retry state into Downloads UX | Execution projection delivered; acceptance pending | WorkManager execution projection already exists; PRs #207/#209/#215 and merged #232 form the current recovery path. Backoff/constraints/stop/restart/notification acceptance remains in D-01–D-15. Original planned-state prose is a dated baseline, not an instruction to duplicate the observer. |
| [#110](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/110) [BW-DL-05] Treat unavailable removable storage separately from corrupt downloads | Open storage acceptance/correction scope | R-116/D matrix: same/different removable volume, disclosed fallback and corruption distinction. |
| [#112](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/112) [BW-DL-07] Add explicit discard-partial recovery action | Discard path delivered; wider acceptance pending | Explicit confirmed partial discard is present; selected2184 pause/relaunch/discard/resume phone checks PASS. PR #232 preserves verification-cancellation and durable progress semantics. Committed media, concurrent workers/claims, storage and wire cases remain pending; ordinary Retry stays non-destructive. |
| [#114](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/114) [BW-SYS-01] Expose BookWave actions to Android shortcuts and automation | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#116](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/116) [BW-AUTOMATION-01] Add headset-triggered Continue automation | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#117](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/117) [BW-WIDGET-01] Add Resume / Now Playing Android widget | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#118](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/118) [BW-QS-01] Add configurable BookWave Quick Settings tile | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#119](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/119) [BW-GARMIN-01] Plan fēnix 8 offline audiobooks with BookWave-managed transfers and sync | Cross-repository Companion; offline provider deferred | Garmin PR1 merged; Android bridge6239a148 candidate; Garmin2/3 acceptance/interop dependencies. |
| [#120](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/120) [BW-DL-08] Show active download queue and live progress in screen and notifications | Queue/progress projection delivered; acceptance pending | Active download/execution progress projection and Book/Downloads callers exist; merged #232 fixes durable stopped-state progress precedence. Selected2184 checks PASS. Live notification/denied-permission/multiple-transfer/constraints/reboot and full D-01–D-15 acceptance remain pending. |
| [#121](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/121) [BW-IOS-00] Establish staged iOS foundation and selective KMP boundary | Excluded; no iOS work | Excluded from current reconciliation execution queue; original future proposal retained. |
| [#122](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/122) [BW-IOS-CARPLAY-01] Add native CarPlay audiobook surface | Excluded; no iOS work | Excluded from current reconciliation execution queue; original future proposal retained. |
| [#123](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/123) [BW-IOS-LIVE-01] Add a purposeful BookWave Live Activity for sleep/download status | Excluded; no iOS work | Excluded from current reconciliation execution queue; original future proposal retained. |
| [#124](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/124) [BW-SLEEP-01] Add automatic nightly sleep schedule | Schedule implemented; physical matrix pending | The nightly sleep schedule is implemented on main; the earlier settings-PR prerequisite is historical. S-01–S-05 retain civil-time/schedule/notification/car/grace/sensor acceptance. No additional physical result is recorded by this reconciliation. |
| [#126](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/126) Car action still does not show as selected when the car is the output | Existing source; physical system acceptance open | Current Android Auto/notification drive matrix; no new device evidence in reconciliation. |
| [#128](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/128) A car connecting stops the book instead of letting the headset carry on | Continuity fixes merged; physical route gates open | App ownership/lifecycle continuity corrections are merged through the recorded reliability work. Historical issue36 maps to this GitHub issue. The drive/continuity matrices still require real headset/car/focus/explicit-intent evidence; no phone tests are added by this reconciliation. |
| [#130](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/130) Keep the skips in the phone notification when no car is connected | Existing source; physical system acceptance open | Current Android Auto/notification drive matrix; no new device evidence in reconciliation. |
| [#134](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/134) Hydrate recently active books before the full library refresh | Recent hydration exists; latency/scope acceptance open | Recent-book hydration already exists from historical Forgejo PR #57. Verify bounded latency, item/tag admission and unsynced progress before closure; do not add an uncontracted endpoint or competing refresh owner. |
| [#175](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/175) [Polish] Evaluate shared book-cover transition into Book details | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#176](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/176) [Navigation] Show Back when Sign in is opened from Profiles | Merged #235; remaining acceptance | PR #235 implements route Back and full wrapped toolbar titles. Selected isolated Add/reauth, predictive Back/IME/cancellation/process-death/error checks PASS on the recorded source; wider authentication/configuration acceptance remains pending. Original missing-arrow observations are historical. |
| [#177](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/177) [Hallmark] Give full-player expand/collapse spatial continuity and predictive Back | Partial player/motion delivery; acceptance open | Parts of this motion/Back scope landed in PR #200. Remaining actual player predictive Back/reduced-motion/window/font and state-restoration criteria remain open. The separately accepted Sign-in predictive Back cases are not whole-player acceptance. |
| [#178](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/178) [Hallmark] Establish a coherent app-wide navigation and motion language | Partial navigation/motion delivery; acceptance open | Parts of navigation/motion landed in PR #200; Home gesture/highlight correction is merged in #230. Selected2192 owner animation PASS does not accept every app transition/Back/reduced-motion configuration. Preserve the remaining U-01–U-05/U-06 inventory. |
| [#179](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/179) [Hallmark] Refine Book screen into a responsive reader-first detail page | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#180](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/180) [Hallmark] Refine Settings navigation and separate everyday settings from diagnostics | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#181](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/181) [Hallmark] Simplify the profile switcher into an identity-first account chooser | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#182](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/182) [Hallmark] Rebalance the full player around History-first audiobook navigation | PR #200 delivered player slice; residual acceptance | PR #200 delivered the History-first player slice; subsequent local History checkpoints and event date/time/progress/chapter behavior landed through #220/#222. U-01–U-05 and playback/history matrices retain broader accessibility/configuration/continuity acceptance. |
| [#183](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/183) [Hallmark] Simplify mini-player around Sleep Timer and Play/Pause | PR #200 delivered compact-player slice; residual acceptance | PR #200 delivered the compact-player slice; remaining clipping/clearance/motion/configuration checks stay under #194/#195 and U-01–U-05. Preserve Sleep Timer and Play/Pause and existing playback owners; do not treat the original audit as untouched source. |
| [#184](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/184) [Hallmark] Reduce Home chrome density and give shelves distinct visual roles | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#185](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/185) Android Auto should restore the latest played book into an idle Media3 session | Restore corrections merged; host acceptance open | Existing restoration/remembered-book/lifecycle corrections are merged. Verify cold/warm/projected host entry and heard playback using A/C/P matrices before closure; no second resume owner or playback start from passive sync. |
| [#186](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/186) [Appearance] Add Home card roundness, border and background controls | Appearance controls exist; residual audit open | Existing Appearance/card controls must be reconciled with #194 effective-theme preview parity and #187 semantic completion. Preserve the subtle inward green completion cue independent of decorative controls. Do not duplicate the settings surface; wider configurations/design criteria remain pending. |
| [#187](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/187) [UI] Add a consistent finished-book visual indicator to book cards | Series/shared/Author slices delivered; all-family audit open | Delivered series/shared and grouped Author cards use authoritative completion with a subtle inward green cue, no complete checkmark, plus readable/spoken state under PD-005/006. Selected owner appearance checks PASS. Audit every remaining Home/focused/standalone card family; decorative appearance settings must not hide semantic completion. |
| [#188](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/188) [CI] Tier verification by depth and compute profile | Existing CI; residual tier/timing audit | Audit current GitHub tiers/coverage/cache telemetry; do not recreate CI foundation. |
| [#189](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/189) [BW-SLEEP-02] Show sleep countdown in system media controls and add shake grace/sensitivity | Sleep policy delivered; physical matrix pending | Pause/focus/buffering countdown freezing, playing-only start, grace/Extra high/Ultra high behavior are delivered through existing sleep work and #220/#222. System-control/schedule/car and actual bedside sensor false-positive acceptance remain open in S-01–S-05; ultra sensitivity is not an accepted breathing detector without measurement. |
| [#190](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/190) Embedded Loopbound WebView flickers rapidly on Android | Open reproduced-symptom investigation | Provider/version/opaque-background/reduced-motion/Haze isolation before mitigation. |
| [#191](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/191) Redesign Android Auto browse home with artwork and profiles | PD-001 browse source delivered; host acceptance open | Current source targets Continue → Series → Authors → Profiles with safe cached artwork. The original redesign proposal is not a parallel backlog. Current DHU/head-unit discovery/render/profile/permission/continuity checks remain pending. |
| [#192](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/192) [Hallmark] Rework the flat book list into readable adaptive rows | Partial row fix merged #236 | PR #236 fixes bottom metadata clipping with minimum/content-driven book rows. Owner2195 last-line appearance PASS; native large-text guards PASS. Wider adaptive/design/configuration/timing work remains open; do not reintroduce a fixed card height. |
| [#193](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/193) [Hallmark] Give Continue listening a clear leading Resume card | Residual/future Android scope | Use roadmap dependencies/child triage; no new implementation or acceptance in this audit. |
| [#194](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/194) [Hallmark] Rework Home, profile picker and Appearance into a calmer library interface | Partial delivery; next bounded UI slice | Series/Author/general-row changes are merged; #236 fixes general-row clipping and #238 covers the separate Home recovery child of #195. Profile/player clearance and effective-theme preview parity are the next planned Android slice. Neither correction has been implemented in this reconciliation. Broader Home/Continue/Profile/Appearance redesign is not accepted by these partial fixes. |
| [#195](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/195) [Design audit] Improve contrast, compact player hierarchy and recovery clarity | Partially implemented; #238 merged | PR #238 is merged on main36c25043: findings4/7 now have non-color connection symbols and scoped no-results recovery. Native/source-reversion/contrast checks and strict PR/main CI PASS; physical U-09-01–05 remain NOT RUN. Other accent/player/motion/seek/download audit criteria retain their separate delivered/remaining scopes. |
| [#196](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/196) Hide Android Auto Queue from the player | Queue suppression source delivered; host acceptance open | PD-001 removes car History/replacement Queue; preserve current queue-suppression contract. Actual projected-host behavior remains in the Android Auto acceptance matrix. Do not close a host rendering criterion with phone/source evidence alone. |
| [#227](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/227) [Navigation] Keep gesture navigation and axis highlighting synchronized | Merged #230; remaining acceptance | Gesture/highlight correction is on main through PR #230, including the fast-fling/newer-tap correction. Selected APK2192 gesture animation, settled highlight and TalkBack checks PASS; wider restoration/profile/playback cases remain pending. Do not schedule another implementation of the original defect without reproduction. |
| [#228](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/228) [Browse] Show book, series, author and genre counts for the active axis | Merged #230; remaining acceptance | Entity-specific counts are on main through PR #230. Selected populated/localized/offline checks PASS on recorded builds; zero/one/many, live profile/library/revocation/partial-state cases remain pending. The old book-count source description is historical. |
| [#229](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/229) [Authors] Open author details with series, standalone books and completion state | Merged #234; remaining acceptance | Grouped Author details is on main through PR #234, including corrected observation and scroll restoration. Owner normal/200% completed-series and mixed-group layouts PASS on APK2191; selected route/offline checks PASS. Other grouping/privacy/restoration/configuration criteria remain pending. The original draft/APK2187 descriptions are historical. |

## Closed issue ledger retained

23closed issues (22substantive; #105 was an accidental temporary ticket). Existing closure state is retained; residual acceptance is carried in current roadmap/register rather than reopening completed feature foundations.

| Issue | Recorded closure |
| --- | --- |
| [#90](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/90) Handle Audiobookshelf user_item_progress_updated realtime events | Closed; existing history retained. |
| [#91](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/91) Unify resume freshness across app, media buttons, and realtime progress | Closed; existing history retained. |
| [#102](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/102) Retire stale R-81 output-cycle risk after Android Auto routing redesign | Closed; existing history retained. |
| [#103](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/103) [BW-DOC-01] Establish canonical documentation index and roadmap | Closed; existing history retained. |
| [#104](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/104) [BW-DL-01] Audit actionable download and offline recovery UX | Closed; existing history retained. |
| [#105](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/105) [CLOSED — accidental temporary issue] | Closed; existing history retained. |
| [#107](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/107) [BW-DL-02] Expose safe download failure reason and recovery presentation state | Closed; existing history retained. |
| [#111](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/111) [BW-DL-06] Make device-wide download copy management semantics explicit | Closed; existing history retained. |
| [#115](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/115) [BW-PLAY-01] Persist per-profile local remembered audiobook identity | Closed; existing history retained. |
| [#127](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/127) Player screen still shows the queue rather than History | Closed; existing history retained. |
| [#129](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/129) A profile switch runs one full-library query per remembered browse node | Closed; existing history retained. |
| [#132](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/132) Reconcile canonical roadmap with merged work and retire superseded Android Auto experiment | Closed; existing history retained. |
| [#133](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/133) Keep realtime progress synchronized while BookWave is foregrounded | Closed; existing history retained. |
| [#135](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/135) [BW-DEP-01] Execute staged latest-stable toolchain and dependency migration | Closed; existing history retained. |
| [#138](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/138) [Bug] Headset Play after app update can resume current book at 0:00 | Closed; existing history retained. |
| [#139](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/139) [Bug] History should record playback stop events on pause and sleep-timer expiry | Closed; existing history retained. |
| [#141](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/141) [Bug] Android Auto can fail to surface last played media from cold/background state | Closed; existing history retained. |
| [#142](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/142) [Bug] Service shutdown can detach playback before final session sync snapshots progress | Closed; existing history retained. |
| [#174](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/174) [Android Auto] Profile rows should switch profile instead of opening an empty browse view | Closed; existing history retained. |
| [#197](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/197) Headset Back/Previous should seek backward instead of restarting the audiobook | Closed; existing history retained. |
| [#198](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/198) Mini player missing when opening BookWave from active media notification | Closed; existing history retained. |
| [#199](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/199) Shake to extend active sleep timer is not working | Closed; existing history retained. |
| [#202](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/202) [Downloads] Pressing Download takes seconds to show "download started" | Closed; existing history retained. |

## Whole-repository Markdown inventory

All tracked Markdown files were read/inventoried and classified against their current authority and source. Active status contradictions are corrected in README/spec interpretation, roadmap, decisions, release, compatibility, architecture entry points, testing/register/triage and the Garmin plan/protocol/testing. Dated reports, ADRs, version ledgers and completed phase investigations retain original dates/results; listing them here does not rerun every historical investigation or upgrade upstream dependencies.

The Android owner checkout is unchanged; edits use a separate clean worktree. Android bridge source remains unchanged. Garmin reconciliation changes Markdown only. Local helper/snapshot files and private owner data stay out of commits.

| Android document | Disposition |
| --- | --- |
| [.github/pull_request_template.md](../../.github/pull_request_template.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [.github/workflow-archive/README.md](../../.github/workflow-archive/README.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [AGENTS.md](../../AGENTS.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [CHANGELOG.md](../../CHANGELOG.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [CLAUDE.md](../../CLAUDE.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [CONTRIBUTING.md](../../CONTRIBUTING.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [PRIVACY.md](../../PRIVACY.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [PRODUCT_SPEC.md](../../PRODUCT_SPEC.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [README.md](../../README.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [SECURITY.md](../../SECURITY.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/README.md](../../docs/README.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/adr/0001-record-architecture-decisions.md](../../docs/adr/0001-record-architecture-decisions.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0002-module-structure.md](../../docs/adr/0002-module-structure.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0003-result-and-error-model.md](../../docs/adr/0003-result-and-error-model.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0004-redacted-structured-logging.md](../../docs/adr/0004-redacted-structured-logging.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0005-fake-gateway-and-fixtures.md](../../docs/adr/0005-fake-gateway-and-fixtures.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0006-dependency-locking-and-verification.md](../../docs/adr/0006-dependency-locking-and-verification.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0007-contract-capture-and-licensing.md](../../docs/adr/0007-contract-capture-and-licensing.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0008-audiobooth-as-an-api-reference.md](../../docs/adr/0008-audiobooth-as-an-api-reference.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0009-cleartext-in-debug-only.md](../../docs/adr/0009-cleartext-in-debug-only.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0010-dependency-locking-deferred.md](../../docs/adr/0010-dependency-locking-deferred.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0011-stay-on-api-36-until-detekt-supports-agp-9.md](../../docs/adr/0011-stay-on-api-36-until-detekt-supports-agp-9.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0012-the-official-app-as-an-api-reference.md](../../docs/adr/0012-the-official-app-as-an-api-reference.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0013-finished-is-time-remaining-not-a-percentage.md](../../docs/adr/0013-finished-is-time-remaining-not-a-percentage.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0014-shake-restarts-the-sleep-timer-and-the-timer-is-logged.md](../../docs/adr/0014-shake-restarts-the-sleep-timer-and-the-timer-is-logged.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0015-skip-defaults-to-thirty-seconds-both-ways.md](../../docs/adr/0015-skip-defaults-to-thirty-seconds-both-ways.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0016-a-book-is-one-timeline-window.md](../../docs/adr/0016-a-book-is-one-timeline-window.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0017-smart-download-drives-the-series-queue.md](../../docs/adr/0017-smart-download-drives-the-series-queue.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0018-phase-3-downloads-as-the-owner-specified-them.md](../../docs/adr/0018-phase-3-downloads-as-the-owner-specified-them.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0019-bookwave-keeps-shelfplayers-application-id.md](../../docs/adr/0019-bookwave-keeps-shelfplayers-application-id.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0020-downloads-choose-a-volume-not-a-folder.md](../../docs/adr/0020-downloads-choose-a-volume-not-a-folder.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0021-source-file-deletion-ships-no-feature.md](../../docs/adr/0021-source-file-deletion-ships-no-feature.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0022-the-language-setting-lives-in-the-composition.md](../../docs/adr/0022-the-language-setting-lives-in-the-composition.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0023-the-profile-passcode-is-a-curtain-not-a-vault.md](../../docs/adr/0023-the-profile-passcode-is-a-curtain-not-a-vault.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0024-the-four-release-decisions.md](../../docs/adr/0024-the-four-release-decisions.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0025-the-grid-in-the-performance-target-does-not-exist.md](../../docs/adr/0025-the-grid-in-the-performance-target-does-not-exist.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0026-the-closeout-decisions.md](../../docs/adr/0026-the-closeout-decisions.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0027-the-output-chooser-asks-rather-than-commands.md](../../docs/adr/0027-the-output-chooser-asks-rather-than-commands.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0028-admin-authority-is-the-session-not-a-second-password.md](../../docs/adr/0028-admin-authority-is-the-session-not-a-second-password.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/adr/0029-android-auto-is-a-stable-audiobook-surface.md](../../docs/adr/0029-android-auto-is-a-stable-audiobook-surface.md) | Accepted decision/provenance retained; no unrelated decision reopened. |
| [docs/android-auto-browse-invalidation-acceptance.md](../../docs/android-auto-browse-invalidation-acceptance.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/android-auto-pd001-drive-acceptance.md](../../docs/android-auto-pd001-drive-acceptance.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/android-auto-player-opportunities.md](../../docs/android-auto-player-opportunities.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/api-compatibility.md](../../docs/api-compatibility.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/architecture/build.md](../../docs/architecture/build.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/architecture/module-boundaries.md](../../docs/architecture/module-boundaries.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/architecture/overview.md](../../docs/architecture/overview.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/architecture/playback.md](../../docs/architecture/playback.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/archive/README.md](../../docs/archive/README.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-acceptance.md](../../docs/archive/phase-1-acceptance.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-delta-test-0.1.10.md](../../docs/archive/phase-1-delta-test-0.1.10.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-delta-test-0.1.5.md](../../docs/archive/phase-1-delta-test-0.1.5.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-delta-test-0.1.6.md](../../docs/archive/phase-1-delta-test-0.1.6.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-delta-test-0.1.9.md](../../docs/archive/phase-1-delta-test-0.1.9.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-delta-test.md](../../docs/archive/phase-1-delta-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-1-remaining.md](../../docs/archive/phase-1-remaining.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-book-menu-device-test.md](../../docs/archive/phase-2-book-menu-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-bookmarks-device-test.md](../../docs/archive/phase-2-bookmarks-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-closeout-device-test.md](../../docs/archive/phase-2-closeout-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-closeout-plan.md](../../docs/archive/phase-2-closeout-plan.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-closeout.md](../../docs/archive/phase-2-closeout.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-finished-threshold-device-test.md](../../docs/archive/phase-2-finished-threshold-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-gaps.md](../../docs/archive/phase-2-gaps.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-plan.md](../../docs/archive/phase-2-plan.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-switching-device-test.md](../../docs/archive/phase-2-switching-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-wave-1-device-test.md](../../docs/archive/phase-2-wave-1-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-wave-2-device-test.md](../../docs/archive/phase-2-wave-2-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-wave-3-device-test.md](../../docs/archive/phase-2-wave-3-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-wave-4-device-test.md](../../docs/archive/phase-2-wave-4-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-wave-5-closeout-device-test.md](../../docs/archive/phase-2-wave-5-closeout-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-2-wave-5-device-test.md](../../docs/archive/phase-2-wave-5-device-test.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-3-plan.md](../../docs/archive/phase-3-plan.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/phase-5-plan.md](../../docs/archive/phase-5-plan.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/archive/roadmap-to-phase-1-close.md](../../docs/archive/roadmap-to-phase-1-close.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/benchmark.md](../../docs/benchmark.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/bugs/restored-paused-freshness.md](../../docs/bugs/restored-paused-freshness.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/bugs/session-sync-crash.md](../../docs/bugs/session-sync-crash.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/bugs/unified-resume-freshness.md](../../docs/bugs/unified-resume-freshness.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/closeout.md](../../docs/closeout.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/comparison-absorb.md](../../docs/comparison-absorb.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/dependency-compatibility-inventory.md](../../docs/dependency-compatibility-inventory.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/dependency-upgrade-plan.md](../../docs/dependency-upgrade-plan.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/device-test-0.9.14.md](../../docs/device-test-0.9.14.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/device-test-issue-75.md](../../docs/device-test-issue-75.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/device-test-sleep-schedule.md](../../docs/device-test-sleep-schedule.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/gaps.md](../../docs/gaps.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/garmin-integration.md](../../docs/garmin-integration.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/handover.md](../../docs/handover.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/latest-stable-upgrade-plan.md](../../docs/latest-stable-upgrade-plan.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/loopbound-embedding.md](../../docs/loopbound-embedding.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/product-decisions.md](../../docs/product-decisions.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/release.md](../../docs/release.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/reviews/2026-08-22-server-android-auto.md](../../docs/reviews/2026-08-22-server-android-auto.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-08-23-product-ui-ux-gap-analysis.md](../../docs/reviews/2026-08-23-product-ui-ux-gap-analysis.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-09-06-realtime-progress-and-resume.md](../../docs/reviews/2026-09-06-realtime-progress-and-resume.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-02-reliability-inventory.md](../../docs/reviews/2026-10-02-reliability-inventory.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-03-ci-timing-baseline.md](../../docs/reviews/2026-10-03-ci-timing-baseline.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-03-issue-128-continuity-review.md](../../docs/reviews/2026-10-03-issue-128-continuity-review.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-03-series-screen-hallmark.md](../../docs/reviews/2026-10-03-series-screen-hallmark.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-03-shared-download-ownership.md](../../docs/reviews/2026-10-03-shared-download-ownership.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-04-benchmark-api36.md](../../docs/reviews/2026-10-04-benchmark-api36.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-04-compact-series-cards.md](../../docs/reviews/2026-10-04-compact-series-cards.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-04-documentation-reconciliation.md](../../docs/reviews/2026-10-04-documentation-reconciliation.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-04-download-restart-progress.md](../../docs/reviews/2026-10-04-download-restart-progress.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-04-generated-baseline-profile.md](../../docs/reviews/2026-10-04-generated-baseline-profile.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-04-security-coverage.md](../../docs/reviews/2026-10-04-security-coverage.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/reviews/2026-10-07-cross-repo-reconciliation.md](../../docs/reviews/2026-10-07-cross-repo-reconciliation.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/risks.md](../../docs/risks.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/roadmap.md](../../docs/roadmap.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/testing.md](../../docs/testing.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/testing/2026-10-03-phone-acceptance.md](../../docs/testing/2026-10-03-phone-acceptance.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/2026-10-03-series-history-sleep.md](../../docs/testing/2026-10-03-series-history-sleep.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/2026-10-04-author-details.md](../../docs/testing/2026-10-04-author-details.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-04-browse-selection-counts.md](../../docs/testing/2026-10-04-browse-selection-counts.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-04-card-blur-sampling.md](../../docs/testing/2026-10-04-card-blur-sampling.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-04-download-verification-cancellation.md](../../docs/testing/2026-10-04-download-verification-cancellation.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-04-phone-2178.md](../../docs/testing/2026-10-04-phone-2178.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/2026-10-04-phone-2179.md](../../docs/testing/2026-10-04-phone-2179.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/2026-10-04-sign-in-back.md](../../docs/testing/2026-10-04-sign-in-back.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-05-author-observation.md](../../docs/testing/2026-10-05-author-observation.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-05-book-row-metadata.md](../../docs/testing/2026-10-05-book-row-metadata.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-05-home-status-recovery.md](../../docs/testing/2026-10-05-home-status-recovery.md) | Historical results retained; dated banner links merged state and later acceptance. |
| [docs/testing/2026-10-05-merge-delivery.md](../../docs/testing/2026-10-05-merge-delivery.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/2026-10-05-native-download-cancellation.md](../../docs/testing/2026-10-05-native-download-cancellation.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/2026-10-05-pr-merge-test-plan.md](../../docs/testing/2026-10-05-pr-merge-test-plan.md) | Historical/dated evidence retained; current roadmap/register override ordering/status. |
| [docs/testing/pr-playback-auth-recovery.md](../../docs/testing/pr-playback-auth-recovery.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/testing/reliability-acceptance.md](../../docs/testing/reliability-acceptance.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [docs/testing/roadmap-verification-register.md](../../docs/testing/roadmap-verification-register.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [docs/testing/ui-roadmap-triage.md](../../docs/testing/ui-roadmap-triage.md) | Current entry/status reconciled; linked source and pending acceptance. |
| [scripts/README-bookwave-launcher-assets.md](../../scripts/README-bookwave-launcher-assets.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |
| [version-control.md](../../version-control.md) | Existing contract/child plan/tool guidance retained; current roadmap governs ordering. |

| Garmin document | Disposition |
| --- | --- |
| [AGENTS.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/AGENTS.md) | Current agreement retained |
| [docs/architecture.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/architecture.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [docs/phone-watch-protocol.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/phone-watch-protocol.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [docs/reconciliation.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/reconciliation.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [docs/testing/phase-1-companion.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/testing/phase-1-companion.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [docs/testing/phase-2-transport.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/testing/phase-2-transport.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [docs/watchshelf-integration.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/docs/watchshelf-integration.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [plan.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/plan.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |
| [README.md](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/blob/main/README.md) | Current contract/status/test plan reconciled; no transport/runtime PASS invented |

## Acceptance retained and verification of this change

Android U-09-01–05 remain NOT RUN; author U-08 rows now record their actual selected2191/2195 results instead of blanket NOT RUN. All remaining P/C/S/D/A/Q/U/PERF configurations remain scoped by the verification register. Garmin G-01-01–11 and G-02-01–10 explicitly log pending physical cases; no hardware test blocks this documentation merge under the owner's policy.

Repository link/encoding/whitespace checks and strict Android verification/PR CI must pass before this documentation change is called complete. Garmin documentation follows its existing five-job verification without claiming Run No Evil execution. Final delivery/merge gates are recorded in the reconciliation PRs; the source/Actions snapshot above is historical and is not overwritten with future hashes. No feature source, dependencies, permissions, schema or endpoint is changed.


### Local documentation verification — 2026-10-07

`ktlintFormat` and `verifyDebug -Pshelfplayer.warningsAsErrors=true` **PASS** on the documentation
branch (1 minute; 1,126 actionable tasks, 13 executed). No source/classpath change was made, so no
forced rerun was required. Garmin `bash tools/ci/guardrails.sh` **PASS**. The changed-document local
link/UTF-8 audit checked343 relative links with zero failures before the historical banners; it is
rerun before commit. Whitespace diff checks pass. These results do not run phone/watch acceptance.
