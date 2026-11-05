# Delayed Android Auto arrival: cleanup/recovery characterization

Requirements: PLAY-002, ROUTE-001; issue #277; baseline `04186e447fc75d2e19c2068661117be4a0793104`.
This is a tests-only investigation. It changes no playback behavior and does not close hardware acceptance.

## Reproduction boundary

The owner's APK 2255 diagnostic records headset disappearance at 14:31:01.346 UTC, a route-safety
pause at 14:31:01.692, becoming-noisy at 14:31:02.375, focus loss at 14:31:06.841, and first Auto bind
at 14:31:12.075. Selection advances from 0 to 1 before the noisy event. The first rejection is
`NoFreshPlaybackAfterIntentReset`; the later bind rejects with `NoPendingFocusLoss`.

`DelayedCarArrivalCleanupTest` starts from previously heard headset evidence and a safety pause.
It invokes the real router inventory refresh/disappearance cleanup, the real service `PlayerEvents`
transport callback (including `syncExplicitOutputIntent`), and the production recovery coordinator.
The lifecycle owner supplies absence evidence. The test injects trusted identity and heard evidence;
it does not measure a physical headset or prove the initial pause was avoidable. Arrival is inspected
at the coordinator decision boundary, before playback effects, rather than through a real Auto bind.

With projection still `NotConnected` and no bound controller, automatic retirement creates selection 1.
The noisy/focus callback consumes that selection through the service and cancels the recovery episode.
The test reproduces both diagnostic rejection reasons. With `Unknown` instead, cleanup retains the
headset, the delayed boundary is accepted, and the episode permits only one Play claim. This isolates
a sufficient cause in the production logic; the export alone does not identify the selection writer.

## Controls and interpretation

- Deliberate Pause while already safety-paused cancels recovery even without another raw player transition.
- A known car controller prevents disconnected-headset retirement before projection updates.
- A newer explicit Car choice and a changed book generation invalidate the older headset episode.
- Repeated platform interruptions cannot extend the absolute deadline or authorize a second Play.
- Existing `DisconnectedHeadsetSelectionTest` protects ordinary replacement, locks, refusal and races.
- Existing transfer/probe tests protect wrong-output verification, cancellation and position restoration.

The test named `known defect - pre-arrival NotConnected cleanup erases delayed recovery` deliberately
characterizes the current failure. Its green result is **not** acceptance of that behavior. A later fix
must replace this expectation with successful bounded recovery while retaining the cancellation controls.
Temporarily requiring `Ready` at the same arrival boundary is the red acceptance probe; restore the
characterization afterward, without changing production code or disabling any tests.

`AudioTrackRouteQueryTest` also exercises an unchanged, playing AudioTrack that still reports its sink
while that device is absent from the registry. The production observation becomes `Unresolved`, with
the same observer epoch. This establishes a distinction between missing inventory and measured route
loss; it does not authorize playback on unresolved evidence. Whether this happened on the phone remains
unproven. Preserve fail-closed behavior until that separate path has a justified policy and hardware test.

## Follow-up boundary

First correct only the confirmed cleanup/continuity interaction, retaining ordinary headset replacement,
listener cancellation, exact identity, one player/session, and Media3 focus ownership. Evaluate avoiding
the initial pause separately with actual route/focus observations. Hardware acceptance must repeat
headset/car button and dashboard switching, ordinary headset replacement, deliberate Pause, and car
arrival both while playing and while paused. Neither a passing suite nor these fixtures prove no silence.

## Validation

- The temporary acceptance probe failed exactly once among the six cleanup tests:
  `expected:<Ready> but was:<Rejected>`. No production edit was needed to reproduce it.
- After restoring the explicitly labeled characterization expectation, `ktlintFormat verifyDebug
  -Pshelfplayer.warningsAsErrors=true` passed (1,126 tasks; 87 executed, 1,039 up-to-date).
- Debug/shared reports contain 2,945 tests, zero failures/errors/skips. Unchanged suites were up-to-date;
  the playback suite ran. This includes six cleanup tests, 14 AudioTrack route-query tests, and the
  existing 11 disconnected-headset-selection tests. Seven tests were added in total.
- `git diff --check` passed; no production-source changes. No device/DHU acceptance was performed.
