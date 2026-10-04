# Reliability acceptance

**Classification:** Acceptance checklist, not a second roadmap.
**Baseline reconciled:** main `8de931f0` includes #211–#225 and planning #226.
Draft PR #232 adds cancellation/percent corrections that are not on main; use its dated report for APK2184 subcases.
Record the exact candidate APK commit when executing.

## Evidence record

Record APK commit, Android/API version, device/host type and results. Use fixture accounts/media; redact
private titles, usernames, hosts and paths from shared captures. `adb devices` returned no attached device
during the 2026-10-02 inventory. The owner supplied an API-36 phone on 2026-10-03; its
[dated execution](2026-10-03-phone-acceptance.md) records passing instrumented/manual subcases, failed
benchmark attempts and pending matrices. No entire playback/car/download lane is accepted from that subset.
Log each remaining runbook step in the
[verification register](roadmap-verification-register.md), including unavailable configurations and the
historical #36 / GitHub #128 headset-continuity follow-up.

## Playback and car

Use `../android-auto-pd001-drive-acceptance.md` for browse/artwork, profiles, Queue, output indicator,
continuity and Previous. Extend it with:

- Idle restore: keep existing book; empty session selects eligible candidate; Never installs metadata only;
  Arm stays paused; ArmAndPlay follows route/lock policy. Cover cached, refreshed, empty and locked states.
- During a queue open, start another book and switch to another unlocked profile in separate cases. No old
  book, title, position, timer or session state may overwrite the new context (PR #205 review).
- Switch A → B → A while a holder, candidate or queue is resolving; returning to A must not authorize the
  old request. Repeat with a newer Pause/Stop and with local session storage delayed.
- Car-only profile switch flushes outgoing progress, stays paused and evicts old dynamic browse content.
- Phone skips, active sleep-timer slot exception, then car connect/disconnect layouts without interruption.
- Inside the nightly window, car connection prevents a new automatic timer while active/manual timers keep
  running. Cross the window start while connected, then disconnect during active playback: one ordinary
  timer starts. Disconnect while paused: no audio or timer starts. A manually cancelled occurrence remains
  suppressed after the round trip. Cover an Android Auto controller rebind while projection stays connected.
- Headset heard → explicit speaker → car arrival never resurrects the headset.
- Previous/Rewind/Fast-forward use configured skips rather than whole-book restart (PR #204).
- Process death loses at most ten seconds of progress; complete the two-hour playback soak.

## Downloads

| Scenario | Expected evidence | Issues |
| --- | --- | --- |
| Queue Wi-Fi-only while offline/metered, then restore Wi-Fi | Waiting → Running/Complete automatically, also after process restart. | #109 |
| Retryable network/server failure | Retrying during backoff; terminal failure offers Retry, never Pause. | #108/#109 |
| Pause, restart, Resume | Paused persists, partial bytes reused, permission/free-space checks run. | #108 |
| Cancel discard dialog, then confirm for paused partial copy | Cancel changes nothing; confirm removes only partial data, keeps committed media. | #112 |
| Two active downloads plus stored book | Independent progress, separate stored section, coherent notifications; tap opens Downloads. | #120 |
| Notifications denied | Downloads remains usable and truthful. | #120 |
| Download to card, remove while stopped, reopen | Storage unavailable, not corrupt; preserve bytes/manifest; disclose new-download fallback. | #110 |
| Reinsert intact card | Reverify without redownload; preserve selected-card preference. | #110 |
| A/B claim same copy; A removes, then B removes | Retain bytes after A; delete after final claim; coherent device pin. | #111 |
| Original transfer owner leaves, expires or loses permission while B still claims | Current eligible same-server claimant continues at the next request boundary; no active-profile fallback, blind account retry or relaxed network constraints. Run every DEV-DL-11 case in the [review](../reviews/2026-10-03-shared-download-ownership.md). | R-122, DL-001/003 |
| Hidden stored copy, including matching item ID on another server | Generic row; no hidden title, author or failure detail. | #111, section 5.2 |
| Upgrade existing downloads | Preserve schema/data; unknown legacy volume ownership stays conservative. | #110/#111 |

## Automated evidence

Run focused presentation, execution mapping, discard, verification and ownership tests before the full gate.
For fixes, record failure without implementation and success with it. Inspect production callers and bindings.

Run `ktlintFormat`, then `verifyDebug -Pshelfplayer.warningsAsErrors=true` (quote the `-P` argument in
PowerShell). Add `--rerun-tasks` for classpath changes. The datastore connected tier requires a device and does
not replace these manual scenarios. Keep unavailable and failed checks explicit.

### Local regression evidence — 2026-10-02

- Download identity: 28 ViewModel tests ran against the original join; the new cross-server case failed.
  With the server-and-item join, all 28 passed. `DownloadsScreen` collects that ViewModel state directly.
- Windows build prerequisite: protoc 4.36.1 lacked a Windows verification entry. SHA-256 of the cached binary
  matched an independent Maven Central download; the narrow checksum addition retains strict verification.
- Appearance fixture: the first full gate exposed five failing cases; the isolated seven-test run reproduced
  all five. Stable file identity alone did not fix them. Surfacing the suppressed storage error identified
  Android DataStore's rename failure on a plain Windows JVM. Robolectric API 34, matching the existing
  repository test environment, made all seven pass with unchanged assertions. The store scope is cancelled
  after each test and storage errors now fail the fixture explicitly.
- Full gate: `verifyDebug '-Pshelfplayer.warningsAsErrors=true' --rerun-tasks --max-workers=4`
  passed in 7m 22s, with all 1,184 actionable tasks executed. Formatter passed. Gradle reported
  deprecations and Android Lint's external-module configuration warnings; neither failed the gate.
- Device, projected Android Auto, removable storage and soak: not run; no device attached.

Local logs are under ignored `build/reliability-evidence/`; the full-gate log is `build/reliability-verify.log`.

### PR #205 merge follow-up — 2026-10-02

- Integrated main `756d521e` while preserving the download privacy fix and Windows test/build corrections.
- Three new tests failed on the merged restorer: unlocked-profile switch during held metadata resolution,
  candidate resolution and queue opening. Each uses deferred gates to suspend the operation before switching.
- The fix captures the requesting profile and checks it before lookup and after candidate/queue resolution.
  The service supplies the identity through AutoLibrary's existing profile repository. A missing-profile
  test rejects all restore modes without lookup. `IdleResumeWiringTest` checks the service binding.
- Focused verification: all 16 restorer tests and four service-wiring tests passed. The forced full rebuild
  found Detekt's return-count limit; moving the common entry guard into `restore` corrected that finding.
  Final `ktlintFormat verifyDebug '-Pshelfplayer.warningsAsErrors=true' --max-workers=4` passed in 3m 47s
  (129 tasks executed, 1,155 up-to-date after the forced rebuild). Logs: `car-profile-before.log`, `car-profile-after.log`,
  `car-profile-verify.log` and `car-profile-verify-final.log` under `build/reliability-evidence/`.
- This does not retire R-115's queue-opening bookkeeping or away-and-back profile generation concerns.
  No hardware acceptance or issue closure is claimed.

### Restore generation and book acceptance — 2026-10-03

- The three A → B → A restorer cases and three selection-token cases failed before the generation guard.
  Two session-acceptance cases failed when acceptance delegated to the unguarded session-opening path.
- A real BookChanges/ExoPlayer regression failed when its guarded acceptance was replaced with the old
  book-opening sequence. With the guard, rejected durable opening preserves the running timer, baseline
  and prior live session. Accepted publication clears the old timer before installation. Cancellation of
  local opening and newer transport intent are also covered. A final identity lookup suspended while its
  profile locked reproduced an install; checking the lock after that lookup rejects it.
- The captured-profile outbox test failed when storage looked up the current selection instead; the
  profile-scoped implementation passes. No endpoint or schema change was needed.
- A prepared server session or zero-listening local row can remain after later supersession; it is bound
  to the captured owner and never becomes live playback. An outgoing close already sent before later
  supersession cannot be undone. Physical Android Auto/profile and soak acceptance remains pending.
- Forced `ktlintFormat verifyDebug '-Pshelfplayer.warningsAsErrors=true' --rerun-tasks --max-workers=4`
  passed in 5m 13s with all 1,119 tasks executed. After the final lock-race correction, the full gate passed
  again in 4m 24s (83 executed, 1,036 up-to-date), including all 21 restorer cases.
- Logs are under ignored `build/car-evidence/`. Record the candidate commit before device use.

### Book download execution wiring — 2026-10-03

- Five `BookViewModelDownloadTest` scenarios cross the actual ViewModel/observer/repository/use-case boundary:
  a Failed manifest being retried, live percent capped at 99 until durable completion, missing execution
  fallback, Pause written before cancellation, shared-claim refusal and profile/server changes.
- Removing the ViewModel's execution projection made all five fail. Restoring the unchanged production
  wiring made all five pass. `BookScreen` collects `menu`, renders `menu.download` and routes the prompt's
  Pause to `onPauseDownload`; the test does not substitute the pure button policy for that ViewModel path.
- R-124's automated gap is closed. R-119's TalkBack, large-font, process restart and shared-copy device
  acceptance remains pending. Logs are under ignored `build/book-evidence/`.
- `ktlintFormat verifyDebug '-Pshelfplayer.warningsAsErrors=true' --max-workers=4` passed in 2m 33s
  (1,119 tasks). The full app suite includes these five scenarios; no production behavior changed.

### Nightly timer during car connection — 2026-10-03

- Seven new controller scenarios cover automatic suppression, the window start and disconnect, preservation
  of a running automatic timer, explicit manual creation, durable manual cancellation, and car arrival during
  suspended history/runtime persistence. Four cases failed with the connection guard removed; all 38
  controller tests passed with it. The service wiring test covers controller and projection lifecycle calls.
- `ktlintFormat` and final `verifyDebug '-Pshelfplayer.warningsAsErrors=true' --max-workers=4` passed in
  4m (1,184 tasks). Detekt findings in the initial runs were corrected by extracting timer attachment and a
  named ownership check. The app test sandbox uses the same startup-isolation fix as PR #214.
- Phone/system layout, actual Android Auto projection and continuity remain pending; no device was attached.
  Logs are under ignored `build/sleep-evidence/` (`car-before.log`, `car-after.log`, `verify-complete.log`).

### Combined CI/reliability candidate — 2026-10-03

- Candidate `c62368335ef2b831e74892e3399199e4d8078307` combines #211–#216. The forced
  `ktlintFormat verifyDebug '-Pshelfplayer.warningsAsErrors=true' --rerun-tasks --max-workers=4`
  gate passed in 7m 21s with all 1,119 tasks executed. App 521, playback 511, datastore 30 and library 129
  debug tests passed without failures or errors. All 16 CI policy tests, Actionlint and Bash syntax passed.
- Subsequent integration with main preserved the code, tests and workflow tree; differences are additive
  acceptance evidence, the roadmap and CI timing documentation. Each PR still requires green checks on its
  current head; observe the final main seed/release workflows after merging.
- No device was attached. Notification, lock-screen, TalkBack/large text, Android Auto/headset, removable
  storage, download process restart/shared-copy and two-hour soak acceptance remain pending. Record the
  final merged APK commit and device/host versions before those checks; do not close their issues from this
  automated evidence. Remote-cache experimentation is outside the active Android scope.

### Historical #36 / GitHub #128 verification handoff — 2026-10-03

- The actual monitor/provider/broadcast-to-service regression failed three of five cases on main
  `3e699786`; retaining the existing positive lifecycle latch passed all five, and the broader 68-case
  continuity suite passed. Confirmed exit cleans up once; Unknown alone neither departs nor reconnects.
- Integration source `297965b3` passed the forced formatter/full warnings-as-errors gate in 6m 59s with all
  1,119 tasks executed. App 521 and playback 516 debug tests passed without failures/errors. Initial
  fixture lint and isolated local SDK-path/cache findings were corrected; failed logs remain available.
- The [focused review](../reviews/2026-10-03-issue-128-continuity-review.md) records eight automated-check
  entries (the first now passed) and 26 granular phone/Auto cases; the [register](roadmap-verification-register.md)
  logs the wider functional/release checks and exact APK handoff. The later phone pass did not exercise
  heard-headset/Auto continuity or departure. Keep #128 open for the relevant headset/host evidence.

### Shared-transfer credential ownership — 2026-10-03

- R-122 was reproduced with four failures in the 15-case real downloader/Room/filesystem fixture. The
  correction passes all 17 downloader cases, all 16 access-policy cases and all 77 download-module cases.
  Current same-server claims, authentication/grants and catalogue/file visibility authorize each request;
  no active-profile fallback or WorkManager/network-constraint rewrite occurs.
- Validated partial resume and safe no-ETag restart retain the committed first file. Policy coverage is
  38/38 lines and 40/42 branches. Initial loop/nesting/test-shadow analysis findings were corrected; final
  forced formatter/`verifyDebug` warnings-as-errors passed in 7m 30s, all 1,119 tasks executed, 2,133 tests,
  zero failures/errors. All failed and passed logs are retained under ignored `build/shared-download-evidence/`.
- The [review](../reviews/2026-10-03-shared-download-ownership.md) records every automated case, 12 granular
  phone cases and the next R-123 second-cancellation fixture. Device/server authentication, process restart,
  notifications, metering, card storage and audible playback remain NOT RUN; no hardware issue is closed.

### First supplied-phone execution — 2026-10-03

- Installed debug 0.10.6.1 (2175) is byte-identical to the trusted main `8beec05c` artifact; About matches.
  The owner had already installed it, so prior-build upgrade/preservation is not accepted from this run.
- All 27 Keystore/passcode instrumented tests passed through an isolated test package after a pre-existing
  test-package signer conflict. Whole-app security/lifecycle acceptance is a different scope.
- Local offline Resume worked with no active default network. Force-stop/reopen stayed silent; explicit
  Resume was 1.306 seconds ahead of the pre-kill sample, with no observed progress loss in this one run.
  Mini/full and notification entry, configured system skips, manual timer expiry/extend/cancel, rotation
  and 200% player text were sampled. Timer cancellation restored the Samsung media-card book title.
- Five macrobenchmark cases failed because the pinned harness cannot discover this phone's truncated
  `pgrep` process names. No startup, frame, memory or baseline-profile metric is accepted.
- A warm Starting observation cleared after cold launch; landscape artwork/text needs contrast review.
  These are scoped follow-ups, not diagnosed causes. The [case report](2026-10-03-phone-acceptance.md)
  retains UTC/evidence and every remaining register group, including physical car/headset, controlled
  transfer/account/server, TalkBack and two-hour soak. Networking/display settings were restored and
  playback was left paused with the timer off.

### Restart cancellation and durable download progress — 2026-10-04

AUTO-DL-12-01 reproduced three failures: no-ETag restart and declined-range replacement retained 1,000
manifest bytes after writing 300, and immediate post-truncation cancellation retained 1,000 after zero.
The correction passes all 27 file-downloader and 82 download-module cases, including unavailable-owner
and pre-sink preservation. Formatter/full verifyDebug warnings-as-errors passed in 2m 31s (1,119 tasks).
Initial test-fixture compilation and coroutine-style findings were corrected, with failed logs retained.
[Six granular physical checks](../reviews/2026-10-04-download-restart-progress.md) remain NOT RUN at the
owner's request. Controlled server, screen/notification, cold restart, removable storage and audible
continuity acceptance remain pending; no broader issue is closed from these automated results.
