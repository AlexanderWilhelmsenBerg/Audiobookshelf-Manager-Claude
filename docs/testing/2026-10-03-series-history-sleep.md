# Series, local History and sleep-timer fix verification

Owner-requested prerequisite to the roadmap, based on main `cc4642c00a5e`. Requirements LIB-003/004,
PLAY-003/004/008, SET-002 and section 21; decisions PD-002 amendment and PD-005. The Hallmark subagent
owns the [series findings](../reviews/2026-10-03-series-screen-hallmark.md). No server endpoint, schema,
dependency or permission changes. Private media text is excluded from shared evidence.

## Automated execution

| Case | Expected result | Evidence/status |
| --- | --- | --- |
| FIX-A01 | Formatter and full warnings-as-errors `verifyDebug` pass. | PASS: final formatter and warnings-as-errors `verifyDebug`, 2026-10-03 20:22 UTC; 1m48s. |
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

The cable was reconnected and ADB reports one authorized phone. Revised-APK physical acceptance is pending; every physical case below is **NOT RUN** until its observation is recorded.
Earlier debug 2175 phone results cover older behavior and do not establish acceptance for these changes.
Record APK source/version/code/signer/hash, device/API, UTC interval, settings, expected/observed result
and redacted/private evidence for each case using the [verification register](roadmap-verification-register.md).

| Case | Procedure and pass condition |
| --- | --- |
| FIX-D01 | Cached series with finished/in-progress/not-started books, long metadata and multiple memberships: visible textual states/checkmark; correct selected sequence; full metadata; details/Play act separately. |
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

Final commit, gate/CI run, APK identity/checksum and final automated totals will be recorded after validation.
Final local gate: PASS. App 541, playback 527, Room 56 and settings 30 tests pass with zero failures/errors/skips. A [native History row](../reviews/evidence/history-event-375dp-font1.3.png) was inspected with date/time, position, chapter event and percent. Physical results will identify the exact installed APK.
