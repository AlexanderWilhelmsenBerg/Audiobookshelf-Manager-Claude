# Playback governor implementation and acceptance

**Status:** Implemented; local strict verification passed. Hardware acceptance remains open.
**Base:** PR305 at `5bc4f04019f400bec0c53a2f98878cbc6ed6a18e`; main `f829f616943dffe01496f695e622b49104cf9b5a`.
**Requirements:** PLAY-001/002/004/008/009, ROUTE-001/002, S01-S22.

## Regression evidence

The APK2262 capture showed a successful arrival recovery followed by another route-safety interruption
without a new eligible recovery. A fresh departure focus/boundary pair was then rejected using the old
safety-pause timestamp. These are distinct from the phone's initial Android Auto pairing failure.

The implementation keeps one pipeline and coordinates helpers through the
[governor contract](../architecture/playback-governor.md). Local results are recorded below. Exact-head
GitHub CI and APK revisions are recorded in the dependent pull request after completion.

## Software gate

The fresh-departure clock regression failed on the prior implementation. A separate counterfactual
run reverted output-command dispatch, paused-holder protection and episode rearming: all three selected
regressions failed at their expected behavioral assertions. The exact source bytes were then restored.
The new late-departure regression also failed before the fix: an expired, already-confirmed arrival
erased the measured headset needed by a fresh departure. A completed arrival is now retired without
resetting its budget or erasing that evidence; both physical callback orders have regression coverage.
Focused governor/service continuity, output protection, queue mutation, route-safety and recovery tests
passed before final integration verification. Detekt findings were resolved by extracting episode and
stateless evidence helpers, with no suppression or threshold change; focused Detekt then passed.

Final local gate (2026-11-07, JDK 21):

- `ktlintFormat`: passed; final strict ktlint checks also passed.
- `verifyDebug -Pshelfplayer.warningsAsErrors=true`: passed, including lint, Detekt and coverage gate.
- `:playback:testReleaseUnitTest`: 1,152 tests passed in the same successful invocation.
- The debug/JVM reports contain 2,988 tests, with no failures, errors or skips.
- Actual service tests include the attached route guard and service listeners, a consistent READY/playing
  simulation, permission-backed endpoint identity and exact AudioTrack proof. They verify rearm, no-proof
  refusal and newer-Pause cancellation. These remain simulated Android evidence, not hardware acceptance.
- Existing projection-lifecycle and repeated arrival/departure assertions remain; their fixtures now use
  the relocated platform adapter and explicitly report issued/active recovery proof.

- Demonstrate regression failures with the relevant fix removed, then restore and run focused suites.
- Exercise actual service forwarding transport, phone output port, Auto actions, sleep resume,
  paused holder installation, cancellation/reentrancy, stale async work and service replacement.
- Cover bounded retries, proven-headset-only rearm, duplicate callbacks, immutable deadline,
  fresh departure timing and newer-intent refusal. Preserve S01-S22 and route proof fail-closed cases.
- Run formatter, strict verifyDebug and release playback tests. Do not weaken assertions or timing bounds.
- Inspect exact-head normal PR-triggered CI before dispatching the owner-requested debug APK.

## Stationary device acceptance

### Interrupted arrival Play refinement, 2026-11-13

The initial regression run with the original implementation failed the new issued/unconfirmed
focus-retry assertion. The focused service tests exercise an interrupted muted probe, replacement
probe, cancellation by Pause/Stop, duplicate focus callbacks and delayed attempt limits. Gate cases
also cover disconnected Auto, changed headset/generation/selection, revoked identity, expiry and
consumed claims without issued Play. The fixture injects Media3 focus-loss events into the service
listener; it does not reproduce the system focus requester or prove audible output.

Final local validation passed `ktlintFormat verifyDebug :playback:testReleaseUnitTest
-Pshelfplayer.warningsAsErrors=true`. The duplicate-focus service test also failed before the governor
was updated to preserve a current pending focus retry. The checked behavior retains the same target
and original delay; user commands and other interruptions still cancel work.

Physical acceptance must reproduce interruption of the first muted Play after a positive Auto
connection. Verify attempt two waits 1.5 seconds, attempt three waits 3 seconds, no fourth Play occurs,
and no attempt unmutes on the wrong route. Pause during either delay must prevent later Play.
Check failed Auto setup without a positive connection stays paused, initially paused playback stays
paused, and headset unplug/departure or a new destination cancels pending work. Capture system focus
alongside app events to determine whether focus becomes available during backoff. No reduction of the
initial endpoint disappearance or pre-connection pause is claimed by this refinement.

Clear diagnostics, enable capture, use one headset and start narration before connecting Auto.
Repeat three full arrival/departure cycles and record actual audible gaps. Check that healthy headset
playback is preserved, or that recovery remains bounded and returns only to the verified headset.
During a separate interruption deliberately Pause: neither endpoint return nor another Auto callback
may restart narration. Repeat with connection initially paused.

Exercise dashboard/headset transport and explicit headset/car icons, with and without Keep audio here.
Verify notification/background playback, normal headset replacement outside Auto, speed/position,
sleep expiry and history/progress. Repeat capture off/on. Export promptly after failure.

Prefer subsecond interruption where the platform permits it; no test may claim guaranteed zero silence
when Android removes the actual route or denies focus. Hardware results remain pending until reported.
