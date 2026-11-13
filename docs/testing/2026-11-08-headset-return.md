# Brief headset return after a safety pause

**Requirements:** PLAY-002 / ROUTE-001, PD-009 owner refinement 2026-11-08.
**Delivery:** PR306, dependent on PR305. Hardware acceptance pending.

The existing service safety-pause callback now issues a `HeadsetReturn` claim without an Auto boundary
when the gate retains a previously verified, trusted headset. The existing recovery effect waits for
that exact output and uses muted current AudioTrack verification. Both waiting and proof share an
absolute two-second deadline from the safety pause. No new preference, player, session or focus owner
is introduced. Healthy proven playback still bypasses the safety pause entirely.

The small gate-owned `HeadsetReturnRecovery` helper owns only the short claim's deadline and consumed
state. It cannot discover outputs or issue transport. A noisy notification during the same inventory
gap preserves only its unconsumed claim without restarting effects or the deadline. A later focus loss
cancels this shortcut; the existing car-boundary path remains available under its original rules. Deliberate Pause,
Stop, seek, selection, book/profile changes and teardown retain priority. Unknown or mismatched identity,
late return and missing/wrong route proof stay paused. No replacement headset is chosen automatically.

## Regression evidence

- `HeadsetSafetyReturnTest`: one-use claim, strict expiry, repeated callbacks, newer generation/selection,
  wrong target, identity loss, explicit cancellation and focus/car-boundary separation.
- `PlaybackServiceHeadsetReturnTest`: real service and guard callbacks reach the existing effects;
  timely exact return issues one Play while muted, actual proof unmutes, expiry stays paused,
  user Pause cancels, wrong actual route fails closed, a newer dashboard destination wins and repeated
  car arrival cannot replace the pending short claim or duplicate Play. The same gap's noisy notification
  retains the existing unconsumed claim without extending it; actual focus loss cancels it.
- Before implementation, four of five new policy tests failed because no short claim existed.
  Temporarily disabling claim creation afterward made four service regressions fail. Restored the fix.
  The noisy-after-safety service regression also failed before the scoped preservation wiring was added.
- Local `ktlintFormat`, strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` and
  `:playback:testReleaseUnitTest` passed: 3,032 debug/JVM tests and 1,196 release playback tests,
  zero failures/errors/skips. This slice adds thirteen regressions. Exact-head CI/APK results are recorded
  in the PR after completion. The initial gate attempt caught a ReturnCount style violation, corrected
  without changing thresholds.
- Delayed-arrival fixture ordering now removes inventory before delivering its safety pause. Leaving
  the headset live at that point correctly started the newly authorized short recovery and invalidated
  the fixture's intended missing-output scenario. All previous cleanup, cancellation and expiry assertions
  are retained; the affected suites pass together with the new service tests.

The service fixture uses a real pinned Media3 player with controlled inventory, clock and route evidence.
It proves command ordering and protection, not physical audibility. No hardware result is claimed.

## Stationary device acceptance

1. Clear diagnostics; enable route recording. Play a book on one identified headset.
2. Exercise a brief headset output interruption/return. If the exact route returns and verifies before
   the two-second deadline, expect one recovery; inspect `phase=HeadsetReturn`,
   `reason=SafetyPausedHeadsetReturn`, route events and the final verification outcome.
3. Repeat while deliberately pressing Pause during the interruption: expect no automatic resume.
4. Leave the output absent longer than two seconds: expect paused playback until deliberate Play or
   separately qualified Auto recovery. A similarly named/different headset must not inherit recovery.
5. Connect/disconnect Android Auto, then verify dashboard Play, headset Play, phone Play and explicit
   Headset/Car choices retain their routing behavior. Initially paused playback must remain paused.
6. Verify notification/call interruption still follows Media3 focus policy and book position remains
   correct. Measure audible silence separately; application timestamps are not acoustic proof.
