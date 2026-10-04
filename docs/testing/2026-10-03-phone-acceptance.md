# Phone acceptance — 2026-10-03

**Classification:** Dated evidence snapshot; original source/build findings and results are retained.
Use [the current roadmap](../roadmap.md) for present delivery state and sequencing.

**Owner:** Test & Acceptance. **Source:** main `8beec05ca33b786bb7e6d53a42fbe7bfdde7dd2e` (#218).
**Scope:** first physical pass on the supplied phone. Requirements AUTH-002/003, PLAY-001/004/007/008,
DL-006 and PRODUCT_SPEC 17.2/17.3/21. The [verification register](roadmap-verification-register.md)
retains the complete inventory; only the subcases explicitly recorded here have execution evidence.

## Identity and setup

| Field | Recorded value |
| --- | --- |
| Phone | Samsung SM-S928B, Android 16 / API 36, security patch 2026-08-05; authorized active Android user |
| Display / locale | 1080 × 2340 physical pixels, density 450 (384 dp portrait width); Norwegian UI; Europe/Oslo (UTC+02:00) |
| Settings before and after | Font scale 1.0, auto-rotation enabled, user rotation 0; Wi-Fi and mobile data enabled |
| App | `org.homebord.bookwave.debug`, 0.10.6.1 (2175), minimum API 26, target API 36 |
| About observed | `0.10.6.1 (2175)` and `debug · 8beec05ca33b` |
| Installed APK SHA-256 | `ac74732a769a1c8d66ba6c84062b450599d15152f4b487728a619d29d046764a` |
| Trusted signer SHA-256 | `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c` |
| WebView | `com.google.android.webview` 153.0.8010.36 |
| Loopbound bundle source | `7e24529b2a9e419218d2a1423d832b1bf587c8ef` |
| Host tools | Windows, JDK 21.0.12.1, repository Gradle wrapper and Android SDK; environment checker passed required tools, Docker unavailable |
| External equipment | No exercised headset, DHU, projected Auto or Automotive host; a Bluetooth availability icon is not heard-route evidence |

The pulled installed APK is byte-for-byte identical to the trusted delivery. Signing verification and
bundle staging belong to [Build APK 37133452148](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37133452148),
[artifact 11277344292](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37133452148/artifacts/11277344292).
The same SHA passed [main debug/cache verification](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37133450350)
and [release/security](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37133450356);
packaging reused those checks. Its preceding local forced gate passed all 1,119 tasks and 2,133 JVM/Robolectric
tests in 7m 30s, with zero failures/errors. This report does not transfer physical acceptance to another APK.

The owner had already installed this build. No upgrade was performed or old-build baseline captured in
this session. Existing profile/library state and two stored copies were present. The tested complete
local copy is called **Book A** below. Playback advanced its listening progress. No account, credential,
profile, stored-copy ownership or management write was deliberately changed.

Raw screenshots/XML can contain private media and unrelated system overlays. They remain in ignored
`build/phone-acceptance-evidence/`, are not PR attachments, and are not shared diagnostic exports. Shared
session JSON contains only UTC, playback state, position and speed. Device serials, hosts, accounts and
media metadata are omitted here. Evidence names below are relative to that private directory.

## Instrumented tier — A-06

The ordinary `:core:datastore:connectedDebugAndroidTest` failed during installation, before running a
test: a pre-existing `com.example.shelfplayer.core.datastore.test` had an incompatible signer. A removal
scoped to the active Android user found no installation there. No all-user uninstall or other-user data
access was performed.

An ignored init script set only `testApplicationId` to
`com.example.shelfplayer.core.datastore.phoneacceptance20261003.test`. The test APK self-instruments that
isolated package, uses the repository's unchanged code/tests, and does not use BookWave's user data.
Its target SDK is 26; the actual phone is API 36 and the BookWave app targets 36. This proves the listed
Keystore/filesystem/passcode code paths, not the complete app's profile/lifecycle/controller behavior.

```powershell
.\gradlew.bat :core:datastore:connectedDebugAndroidTest `
  --init-script build/phone-acceptance-evidence/isolated-test-package.gradle `
  '-Pshelfplayer.warningsAsErrors=true' --max-workers=4
```

**PASS: 27 tests, zero failures/errors/skips.** Gradle completed in 1m 5s (74 tasks; five executed).
JUnit report UTC is `2026-10-03T16:48:53`, aggregate test time 50.424s. The runner did not emit separate
UTC start/end times per test; the durations below are its actual per-case values, not invented timestamps.
Logs: `datastore-connected.log` (failed install), `datastore-isolated-connected.log` (pass). JUnit source:
`core/datastore/build/outputs/androidTest-results/connected/debug/TEST-*.xml`.
The test name states the expected policy; every row completed with no failed assertion.

| Case | Class | Test | Result | Seconds |
| --- | --- | --- | --- | --- |
| A-06-01 | KeystoreLockCipherTest | `clearing_an_absent_key_is_harmless` | PASS | 0.016 |
| A-06-02 | KeystoreLockCipherTest | `a_modified_byte_is_rejected` | PASS | 0.000 |
| A-06-03 | KeystoreLockCipherTest | `wrapped_bytes_round_trip` | PASS | 0.972 |
| A-06-04 | KeystoreLockCipherTest | `a_truncated_record_is_rejected` | PASS | 0.083 |
| A-06-05 | KeystoreLockCipherTest | `an_empty_record_round_trips` | PASS | 0.030 |
| A-06-06 | KeystoreLockCipherTest | `clearing_the_key_makes_existing_records_unreadable` | PASS | 0.233 |
| A-06-07 | KeystoreLockCipherTest | `two_wraps_of_one_plaintext_differ` | PASS | 0.107 |
| A-06-08 | KeystoreLockCipherTest | `a_new_key_is_generated_after_a_clear` | PASS | 0.286 |
| A-06-09 | KeystoreLockCipherTest | `a_modified_iv_is_rejected` | PASS | 0.106 |
| A-06-10 | KeystoreLockCipherTest | `the_ciphertext_is_not_the_plaintext` | PASS | 0.132 |
| A-06-11 | ProfilePasscodeStoreTest | `profiles_do_not_share_a_record` | PASS | 3.379 |
| A-06-12 | ProfilePasscodeStoreTest | `ten_failures_exhaust_the_record` | PASS | 8.798 |
| A-06-13 | ProfilePasscodeStoreTest | `a_profile_with_no_record_has_no_passcode` | PASS | 0.260 |
| A-06-14 | ProfilePasscodeStoreTest | `a_record_whose_key_is_gone_reads_as_unreadable` | PASS | 1.077 |
| A-06-15 | ProfilePasscodeStoreTest | `the_failure_count_survives_a_new_store_instance` | PASS | 5.197 |
| A-06-16 | ProfilePasscodeStoreTest | `a_passcode_is_written_and_verifies` | PASS | 1.793 |
| A-06-17 | ProfilePasscodeStoreTest | `a_wrong_passcode_is_refused_and_the_right_one_still_works` | PASS | 2.513 |
| A-06-18 | ProfilePasscodeStoreTest | `a_correct_passcode_resets_the_failure_count` | PASS | 4.881 |
| A-06-19 | ProfilePasscodeStoreTest | `the_record_is_a_file_and_holds_no_readable_passcode` | PASS | 0.926 |
| A-06-20 | ProfilePasscodeStoreTest | `replacing_a_passcode_preserves_its_preferences` | PASS | 3.318 |
| A-06-21 | ProfilePasscodeStoreTest | `removing_a_passcode_deletes_its_record` | PASS | 0.874 |
| A-06-22 | ProfilePasscodeStoreTest | `a_corrupted_record_reads_as_unreadable` | PASS | 0.897 |
| A-06-23 | ProfilePasscodeStoreTest | `preferences_round_trip_through_the_encrypted_record` | PASS | 0.901 |
| A-06-24 | ProfilePasscodeStoreTest | `the_file_name_carries_only_the_opaque_key` | PASS | 0.873 |
| A-06-25 | ProfilePasscodeStoreTest | `the_first_four_failures_carry_no_delay` | PASS | 4.036 |
| A-06-26 | ProfilePasscodeStoreTest | `a_backoff_expires_against_the_clock` | PASS | 5.623 |
| A-06-27 | ProfilePasscodeStoreTest | `refresh_reports_every_protected_profile` | PASS | 1.613 |

## Phone functional subcases

UTC times are on 2026-10-03. Each PASS is limited to its named subcase; it does not accept the full parent
matrix. Transport/session observations prove controller state and position, not what a human heard.

| Case / parent | UTC evidence window | Expected | Observed / result | Evidence |
| --- | --- | --- | --- | --- |
| PHONE-01 / A-08, Q-01 | 16:46:34–17:04:22 | Installed bytes and About identify the trusted build. | PASS for identity: checksum matches; About shows 2175 / `8beec05ca33b`. Upgrade/data migration NOT RUN. | `installed-bookwave.apk`, `apk-identity.json`, `about-opened.xml` |
| PHONE-02 / D-15 subset | 17:02:26–17:03:01 | Stored copies remain listed after invoking verification. | PASS for navigation/invocation: two copies remain, no failure UI. No byte-level integrity result or transfer completion is claimed. | `downloads-initial.*`, `downloads-verified.*` |
| PHONE-03 / P-01, P-02 | 17:10–17:21 | Explicit phone Play loads one session; mini/full reattach to it. | PASS: cold launch is idle; Resume starts one foreground playback service/session; mini opens/collapses the full player with the same book/position. Warm-state observation below remains unresolved. | `cold-home.*`, `cold-book-details.*`, `local-play-request.png`, `playing-home.png`, `player-expanded-session.json` |
| PHONE-04 / P-06 | 17:21:01–17:22:37 | System Pause works; forward/back use the configured 30-second intervals. | PASS: PAUSED at 63,826,256 ms, forward to 63,856,261, back to 63,826,261. Five ms difference is sampling; physical headset commands NOT RUN. | `system-pause-session.json`, `system-forward-session.json`, `system-rewind-session.json` |
| PHONE-05 / S-01 | 17:27:55–17:27:59 | One-minute manual timer keeps the book title in full player and displays countdown on Samsung media card. | PASS: full-player Sleep action 0:59 and original title; media card countdown 0:59 and extend/transport controls. | `timer-active-full.png`, `timer-notification.png` |
| PHONE-06 / P-02, S-01 | 17:28:54–17:31:50 | Notification content entry reattaches; natural expiry pauses without replacing the book. | PASS: same one playing session on entry; expiry around 17:28:55 pauses; mini retains title and idle Sleep. Later PAUSED sample is 63,886,188 ms, about 59.36 seconds beyond start. Post-expiry notification title restoration was not captured. | `timer-notification-entry.png`, its session JSON, `timer-active-mini.png`, `timer-expired-session.json` |
| PHONE-07 / P-01 | 17:34:18.920–17:34:27.062 | Fully local playback works with no usable network; cold reopen does not autoplay. | PASS: Wi-Fi/data disabled; active default network `none`; launcher has no live session, explicit Resume creates one PLAYING session at 63,886,222 ms. | `offline-restart-result.json`, `offline-cold-home.*`, `offline-playing.png` |
| PHONE-08 / P-04 | 17:34:40.476–17:34:46.386 | Force-stop/reopen/resume loses at most ten seconds and does not autoplay. | PASS for one local run: pre-kill 63,898,058 ms; cold launcher no session; explicit Resume 63,899,364 ms. No observed loss: resumed 1,306 ms ahead of the earlier sample. | `offline-restart-result.json`, `offline-before-kill-session.json`, `offline-after-kill-home.*`, `offline-resumed-after-kill.png` |
| PHONE-09 / P-02 | 17:38:07.937–17:38:14.519 | Rotation and 200% text do not create another session or interrupt playback state. | PASS: one PLAYING session before/after landscape and at 2.0 font scale; position subsequently advances. This does not measure audible continuity. | `before-rotation-session.json`, `after-rotation-session.json`, `after-large-font-session.json` |
| PHONE-10 / U-01 subset | 17:38:10–17:38:14 | Full-player controls remain readable/reachable across these configurations. | FAIL for visual review: landscape text overlays bright enlarged artwork with weak contrast. At 2.0 portrait scale the sampled transport/history/sleep/speed/chapter controls were visible after scrolling; author text truncates. Numeric contrast, full matrix and TalkBack NOT RUN. | `player-landscape.png`, `player-large-font.png`, `player-large-font-scrolled.png`; OBS-UI-01 |
| PHONE-11 / S-01 | 17:41:55–17:42:01 | Active mini retains title; notification extend adds the configured five minutes to the same timer. | PASS: mini shows original title and 4:58 in Sleep; extend leads to 9:56 on media card and the same remaining timer in its sheet. | `timer-active-mini-five.png`, `timer-after-extend.png`, `timer-cancel-sheet.png` |
| PHONE-12 / S-01, S-02 subset | 17:43:16–17:43:56 | Explicit Off clears timer and restores non-car media title/controls. | PASS: sheet Off cancels; mini returns to idle moon; Samsung media card restores original title and ordinary transport controls, with no extend action. Car/lock-screen/headset projections NOT RUN. | `timer-cancel-sheet.png`, `timer-cancelled-mini.png`, `timer-cancelled-notification.png` |
| PHONE-13 / U-05, Q-01 subset | 17:49:43 | Bundled Loopbound entry renders with the recorded WebView. | PASS for first-page rendering and mini-player presence. No affected-device flicker reproduction, game progression, standalone/opaque/Haze comparison or reduced-motion matrix. | `loopbound-phone.png` |
| PHONE-14 / cleanup | 17:51:27 | Leave playback paused, timer off, network/display settings restored. | PASS: one PAUSED session, font 1.0, auto-rotation 1, user rotation 0, Wi-Fi/data 1. Book remains loaded; Stop/clear disappearance was not tested. Temporary unsuffixed benchmark app removed only from active user. | `final-paused-session.json`, `display-baseline.json`, `display-restored.json`, `network-baseline.json`, `offline-restart-result.json` |

The offline helper asserts network absence, uses UI Resume, samples only the BookWave session and restores
network in `finally`. Force-stop is the exercised termination mechanism; ordinary OS low-memory death,
remote-stream repetition and repeated runs are still pending. Restoration is intentionally silent until
explicit Resume; a paused live session on the first launcher frame is not claimed.

## Observations requiring follow-up

- **OBS-PHONE-01 — warm Starting state:** the inherited warm app showed a disabled/spinning Starting control
  around 17:05:17–17:07:22, with no live BookWave service/session found. Force-stop and ordinary alias launch
  at 17:10 cleared it; explicit Play then worked. Playback & Lifecycle should reproduce from a defined
  prior state before diagnosing it. It is not a proven cause or repeatable regression. Evidence:
  `local-book-details.png`, `local-book-ready.png`, cold-launch captures and session samples.
- **OBS-UI-01 — landscape contrast:** recorded under the existing appearance, not a reset theme. UI &
  Experience should repeat with controlled background/opaque settings and measure effective contrast under
  #194/#195. The screenshot is an observed visual concern; no numerical contrast ratio is asserted.
- **Evidence tooling:** UiAutomator can exit zero with `ERROR: could not get idle state` while timer/player
  updates animate, leaving an old XML file behind. The helper now uses unique remote paths, checks errors
  and takes screenshots first. `local-play-request.xml`, `playing-home.xml` and `playing-home-return.xml`
  are excluded from UI assertions; their fresh PNGs remain valid. `timer-before-extend.png` caught a shade
  animation and does not establish a readable pre-action card. The post-expiry card was absent when sampled.
  Direct MainActivity/implicit launcher attempts also failed in the harness; the exported Indigo launcher
  alias works. None of these harness failures is an accepted product defect.

## Performance tier — Q-05

`:app:assembleBenchmark :benchmark:assembleBenchmark` passed in 1m 41s. The unsuffixed app was absent for
the active user before this fixture run; the owner's `.debug` installation was retained. The five selected
tests **failed**, Gradle failed in 2m 55s. JUnit report UTC `16:55:33`, aggregate 140.101s:

| Case | Expected | Observed | Result / seconds |
| --- | --- | --- | --- |
| `StartupBenchmark.startupNoCompilation` | Record cold TTID/TTFD | `Unable to confirm activity launch completion []` | FAIL / 25.469 |
| `StartupBenchmark.startupFullCompilation` | Record compiled TTFD floor | Same launch-confirmation failure | FAIL / 25.686 |
| `LibraryScaleBenchmark.scrollBooksList` | Record frame timing over 2,000 books | Same launch-confirmation failure | FAIL / 25.026 |
| `LibraryScaleBenchmark.homeMemoryAtScale` | Record Home memory | Same launch-confirmation failure | FAIL / 27.783 |
| `BaselineProfileGenerator.generate` | Generate exercised startup profile | Same launch-confirmation failure; no profile | FAIL / 24.884 |
| `StartupBenchmark.startupBaselineProfile` | Measure with shipped profile | No `app/src/main/baseline-prof.txt`; requires a profile | NOT RUN |

The seeded broadcast reports 2,000 items. An explicit alias launch renders that synthetic library and
`dumpsys gfxinfo` has frames. This proves fixture/launch reachability, not a usable startup/frame/memory
measurement. The pinned AndroidX Benchmark 1.3.4 `Shell.getRunningProcessesForPackage` uses `pgrep -l -f`
and then matches the full package name. This phone returns truncated `comm` names (for example
`mebord.bookwave`), whereas `ps -A -o PID,NAME` returns the full name. The library therefore returns no
matching process; `MacrobenchmarkScope.getFrameStats` sees an empty list and launch confirmation fails.
The [benchmark runbook](../benchmark.md) records this Build & Dependencies follow-up under R-25.
No suppression, package rename or dependency upgrade was used to obtain a green result.

Logs: `benchmark-build.log`, `benchmark-connected.log`, `benchmark-gfxinfo.txt`; JUnit:
`benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-*.xml`. Upstream source artifacts
and the inspected `Shell.kt` / `MacrobenchmarkScope.kt` are retained privately beside them. Player latency
median-of-five, stress ANR checks, every metric above and baseline-profile shipping remain unaccepted.

## Remaining inventory and next run

All unexecuted steps in linked runbooks remain **NOT RUN**, including steps inside partially sampled rows.
They have no execution start/end or result artifact. This inventory records why; it does not turn
unavailable fixtures/equipment into passes.

| Register cases | Remaining work / reason |
| --- | --- |
| P-01, P-04, P-06 | Remote streaming, repeats, multi-file/chapter boundaries, speed, end-of-book and five-start latency measurement; first pass used one existing local book. |
| P-02 | Full [reattachment runbook](../device-test-issue-75.md): screen off, task removal/background variants, system-origin Play and Stop/clear. Notification entry/rotation only are sampled here. |
| P-03 | Two-hour uninterrupted soak with checkpoints; short functional playback is not a soak. |
| P-05, Q-03 | Reconnect/outbox/history/duplicate attribution and selected server-version contracts; no controlled server baseline/version or remote-history comparison captured. |
| P-07, P-11, P-12, C-01–C-08, Q-06 | Heard wired/Bluetooth routes, focus/calls/unplug, USB/wireless Auto, Automotive and DHU; external routes/hosts not exercised. API 26/31/34 and tablet/foldable still unavailable. |
| P-08–P-10 | Controlled profile/lock/generation/held-session acceptance and newest transport races; isolated deterministic fixtures remain automated evidence, not this physical run. |
| S-01–S-06 | Remaining [sleep runbook](../device-test-sleep-schedule.md): schedule/overnight/timezone/DST, lifecycle persistence, lock screen, grace/shake/sensitivity and car suppression/expiry/reconnect. Manual phone expiry/extend/cancel only are sampled. |
| D-01–D-14 | Controlled failed/partial/constrained transfers, process restart, claims/permissions across fixture accounts, notification denial, card/low-space/removal races, upgrade and Android 15+ timeout. Existing stored copies alone cannot exercise these transitions. |
| D-11, D-12 | Every DEV-DL-11/12 step in [shared-transfer ownership](../reviews/2026-10-03-shared-download-ownership.md) remains NOT RUN; needs controlled accounts/server responses. AUTO-DL-12-01 second-cancellation reproduction remains next automated download work. |
| D-15 | Smart next-book/charging/metering/retention and atomic completion; stored-copy Verify invocation is only a navigation check. |
| U-01–U-04 | Complete [UI matrix](ui-roadmap-triage.md), theme/width/1.3 scale, TalkBack speech/order, target sizes, sign-in/back contexts and empty/recovery states. 384 dp phone/player samples do not accept that matrix. |
| U-05 | Affected-device flicker, opaque/standalone/Haze/reduced-motion comparison; one successful first-page render is insufficient. |
| Q-01, Q-02, Q-04 | Prior-build upgrade and passcode/profile preservation, reauthentication, app-switcher/controller security and permission/admin write/deletion cases; no disposable account/baseline or destructive fixture established. |
| Q-05 | Five benchmark failures require a harness compatibility fix first; shipped-profile startup, cached player latency and download/playback stress still pending. |

Keep #128 (historical #36), #108/#109/#110/#120 and the car/sleep issues open until their complete required
matrices pass. Continue with R-123's deterministic reproduction, the benchmark compatibility repair and
controlled playback/download fixtures; arrange actual heard-route/Auto acceptance separately. Silo remains
deferred for the owner's separate research and iOS remains low priority.

## Report verification

`ktlintFormat verifyDebug '-Pshelfplayer.warningsAsErrors=true' --max-workers=4` passed after the report
updates in 5s (1,119 tasks; 12 executed, 1,107 up-to-date). No runtime/classpath change was made; this is
a normal incremental gate, not a claim that all JVM tests reran. `phone-log-verify.log` retains the result.
The evidence validator compared every instrumented name/result/duration and all five benchmark
failure/duration rows against the actual JUnit reports; all 32 rows match. Local Markdown links resolve
and `git diff --check` passes. Raw device evidence and helper scripts remain ignored.
