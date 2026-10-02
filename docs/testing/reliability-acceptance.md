# Reliability acceptance

**Classification:** Acceptance checklist, not a second roadmap.
**Baseline:** main `c6b52b22`; record the exact candidate commit when executing.

## Evidence record

Record APK commit, Android/API version, device/host type and results. Use fixture accounts/media; redact
private titles, usernames, hosts and paths from shared captures. `adb devices` returned no attached device
during the 2026-10-02 inventory. Every physical scenario below remains pending.

## Playback and car

Use `../android-auto-pd001-drive-acceptance.md` for browse/artwork, profiles, Queue, output indicator,
continuity and Previous. Extend it with:

- Idle restore: keep existing book; empty session selects eligible candidate; Never installs metadata only;
  Arm stays paused; ArmAndPlay follows route/lock policy. Cover cached, refreshed, empty and locked states.
- During a queue open, start another book and switch to another unlocked profile in separate cases. No old
  book, title, position, timer or session state may overwrite the new context (PR #205 review).
- Car-only profile switch flushes outgoing progress, stays paused and evicts old dynamic browse content.
- Phone skips, active sleep-timer slot exception, then car connect/disconnect layouts without interruption.
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
