# Issue #128 headset continuity review — 2026-10-03

**Owner:** Android System & Auto, with Test & Acceptance review.

**Reviewed baseline:** GitHub main `3e6997866d800b7dc04e210a624c2f52e379c44b`.

**Requirements:** PLAY-001/002/004/008, ROUTE-001/002; profile boundaries from AUTH-002 and section 6.5.

**Status:** a provider-state regression is repaired and verified locally; physical acceptance is pending.

The owner's historical issue **#36** is GitHub
[#128, “A car connecting stops the book instead of letting the headset carry on”](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/128).
The mapping is recorded in `docs/android-auto-pd001-drive-acceptance.md`. GitHub PR #36 is an unrelated,
already-merged privileged-API contract change. Issue #128 remains open; this review does not close it.

## Existing solution and reachability

`RouteHeardOwnership` records the exact headset heard during actual playback, the loaded-book generation,
and the precedence of explicit listener output choices. `CarArrivalResumeGate` captures that evidence and
pairs audio-focus loss with a matching car boundary inside six seconds. Arrival uses projection connection
or the first car-controller bind. Departure uses a confirmed projection exit in either focus/boundary order;
without a positive projection observation, the legacy-controller fallback accepts only focus-before-exit.

`CarArrivalRouteRecovery` waits at most two seconds for that same headset, reasserts it and checks that its
selection survived settling. It cannot choose another headset, a speaker, Car or Automatic. The final
one-shot consume checks book generation, explicit-selection sequence and secured headset identity.
`CarContinuityPlayPrecheck` refuses a missing player, empty queue or already-active playback intent.

Production callers were inspected, rather than inferred from helper tests:

- `PlaybackService.onCreate` starts `AndroidAutoProjectionMonitor` with `::onCarProjectionUpdate`;
  `onDestroy` stops it.
- `PlayerEvents.onPlayWhenReadyChanged` supplies focus loss, retains exact-headset evidence across
  becoming-noisy evidence, and cancels it for other pause causes. The forwarding player cancels continuity
  on an explicit controller Pause even when raw ExoPlayer is already paused.
- `onCarControllerConnected`, `onCarControllerDisconnected` and `onCarProjectionUpdate` supply the lifecycle
  boundaries; `recoverAndResumeCarContinuity` reaches `resumeAfterCarContinuity`, which issues one raw Play
  only after the precheck and accepted consume.
- Book/queue transitions invalidate route and continuity ownership. Output collectors refresh evidence
  only while actual playback is observed; an explicit selection has its own monotonically increasing sequence.

These checks establish reachability and policy. They do not establish focus reacquisition, audible playback
or the physical AudioTrack sink on the owner's phone/head unit.

## Confirmed finding and repair

The production projection callback used only `update.previous.carConnected` to identify the old connection.
The real monitor can return `Unknown` for a null/unreadable provider result and then later return a valid state.

Two sequences therefore broke the established lifecycle:

1. **Projection → Unknown → NotConnected:** Unknown correctly did not depart. The subsequent confirmed exit
   also did not depart because its immediately preceding state was Unknown. Controller counts were left stale,
   `carContinuitySessionEstablished` stayed true, and `projectionOwnsCarLifecycle` remained true, suppressing
   the final-controller fallback as well.
2. **Projection → Unknown → Projection:** recovery of the provider read became another arrival, which could
   cancel a pending departure candidate as the wrong lifecycle phase.

The repair reads the already-existing `projectionOwnsCarLifecycle` latch for `wasConnected`. A positive state
owns the lifecycle across inconclusive reads until confirmed NotConnected ends it. Unknown alone grants no
departure; an initial Unknown still does not claim a projection; a recovered positive read does not reconnect.
No new owner, endpoint, route policy, dependency or schema was introduced.

`CarProjectionLifecycleTest` runs the **real provider reader and real exported update receiver**, using a
controlled read-only ContentProvider, and delivers the emitted updates into the existing private production
service callback. The service is constructed under Robolectric without `onCreate`; only the callback's
dependencies are injected. Its existing concrete gate is seeded with current-generation heard-headset evidence.
The test exercises the callback and cleanup/authorization path, without pretending to run a foreground service
or hear audio. Framework/Hilt startup and the final audible Play remain separate acceptance tiers.

## Automated evidence

The new five-case suite on the unmodified baseline failed three cases:

- confirmed exit after Unknown did not retire stale controllers;
- provider recovery emitted one false arrival when zero were expected;
- a cancelled transition still failed confirmed-exit cleanup.

The same assertions all passed after the one-line repair. The red run completed in 16 seconds and the green
run in 21 seconds. Evidence is retained locally in `build/issue-36-review/regression-red.log`,
`regression-red.xml` and `regression-green.log`. A preliminary fixture constructor compile error was corrected
before the red run; it is not counted as regression evidence.

| Suite | Cases | What it establishes |
| --- | ---: | --- |
| `CarProjectionLifecycleTest` | 5 | Actual monitor → service transitions: Unknown interruption/recovery, confirmed exit, retained fallback boundary, cancellation and duplicate/late disconnects. |
| `CarArrivalResumeGateTest` | 23 | Exact target, phase/window matching, route omission, generation/output-intent invalidation, deliberately cancelled transitions and wrong-target rejection. |
| `CarArrivalRouteRecoveryTest` | 4 | Exact-target reappearance, bounded absence, preference-loss retry and newer intent during settling. |
| `CarLifecycleContinuityIntegrationTest` | 3 | Concrete ownership → gate → route-recovery → consume chain for arrival, legacy departure and projection departure. These do not invoke the service callback. |
| `CarContinuityPlayPrecheckTest` | 6 | Five precheck decisions and one source-text assertion for guard/Play ordering; the latter is wiring evidence, not a running MediaSession. |
| `AndroidAutoProjectionMonitorTest` | 3 | Public raw-state mapping, positive/unknown semantics and initial-observation flag. |
| `HeadsetHoldTest` | 15 | Route-heard evidence, explicit output intent, ambiguity, speaker supersession and disconnection. |
| `RouteHeardOwnershipGenerationTest` | 3 | Generation ownership, new-book invalidation and queue-clear invalidation; this class lives in `HeadsetHoldTest.kt`. |
| `CarConnectionsTest` | 6 | Multiple controllers, zero floor, projection-exit cleanup and readiness timestamp. |

The final nine-suite run passed **68 tests, zero failures and zero errors**, with warnings-as-errors,
`:playback:ktlintCheck` and `:playback:detekt`, in 13 seconds (88 tasks: nine executed, 79 up-to-date).
Root `ktlintFormat` also passed; the final focused formatter passed before this test run. The local execution
log is `build/issue-36-review/focused-continuity-final.log`. The combined `verifyDebug` gate belongs to the
integration owner and remains required before the change is complete.

## Required remaining automated checks

These entries are **pending**, unless a later execution record explicitly supplies results. A green focused
suite does not silently check them off.

| ID | Test to execute/add | Required result |
| --- | --- | --- |
| AUTO-36-01 | Run all nine suites above, formatter and the combined warnings-as-errors `verifyDebug` gate. | **PASS, 2026-10-03:** 68 focused cases and forced combined gate passed; execution record below. Current-head CI remains required before merge. |
| AUTO-36-02 | Extend the actual provider harness with empty cursor, missing column, invalid raw value, SecurityException and IllegalArgumentException. Include a later valid read after each. | Each failure is Unknown, produces diagnostic reason without private data, grants no exit/Play, and does not erase the last positive lifecycle. |
| AUTO-36-03 | Supersede a suspended provider read and stop/restart the monitor while it is pending. | Old reads cannot publish into a newer observer/service lifetime; receiver/read resources retire. |
| AUTO-36-04 | Run production MediaSession/forwarding-player Pause while focus has already paused the raw player; then deliver arrival/departure and route return. | Duplicate Pause cancels all continuity, and no raw Play is issued. Repeat with newer Play/Stop, book change and profile switch. |
| AUTO-36-05 | Exercise the accepted final service resume with a real loaded ExoPlayer/MediaSession; repeat rejection, missing player, empty queue and already-active intent. | One accepted transition reaches one Play; every refusal reaches zero. The existing source-text wiring assertion alone cannot prove this. |
| AUTO-36-06 | Cover focus/boundary timing at six seconds and beyond, and target return inside/outside the two-second route-recovery bound. | Correlation/recovery remain bounded; expired or absent targets cannot authorize Play or substitute a route. |
| AUTO-36-07 | Expire an existing sleep timer after focus has already paused ExoPlayer and before continuity recovery. Repeat with manual and automatic timers. | Expiry stays authoritative and no passive recovery resumes audio; progress and timer outcome remain correct. This interaction is unproven, not a confirmed defect in this review. |
| AUTO-36-08 | Mutate/remove the exact headset during final settling; offer another headset, speaker, Car and Automatic. | Zero replacement-target Play; newer explicit output intent wins before the final command. |

For any new fix, retain a meaningful red result with that fix removed before trusting green. Do not replace
actual transition/Media3 tests with string matching or a duplicated model of the production code.

## Phone, projection and audio-route acceptance

**All rows below are pending.** No phone, headset, head unit, DHU or emulator was used for this review.
The owner will supply a phone later. A phone alone cannot verify projected-car A2DP/focus behaviour; use the
owner's real projection setup and headset for the audio rows. DHU is useful for controller/browse behaviour,
but does not establish that physical combination's sink or audible interruption.

Before testing, record the APK commit/hash, Android/API version, phone, Android Auto version, projection mode
(wired/wireless), head-unit version and headset transport. Use fixture accounts/media; identify routes by
non-private labels in shared evidence. Record start/end progress, audible result, pause/Play count and the
correlated AndroidAuto/Playback diagnostic window for each case. A failure should retain the exact event order
and measured timings. Keep unsupported or unavailable scenarios marked Pending/Unavailable rather than Pass.

| ID | Scenario | Expected result and evidence |
| --- | --- | --- |
| DEV-36-01 | Start a downloaded book in the headset, then connect projection; repeat three times. | Same book/position continues in the same headset. No speaker/car sound, no duplicate Play; measure any silence rather than claiming zero-gap continuity. |
| DEV-36-02 | Repeat entry with a streamed book and the phone app backgrounded/locked. | Same exact-headset continuity and truthful focus/route diagnostics; streaming policy and credentials remain intact. |
| DEV-36-03 | Capture focus loss → projection connection and focus loss → first Gearhead bind orderings. | Only a matching boundary inside the six-second window authorizes the captured headset; trace records the actual interval. |
| DEV-36-04 | Capture projection connection before focus loss and before the first Gearhead bind. | Starting projection does not misclassify later entry focus loss as departure; at most one recovery occurs. |
| DEV-36-05 | Leave projection while headset playback is active; repeat three times. | Same headset recovers from departure focus loss without requiring a late legacy-controller disconnect; count cleanup enables the next drive. |
| DEV-36-06 | Capture both departure orders: focus loss → projection exit and projection exit → focus loss. | Both may pair only inside the bounded window. Record actual order; an unrelated much-later focus loss grants no Play. |
| DEV-36-07 | Deliberately pause before entry and before exit. | Book stays paused, including with a connected/headset route and enabled Arm-only policy. |
| DEV-36-08 | Press Pause during the focus/route interruption, including a repeated Pause while already paused. | New listener intent wins: no eventual continuity Play on arrival, departure or headset reappearance. |
| DEV-36-09 | Issue a newer Play during recovery. | New request owns playback; continuity does not issue a second Play or disturb its queue/position. |
| DEV-36-10 | Stop/clear the queue, change book, or switch profile during recovery; repeat A → B → A. | Old headset/book/session generation cannot restore old audio, metadata, position or authority. Profile privacy holds. |
| DEV-36-11 | Choose Car, another Headset, or Automatic explicitly during recovery. | The newer explicit choice wins; the old captured-headset recovery cannot reassert or Play over it. |
| DEV-36-12 | Two classic-A2DP candidates are connected; change their connection order. | Enumeration order never chooses the continuity target. Only the positively heard headset is eligible. |
| DEV-36-13 | Target headset vanishes briefly and returns; repeat with absence longer than recovery window and with only another headset returning. | Same target may recover inside the bound; late/absent/wrong target leaves playback paused, never on speaker/car. |
| DEV-36-14 | Unplug/disconnect headset with no car lifecycle boundary. | Immediate safe pause; no generic becoming-noisy/focus auto-resume onto the phone speaker. |
| DEV-36-15 | Incoming call, navigation prompt and another media app take focus while projection remains connected. | Ordinary Android focus policy applies; continuity does not interpret these as completed departure or steal playback. |
| DEV-36-16 | Provider is temporarily unreadable during an established projection, then reports confirmed exit. | Unknown alone has no exit effect; confirmed NotConnected retires all counts and completes eligible departure once. Use diagnostics/fault-capable test build if the read failure cannot be induced safely. |
| DEV-36-17 | Provider is unreadable then recovers to Projection during the same drive. | No second arrival or extra Play; pending departure evidence is not cancelled as a false reconnect. |
| DEV-36-18 | Projection provider unavailable for the entire drive, with a real final controller disconnect after focus loss. | Conservative legacy fallback remains usable; reverse-order legacy-only focus loss does not auto-resume. Record unavailable provider explicitly. |
| DEV-36-19 | Multiple car controllers, duplicate callbacks, delayed controller disconnect after physical exit, then reconnect. | Intermediate disconnect does not depart; physical exit clears all counts; late callback cannot create another Play or poison next arrival. |
| DEV-36-20 | Recreate the activity during the transition; also test a service/process restart under connected projection. | Activity recreation preserves the service. Fresh service observation does not invent arrival/Play or reuse stale ownership; valid user/media command still works. |
| DEV-36-21 | Existing manual/automatic timer continues through car transition; expire it while focus has already paused playback. | Timer and progress outcomes stay correct; expiry must not be undone by passive continuity. This is a pending cross-policy case. |
| DEV-36-22 | Run entry/departure while offline on a downloaded book, then reconnect to the server. | Local position/listening time remain durable and sync once; car recovery does not open or attribute another profile's session. |
| DEV-36-23 | Leave a book deliberately paused while framework/system output chooser moves to speaker, then connect the car. | No old headset resurrection or unintended speaker/car Play. Repeat chooser movement during the interrupted transition and record policy evidence. |
| DEV-36-24 | Repeat supported routes on API 26/31 and 34/36 devices/emulators, with a real projection run on the reference phone. | API-specific route-observation fallback stays conservative. Mark missing API/headset/host combinations unavailable; do not infer audible results from JVM/emulator checks. |
| DEV-36-25 | Export the correlated diagnostics with default privacy settings. | No device names/addresses, private media titles, server hosts, usernames, tokens or paths; exact target can be correlated without disclosure. |
| DEV-36-26 | During the two-hour playback soak include repeated entry/exit, backgrounding and a forced process termination. | No crash/ANR, no speaker surprise, and no more than ten seconds of progress loss; report audible gaps and server reconciliation separately. |

Issue closure requires observed successful entry/departure, Pause/output safety and exact sink on the reference
setup. A few-second interruption has been measured historically; the current bounded correlation preserves
safety but does not promise zero-gap audio before a trustworthy car boundary exists.

## Documentation follow-up for the integration owner

`docs/architecture/playback.md` at the reviewed baseline still describes only legacy final-controller
departure and calls disconnect-first/focus-later diagnostic-only. ADR-0029 section 9 already describes the
implemented physical-projection boundary and its permitted reverse order. Reconcile that architecture paragraph
with the current code, retain the distinction between strong projection and weak legacy boundaries, and record
this new provider-lifecycle regression/evidence in the shared risk and acceptance documents.

The combined drive checklist also still describes GitHub PR #205 as open although it merged on 2026-10-02.
Keep it aligned with the exact tested APK and the central verification register. No physical acceptance has
been upgraded to complete by this review.

## Integration execution record

The integration source at `297965b3` passed `ktlintFormat verifyDebug
-Pshelfplayer.warningsAsErrors=true --rerun-tasks --max-workers=4` in **6m 59s**, with all **1,119 tasks
executed**. App 521 and playback 516 debug tests had zero failures/errors. Production `onCreate` still starts
the real monitor with `::onCarProjectionUpdate`; the regression reaches that same callback.

The initial full run found type-aware fixture lint (`ArrayPrimitive` and `NullableToStringCall`) beyond the
focused static-analysis task. Replacing the cursor's primitive array with a list and the nullable fake-class
name with its Java name preserved the regression assertions. A separate local SDK-path escaping error and
stale lint report were corrected in the isolated worktree; the forced gate then rebuilt every task. Failed
and successful logs are retained under ignored `build/verification-handoff/` (`verify-first.log`,
`verify-second.log`, `verify-stale-lint.log`, `verify-complete.log`). No phone/DHU/real headset was used.
