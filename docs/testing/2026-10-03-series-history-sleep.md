# Series, local History and sleep-timer fix verification

Owner-requested prerequisite to the roadmap, based on main `cc4642c00a5e`. Requirements LIB-003/004,
PLAY-003/004/008, SET-002 and section 21; decisions PD-002 amendment and PD-005. The Hallmark subagent
owns the [series findings](../reviews/2026-10-03-series-screen-hallmark.md). No server endpoint, schema,
dependency or permission changes. Private media text is excluded from shared evidence.

## Automated execution

| Case | Expected result | Evidence/status |
| --- | --- | --- |
| FIX-A01 | Formatter and full warnings-as-errors `verifyDebug` pass. | PASS: final formatter and warnings-as-errors `verifyDebug`, 2026-10-03; 1m48s. |
| FIX-A02 | Series rows show finished icon/text, in-progress state, full title/author/sequence/progress; geometry works at 320/375/414/768 dp × font 1.0/1.3/2.0. | Native 20/20 pass; old-row delegation fails all three guards; restored 20/20 pass. Includes 12 geometry combinations, two states, two inspected native captures and four route/screen cases. See Hallmark findings for renderer limitations. |
| FIX-A03 | Series ordering/counts, continuation, details vs Play and minimum targets remain correct. | Existing four `SeriesScreenTest` cases pass; full gate PASS. |
| FIX-A04 | Rolling checkpoints update one row; a new local event starts a new checkpoint and preserves previous position/time. | Both new `MigrationTest` checkpoint cases failed insert-only implementation; fixed transaction passes. Includes Room close/reopen and existing upgrade suite PASS in final gate. |
| FIX-A05 | Server imports do not split local checkpoints; late older samples cannot replace newer samples; intentional newer rewinds remain valid. | Transaction test passes for remote import and stale sample; full gate PASS. |
| FIX-A06 | Ordinary chapter crossings are recorded service-side at the observed position/time with captured profile ownership; no chapter data invents no event. | `ListeningHistoryRecorderTest`, including account/book changes; all three PASS in full gate; disabling crossing fails both crossing guards. Sampling observes a crossing within the existing five-second interval. |
| FIX-A07 | History renders event date/time, position, percent and Chapter crossed; unknown duration has no invented percent. | Four `HistoryRecoveryScreenTest` cases pass, including paused chooser controls. |
| FIX-A08 | Fixed countdown freezes during pause and resumes the saved remainder; no paused/empty creation. | Three new `SleepTimerControllerTest` regressions failed old implementation and pass fixed implementation. |
| FIX-A09 | Pause during suspended creation closes provisional session; paused extension remains frozen; fade/expiry respects actual audio activity. | All 43 controller cases PASS in full gate; existing schedule/grace/generation cases remain required. |
| FIX-A10 | Existing natural expiry/grace, chapter timers, schedule end/manual precedence, car suppression and cancellation remain correct. | Existing timer/projection/car suites PASS in full gate; fixtures model audible audio when starting a timer. |
| FIX-A11 | All five sensitivity names survive DataStore; Normal/High/Low thresholds remain compatible. | `DefaultSleepTimerRepositoryTest` round trips all levels; legacy sensitivity assertions pass. |
| FIX-A12 | Extra high/Ultra high thresholds are progressively lower; stationary gravity/noise/isolated samples rejected, sustained small sideways motion detected. | New threshold assertion failed temporary High-equivalent levels; filtered `ShakeSensitivityTest` cases pass. Synthetic acceleration does not prove bedside effectiveness. |

Local intermediate logs are ignored under `build/`: `sleep-red.log`, `history-red-sleep-green.log`,
`fixes-focused.log`, `fixes-green.log`; JUnit reports remain the test-count authority. Intermediate
compile/fixture/render failures are repaired before the final gate and are not reported as passes.

## Physical execution required

The cable was reconnected and ADB reports one authorized phone. The table below is the complete procedure inventory. Execution and partial coverage are recorded below it; unrecorded subcases remain **NOT RUN**.
Earlier debug 2175 phone results cover older behavior and do not establish acceptance for these changes.
Record APK source/version/code/signer/hash, device/API, UTC interval, settings, expected/observed result
and redacted/private evidence for each case using the [verification register](roadmap-verification-register.md).

| Case | Procedure and pass condition |
| --- | --- |
| FIX-D01 | Cached series with finished/in-progress/not-started books, long metadata and multiple memberships: visible textual states and subtle green inside border on completed cards; correct selected sequence; full metadata; details/Play act separately. |
| FIX-D02 | Series portrait/landscape, 100/130/200% text, English/Norwegian, missing cover, last row above mini-player; light/dark/AMOLED/dynamic/artwork appearance and TalkBack. Nothing clips; effective contrast and minimum controls remain usable. |
| FIX-D03 | Open/scroll series and History during active playback and without internet. Audio/position continuity survives navigation; repository state remains available. |
| FIX-D04 | Offline local listening at least two minutes with History closed and Activity backgrounded: rolling checkpoint advances within five seconds, one row per event interval, correct date/time/position/percent. Force-stop, cold open and explicit Resume; compare saved position, with no more than ten seconds lost. Repeat process kill and phone restart separately; do not confuse graceful pause with abrupt loss. |
| FIX-D05 | Listen across a chapter boundary with Activity closed, then reopen History: distinct Chapter crossed row at observed boundary within five seconds; correct chapter/date/position. Seek/rewind across chapters and return to a saved row; earlier event data remains intact. |
| FIX-D06 | Pause/Play/seek/timer/new book and profile switch while a sample/write is in flight, sign-out, removal and offline sync/reconnect. Each captured sample belongs to its loaded profile/book, retained event positions are not overwritten, server imports do not duplicate local checkpoints, pruning/clear remain scoped. |
| FIX-D07 | One-minute manual fixed timer: consume part, pause longer than original remainder, then resume. Mini/full/notification countdown stays frozen while paused, no expiry/fade/rewind, and expires only after remaining audible time. Repeat notification/headset Pause and buffering/audio-focus interruption. |
| FIX-D08 | Empty player, paused book and mid-start pause: preset/custom/end-of-chapter/restart creation disabled/refused. Off still works; existing paused timer can cancel or extend, extension stays frozen; no provisional active session appears after delayed persistence. |
| FIX-D09 | Pause during last fade seconds and at a chapter target; resume at speed changes. Volume restores while paused; countdown/expiry resumes coherently. Book replacement/end/service teardown cancel ownership; no wrong-book stop. |
| FIX-D10 | Scheduled timer pauses through civil schedule end: automatic timer cancels without Play, manual timer remains frozen. Manual suppression, next occurrence, car connect/disconnect and explicit/passive resume retain the schedule policy. |
| FIX-D11 | Repeat every step of [sleep schedule/grace/system surfaces](../device-test-sleep-schedule.md), including ten-second/off/exact-boundary grace and settings changes. New creation requires active audio; grace restart intentionally resumes expiry-paused playback through the existing owner. |
| FIX-D12 | Opt-in each sensitivity; compare deliberate High, gentle Extra high and tiny Ultra high movement on the actual phone. Stationary rest and setting/registration must not restart. Sensor stops after cancel/book/service change and grace end; no sensor/permission still permits ordinary timer. |
| FIX-D13 | Ultra high in intended bedside position: record breathing-only detection rate, stationary bed, mattress/duvet/pillow placements, partner/vibration/putting-down false restarts and battery behavior. Calibrate from observed results; do not claim arbitrary bedding transmits detectable breathing. |
| FIX-D14 | Historical #36 / GitHub #128 focused headset/car late-recovery matrix: while focus-paused, timer remains frozen; newer Pause/Stop/book/profile intent wins. After resumed audio consumes remainder, actual expiry must not be undone by late automatic recovery. All other route/headset/DHU/soak cases remain in their existing register. |

## Delivery identity

The revised phone acceptance below records the exact tested code, successful gate/CI run and signed APK identity/checksum. The final merged build is identified in its delivery handoff.
Final local gate: PASS. App 541, playback 527, Room 56 and settings 30 tests pass with zero failures/errors/skips. A [native History row](../reviews/evidence/history-event-375dp-font1.3.png) was inspected with date/time, position, chapter event and percent. Physical results will identify the exact installed APK.

## Revised phone acceptance — 2026-10-03

The cable was reconnected. Samsung SM-S928B, Android 16 / API 36; 1080 × 2340,
450 dpi, Norwegian and the user's existing appearance. Tests use debug 2177 / 0.10.6.1,
source `267a248e66cfaee55ac74a73852c045196412f00`,
APK SHA-256 `dfe2771ff37c71df5831d7c0239cafdc8e9dd08a5854a87632a795b38e246a66`.
The signer matches the existing installation
(`c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`);
`adb install -r` succeeded with sign-in, downloaded audio, catalogue and prior History intact.
Pinned Loopbound source is `7e24529b2a9e419218d2a1423d832b1bf587c8ef`.

These observations replace the pending status only for the named subcases. They do not establish
acceptance for every item in the broad physical matrix above. UI screenshots/XML and temporary
Room copies stay in ignored private evidence; shared records use only event types and numeric progress.
Fresh screenshots were taken after sheet/font transitions because the capture harness's initial
image can precede the settled layout. UiAutomator idle failures were recorded and not treated as
fresh XML or a layout pass.

| Case/subcase | Observation and UTC interval | Result |
| --- | --- | --- |
| Connected storage/Keystore tier | Environment checker reports one authorized device; all 27 `:core:datastore:connectedDebugAndroidTest` cases, 1m20s, zero failures/skips. An isolated test application ID avoids the existing device's unrelated test-package certificate; production data is untouched. | PASS |
| FIX-D01 / FIX-D02, finished rows and large text | 20:28–20:32: cached requested series, complete title/author/selected sequence/duration and visible checkmark + Fullført. The 100% and 200% portrait rows grow and wrap; the first complete long-title row remains readable. Font scale restored to 1.0. | PASS for observed rows; other states, landscape, English, appearances, missing cover, final row and TalkBack remain NOT RUN. |
| FIX-D03, navigation while playing | Media session remains PLAYING while opening the series. History remains available after timer expiry. | PASS for these routes; audible/headset judgement and offline series route remain NOT RUN. |
| FIX-D04, online rolling row | Same checkpoint ID advances from 64,044,599 ms / 20:27:30.883 to 64,084,986 ms / 20:28:11.246, and remains in the previous interval after Pause. New Play/timer events create new interval rows. | PASS |
| FIX-D05, ordinary crossing with Activity backgrounded | Seek to next chapter start and rewind 30 seconds while paused; Play then Home at 20:35:59. Service records ChapterCrossed at 20:36:34.023, position 66,777,490 ms, 4,331 ms beyond the boundary. The old interval and new checkpoint both remain. Reopened History shows the distinct chapter/date/time/progress row. Return via the earlier expiry History row restores 64,283,102 ms at 20:38:14. | PASS for natural crossing and History return; account/write race variants remain NOT RUN. |
| FIX-D07, paused remainder and actual expiry | One minute starts 20:30:55.137; Android media Pause 20:31:10.932 freezes at 0:44. Still PAUSED at 20:32:22.378 with unchanged position, beyond the original remainder. Play 20:32:22.882; expiry pauses at 20:33:07.521 after the saved audible remainder. Font configuration change does not consume the timer. | PASS; notification text, headset, buffering and real focus-loss variants remain NOT RUN. |
| FIX-D08, paused creation | 20:29:20: preset/custom/end-of-chapter controls have disabled ancestors; Off remains enabled, and the explanation is visible. Controls enable after Play. Controller-only empty/mid-start races are covered by regression tests. | PASS for paused UI; empty-player physical variant remains NOT RUN. |

Offline force-stop recovery and sensitivity/settings observations are recorded in the additional results table below.
Real power loss/reboot, bedside breathing/false-trigger calibration, headset/car/DHU, fade-at-boundary,
schedule boundary and soak acceptance remain NOT RUN. Synthetic sensor tests do not prove bedside
sensitivity. The historical #36 / GitHub #128 hardware matrix remains open.

| Additional case/subcase | Observation and UTC interval | Result |
| --- | --- | --- |
| FIX-D04, offline background listening and abrupt force-stop | 20:38:26–20:40:54: both Wi-Fi and mobile data disabled; Android reports no active default network. Cold launch has no playing session; explicit Resume plays cached audio. Activity backgrounded for 130 seconds. The same checkpoint ID advances from 64,291,632 ms / 20:38:42.731 to 64,414,222 ms / 20:40:45.313. Force-stop at 20:40:47, cold reopen and explicit Resume at 20:40:53 recover within **1,329 ms** of the pre-kill media position. Wi-Fi and data both restored to 1; playback paused. | PASS; real power loss, reboot and separate process-kill variant remain NOT RUN. |
| FIX-D12, choices and local persistence | 20:43–20:45: all five options visible. Selecting Extra high and Ultra high changes the UI and writes their corresponding enum names to local Proto DataStore. Ultra guidance is visible. Normal is restored and confirmed in DataStore. | PASS for selection/persistence; real motion, sensing lifecycle and bedside calibration remain NOT RUN. |

PR validation for code commit `267a248e66cf`:
[Standard CI 37151277034](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37151277034)
PASS. The tested signed APK is from
[Build APK 37151276406](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37151276406),
with the artifact digest independently checked before install. Subsequent acceptance-log changes are
prose only; final merge CI and APK identity are recorded in the delivery handoff.

Cleanup at 20:48:03 UTC: returning through the original 20:27 History marker restores the exact
pre-test book position **64,025,977 ms**, with playback PAUSED. Test listening and chapter seeks are
not left as the listener's current position. Font scale 1.0, density 450, automatic rotation 1,
Wi-Fi/data enabled and Normal sensitivity are restored; no active sleep timer remains.

## Completion cue refinement — 2026-10-04

The user requested removal of the completed checkmark after reviewing the phone rows. Finished text remains; completed cards now use a subtle green glow inside their border. The earlier phone checkmark observations above apply to APK 2177 only. Physical acceptance of the revised glow, light/dark appearance, TalkBack and remaining layout cases is **NOT RUN**, as requested; retain these in the missing-test matrix and proceed with the roadmap. Native rendering verification is recorded in the Hallmark review.

Refinement validation: three completion guards failed against the old card; restored refinement passed all 24 series tests. Regenerated native images were inspected. Formatter and full verifyDebug with warnings-as-errors passed in 1m 50s; initial test-only Lint findings were fixed. No dependency/classpath changes.

## Connected continuation — 2026-10-04

The [2179 phone log](2026-10-04-phone-2179.md) records the later connected results and each missing case. The compact series/glow/large-text/last-row subset passes on signed2179. All27 storage/security tests and eight benchmark executions pass. Startup meets the fixture target; scrolling misses its P95 budget. The generated profile is retained as a measured experiment outside production; no performance gain is claimed. Earlier failed or NOT RUN cases keep their dated scope.
