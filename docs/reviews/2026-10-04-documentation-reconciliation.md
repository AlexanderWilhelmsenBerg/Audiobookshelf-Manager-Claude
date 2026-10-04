# Documentation and merge reconciliation — 2026-10-04

**Classification:** Dated repository-wide documentation audit and merge evidence.
**Baseline:** GitHub main `8de931f09aafb2991d4cf0febacf939f8eac492c`, merged through PR #226.
**Requirements:** LIB-001/002/003/004, PLAY-004/008, DL-001/002/003/004, AUTH-002/003;
PRODUCT_SPEC 16/17/18/19/21/24/25. Android remains the active scope.

## Merge disposition

PR #226 met its documentation-only scope, source gates and CI and was merged on 2026-10-04 at 18:39:33 UTC.
The other three open runtime PRs remain drafts with explicit acceptance gaps. PR #230 has source guards,
39 targeted tests and selected APK2181 browse checks, but actual TalkBack and the remaining live/configuration
cases are open. PR #231 requires alternating control/candidate performance and visual comparison; no speedup
is claimed. PR #232 has 87 downloads/52 caller tests and ordinary APK2184 recovery/file-integrity evidence,
but controlled verifier/response/race/storage/credential/notification/accessibility cases remain.

The stack is main → #230 → #231 or #232. Green CI does not remove the recorded acceptance gates.
No functional issue was closed or acceptance waived in this reconciliation. The snapshot remains 46 open
tickets (42 active Android, three excluded iOS, parked Garmin #119), 23 closed tickets including one
accidental ticket, and therefore 22 substantive closures. This is a dated inventory; GitHub owns live counts.

## Corrected documentation

- README now agrees with BuildIdentity and the version catalog: product 0.10.6.1, KSP2.3.12,
  Benchmark1.5.0, Room2.8.5, DataStore1.2.1, protobuf4.36.1, Activity1.13.0, Retrofit3.0.0,
  OkHttp5.4.0, serialization1.9.0, coroutines1.11.0 and ktlint1.8.0.
- Build architecture agrees with KSP2.3.12 and ktlint1.8.0/plugin14.2.0. Testing agrees with Room schema21.
  The old 0.9.14 device checklist is historical; the verification register owns current cases.
- Architecture no longer describes implemented resume freshness, remembered identity or ADR-0029 as future
  or pending. Historical Forgejo route issue #11 is explicitly mapped to GitHub #100. Portability and cache
  experiments are absent from the active implementation scope.
- PRODUCT_SPEC identifies accepted ADR/product refinements of the older completion, locking and shared-copy
  wording. Its physical-copy open decision is resolved by ADR-0018/PD-004. Baseline/history remain preserved.
- Roadmap, risk, compatibility, release, product-decision, UI triage and case-register entries distinguish main
  from drafts. Dated browse/download/performance reports and fixture/numeric evidence are imported without
  runtime, endpoint, response-contract, dependency, permission, schema or CI changes.
- Release notes distinguish superseded uninstalled main2180, tested draft2181/2184 and merged-main2185.
  Privacy describes the present product version and existing optional local Loopbound/save boundary.
- Historical phase plans, reviews, bug reports and version-specific procedures now declare their scope and
  point to the canonical roadmap. Original measurements, failures and NOT RUN entries are retained.

## Verification and main delivery

- `ktlintFormat` then `verifyDebug -Pshelfplayer.warningsAsErrors=true` PASS, 28s, 1,122 tasks
  (13 executed, 1,109 up-to-date). No classpath change; no forced rerun is required for this docs/evidence slice.
- All tracked Markdown plus the imported reports are read in the audit. All relative Markdown links resolve;
  README dependency rows match the parsed catalog and product identity matches BuildIdentity. Modified scope
  contains only Markdown and existing fixture PNG/numeric JSON evidence; `git diff --check` passes.
- Merged #226/main `8de931f0`: [Standard/cache-seed verification](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37225280801)
  and [main release/security](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37225280750) PASS.
- Requested [trusted signed debug APK](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37225320585) PASS:
  0.10.6.1/code2185, source `8de931f09aafb2991d4cf0febacf939f8eac492c`, artifact11311054044.
  APK/artifact SHA-256 `62c991b83df5805d0799ddf5ab239103038091f272f347a43d9cccfa31de0228` matches.
  Package `org.homebord.bookwave.debug`; embedded source verified; signer SHA-256
  `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c` matches owner-compatible deliveries;
  Loopbound `7e24529b2a9e419218d2a1423d832b1bf587c8ef` verified. APK2185 is not phone-installed.
- This docs-only follow-up must pass its exact-head PR gate before merge. Its final merge runs the ordinary
  main gates and requested signed APK producer; use the live [main runs](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/workflows/main.yml)
  and [APK runs](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/workflows/apk.yml)
  for later delivery identities rather than treating this dated snapshot as a floating latest-build ledger.

## Physical acceptance carried forward

No app-function or connected test was rerun for this documentation-only reconciliation. The phone remains on
tested draft APK2184; the newer main artifact lacks draft fixes. A-08/Q-01 (in-place upgrade/data retention,
About identity and affected smoke) remain obligations for any new installed candidate. Prior 27-case connected
datastore results and all physical observations retain their exact recorded source scope. All outstanding
car/headset/sensor/power-loss/two-hour/server/storage/accessibility cases remain in the verification register
and linked reports. No hardware absence is turned into a pass. No owner workspace WIP, phone or server data
was changed by this documentation audit.

## Inventory and authority

This is an authority/link/source reconciliation, not a fresh upstream-release, legal/privacy certification or
repeat of historical device experiments. Dated external-version checks, captured-server versions and recorded
device outcomes keep their dates. Current facts were compared with repository/GitHub source; historical claims
remain evidence for their recorded snapshot. The index and roadmap identify the applicable current authority.

Active Android issue snapshot: #99, #100, #101, #108, #109, #110, #112, #114, #116, #117, #118, #120, #124, #126, #128, #130, #134, #175, #176, #177, #178, #179, #180, #181, #182, #183, #184, #185, #186, #187, #188, #189, #190, #191, #192, #193, #194, #195, #196, #227, #228, #229.

| Document | Authority / interpretation |
| --- | --- |
| [.github/pull_request_template.md](../../.github/pull_request_template.md) | Historical survey/review/investigation; roadmap owns present status |
| [.github/workflow-archive/README.md](../../.github/workflow-archive/README.md) | Historical survey/review/investigation; roadmap owns present status |
| [AGENTS.md](../../AGENTS.md) | Current contract/plan/ledger or dated change history |
| [CHANGELOG.md](../../CHANGELOG.md) | Current contract/plan/ledger or dated change history |
| [CLAUDE.md](../../CLAUDE.md) | Current contract/plan/ledger or dated change history |
| [CONTRIBUTING.md](../../CONTRIBUTING.md) | Current contract/plan/ledger or dated change history |
| [PRIVACY.md](../../PRIVACY.md) | Current contract/plan/ledger or dated change history |
| [PRODUCT_SPEC.md](../../PRODUCT_SPEC.md) | Current contract/plan/ledger or dated change history |
| [README.md](../../README.md) | Current contract/plan/ledger or dated change history |
| [SECURITY.md](../../SECURITY.md) | Current contract/plan/ledger or dated change history |
| [docs/README.md](../README.md) | Current contract/plan/ledger or dated change history |
| [docs/adr/0001-record-architecture-decisions.md](../adr/0001-record-architecture-decisions.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0002-module-structure.md](../adr/0002-module-structure.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0003-result-and-error-model.md](../adr/0003-result-and-error-model.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0004-redacted-structured-logging.md](../adr/0004-redacted-structured-logging.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0005-fake-gateway-and-fixtures.md](../adr/0005-fake-gateway-and-fixtures.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0006-dependency-locking-and-verification.md](../adr/0006-dependency-locking-and-verification.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0007-contract-capture-and-licensing.md](../adr/0007-contract-capture-and-licensing.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0008-audiobooth-as-an-api-reference.md](../adr/0008-audiobooth-as-an-api-reference.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0009-cleartext-in-debug-only.md](../adr/0009-cleartext-in-debug-only.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0010-dependency-locking-deferred.md](../adr/0010-dependency-locking-deferred.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0011-stay-on-api-36-until-detekt-supports-agp-9.md](../adr/0011-stay-on-api-36-until-detekt-supports-agp-9.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0012-the-official-app-as-an-api-reference.md](../adr/0012-the-official-app-as-an-api-reference.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0013-finished-is-time-remaining-not-a-percentage.md](../adr/0013-finished-is-time-remaining-not-a-percentage.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0014-shake-restarts-the-sleep-timer-and-the-timer-is-logged.md](../adr/0014-shake-restarts-the-sleep-timer-and-the-timer-is-logged.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0015-skip-defaults-to-thirty-seconds-both-ways.md](../adr/0015-skip-defaults-to-thirty-seconds-both-ways.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0016-a-book-is-one-timeline-window.md](../adr/0016-a-book-is-one-timeline-window.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0017-smart-download-drives-the-series-queue.md](../adr/0017-smart-download-drives-the-series-queue.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0018-phase-3-downloads-as-the-owner-specified-them.md](../adr/0018-phase-3-downloads-as-the-owner-specified-them.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0019-bookwave-keeps-shelfplayers-application-id.md](../adr/0019-bookwave-keeps-shelfplayers-application-id.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0020-downloads-choose-a-volume-not-a-folder.md](../adr/0020-downloads-choose-a-volume-not-a-folder.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0021-source-file-deletion-ships-no-feature.md](../adr/0021-source-file-deletion-ships-no-feature.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0022-the-language-setting-lives-in-the-composition.md](../adr/0022-the-language-setting-lives-in-the-composition.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0023-the-profile-passcode-is-a-curtain-not-a-vault.md](../adr/0023-the-profile-passcode-is-a-curtain-not-a-vault.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0024-the-four-release-decisions.md](../adr/0024-the-four-release-decisions.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0025-the-grid-in-the-performance-target-does-not-exist.md](../adr/0025-the-grid-in-the-performance-target-does-not-exist.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0026-the-closeout-decisions.md](../adr/0026-the-closeout-decisions.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0027-the-output-chooser-asks-rather-than-commands.md](../adr/0027-the-output-chooser-asks-rather-than-commands.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0028-admin-authority-is-the-session-not-a-second-password.md](../adr/0028-admin-authority-is-the-session-not-a-second-password.md) | ADR: use recorded accepted/superseded status |
| [docs/adr/0029-android-auto-is-a-stable-audiobook-surface.md](../adr/0029-android-auto-is-a-stable-audiobook-surface.md) | ADR: use recorded accepted/superseded status |
| [docs/android-auto-browse-invalidation-acceptance.md](../android-auto-browse-invalidation-acceptance.md) | Current contract/plan/ledger or dated change history |
| [docs/android-auto-pd001-drive-acceptance.md](../android-auto-pd001-drive-acceptance.md) | Current contract/plan/ledger or dated change history |
| [docs/android-auto-player-opportunities.md](../android-auto-player-opportunities.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/api-compatibility.md](../api-compatibility.md) | Current contract/plan/ledger or dated change history |
| [docs/architecture/build.md](../architecture/build.md) | Current contract/plan/ledger or dated change history |
| [docs/architecture/module-boundaries.md](../architecture/module-boundaries.md) | Current contract/plan/ledger or dated change history |
| [docs/architecture/overview.md](../architecture/overview.md) | Current contract/plan/ledger or dated change history |
| [docs/architecture/playback.md](../architecture/playback.md) | Current contract/plan/ledger or dated change history |
| [docs/archive/README.md](../archive/README.md) | Historical phase evidence |
| [docs/archive/phase-1-acceptance.md](../archive/phase-1-acceptance.md) | Historical phase evidence |
| [docs/archive/phase-1-delta-test-0.1.10.md](../archive/phase-1-delta-test-0.1.10.md) | Historical phase evidence |
| [docs/archive/phase-1-delta-test-0.1.5.md](../archive/phase-1-delta-test-0.1.5.md) | Historical phase evidence |
| [docs/archive/phase-1-delta-test-0.1.6.md](../archive/phase-1-delta-test-0.1.6.md) | Historical phase evidence |
| [docs/archive/phase-1-delta-test-0.1.9.md](../archive/phase-1-delta-test-0.1.9.md) | Historical phase evidence |
| [docs/archive/phase-1-delta-test.md](../archive/phase-1-delta-test.md) | Historical phase evidence |
| [docs/archive/phase-1-remaining.md](../archive/phase-1-remaining.md) | Historical phase evidence |
| [docs/archive/phase-2-book-menu-device-test.md](../archive/phase-2-book-menu-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-bookmarks-device-test.md](../archive/phase-2-bookmarks-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-closeout-device-test.md](../archive/phase-2-closeout-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-closeout-plan.md](../archive/phase-2-closeout-plan.md) | Historical phase evidence |
| [docs/archive/phase-2-closeout.md](../archive/phase-2-closeout.md) | Historical phase evidence |
| [docs/archive/phase-2-finished-threshold-device-test.md](../archive/phase-2-finished-threshold-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-gaps.md](../archive/phase-2-gaps.md) | Historical phase evidence |
| [docs/archive/phase-2-plan.md](../archive/phase-2-plan.md) | Historical phase evidence |
| [docs/archive/phase-2-switching-device-test.md](../archive/phase-2-switching-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-wave-1-device-test.md](../archive/phase-2-wave-1-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-wave-2-device-test.md](../archive/phase-2-wave-2-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-wave-3-device-test.md](../archive/phase-2-wave-3-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-wave-4-device-test.md](../archive/phase-2-wave-4-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-wave-5-closeout-device-test.md](../archive/phase-2-wave-5-closeout-device-test.md) | Historical phase evidence |
| [docs/archive/phase-2-wave-5-device-test.md](../archive/phase-2-wave-5-device-test.md) | Historical phase evidence |
| [docs/archive/phase-3-plan.md](../archive/phase-3-plan.md) | Historical phase evidence |
| [docs/archive/phase-5-plan.md](../archive/phase-5-plan.md) | Historical phase evidence |
| [docs/archive/roadmap-to-phase-1-close.md](../archive/roadmap-to-phase-1-close.md) | Historical phase evidence |
| [docs/benchmark.md](../benchmark.md) | Current contract/plan/ledger or dated change history |
| [docs/bugs/restored-paused-freshness.md](../bugs/restored-paused-freshness.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/bugs/session-sync-crash.md](../bugs/session-sync-crash.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/bugs/unified-resume-freshness.md](../bugs/unified-resume-freshness.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/closeout.md](../closeout.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/comparison-absorb.md](../comparison-absorb.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/dependency-compatibility-inventory.md](../dependency-compatibility-inventory.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/dependency-upgrade-plan.md](../dependency-upgrade-plan.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/device-test-0.9.14.md](../device-test-0.9.14.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/device-test-issue-75.md](../device-test-issue-75.md) | Current contract/plan/ledger or dated change history |
| [docs/device-test-sleep-schedule.md](../device-test-sleep-schedule.md) | Current contract/plan/ledger or dated change history |
| [docs/gaps.md](../gaps.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/handover.md](../handover.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/latest-stable-upgrade-plan.md](../latest-stable-upgrade-plan.md) | Current contract/plan/ledger or dated change history |
| [docs/loopbound-embedding.md](../loopbound-embedding.md) | Current contract/plan/ledger or dated change history |
| [docs/product-decisions.md](../product-decisions.md) | Current contract/plan/ledger or dated change history |
| [docs/release.md](../release.md) | Current contract/plan/ledger or dated change history |
| [docs/reviews/2026-08-22-server-android-auto.md](2026-08-22-server-android-auto.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-08-23-product-ui-ux-gap-analysis.md](2026-08-23-product-ui-ux-gap-analysis.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-09-06-realtime-progress-and-resume.md](2026-09-06-realtime-progress-and-resume.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-02-reliability-inventory.md](2026-10-02-reliability-inventory.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-03-ci-timing-baseline.md](2026-10-03-ci-timing-baseline.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-03-issue-128-continuity-review.md](2026-10-03-issue-128-continuity-review.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-03-series-screen-hallmark.md](2026-10-03-series-screen-hallmark.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-03-shared-download-ownership.md](2026-10-03-shared-download-ownership.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-04-benchmark-api36.md](2026-10-04-benchmark-api36.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-04-compact-series-cards.md](2026-10-04-compact-series-cards.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-04-documentation-reconciliation.md](2026-10-04-documentation-reconciliation.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-04-download-restart-progress.md](2026-10-04-download-restart-progress.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-04-generated-baseline-profile.md](2026-10-04-generated-baseline-profile.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/reviews/2026-10-04-security-coverage.md](2026-10-04-security-coverage.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/risks.md](../risks.md) | Current contract/plan/ledger or dated change history |
| [docs/roadmap.md](../roadmap.md) | Current contract/plan/ledger or dated change history |
| [docs/testing.md](../testing.md) | Current contract/plan/ledger or dated change history |
| [docs/testing/2026-10-03-phone-acceptance.md](../testing/2026-10-03-phone-acceptance.md) | Dated source/APK execution evidence |
| [docs/testing/2026-10-03-series-history-sleep.md](../testing/2026-10-03-series-history-sleep.md) | Dated source/APK execution evidence |
| [docs/testing/2026-10-04-browse-selection-counts.md](../testing/2026-10-04-browse-selection-counts.md) | Dated source/APK execution evidence |
| [docs/testing/2026-10-04-card-blur-sampling.md](../testing/2026-10-04-card-blur-sampling.md) | Dated source/APK execution evidence |
| [docs/testing/2026-10-04-download-verification-cancellation.md](../testing/2026-10-04-download-verification-cancellation.md) | Dated source/APK execution evidence |
| [docs/testing/2026-10-04-phone-2178.md](../testing/2026-10-04-phone-2178.md) | Dated source/APK execution evidence |
| [docs/testing/2026-10-04-phone-2179.md](../testing/2026-10-04-phone-2179.md) | Dated source/APK execution evidence |
| [docs/testing/pr-playback-auth-recovery.md](../testing/pr-playback-auth-recovery.md) | Historical survey/review/investigation; roadmap owns present status |
| [docs/testing/reliability-acceptance.md](../testing/reliability-acceptance.md) | Current contract/plan/ledger or dated change history |
| [docs/testing/roadmap-verification-register.md](../testing/roadmap-verification-register.md) | Current contract/plan/ledger or dated change history |
| [docs/testing/ui-roadmap-triage.md](../testing/ui-roadmap-triage.md) | Current contract/plan/ledger or dated change history |
| [scripts/README-bookwave-launcher-assets.md](../../scripts/README-bookwave-launcher-assets.md) | Historical survey/review/investigation; roadmap owns present status |
| [version-control.md](../../version-control.md) | Current contract/plan/ledger or dated change history |
