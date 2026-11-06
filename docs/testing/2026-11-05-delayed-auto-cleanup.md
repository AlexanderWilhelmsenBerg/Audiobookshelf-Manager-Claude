# Delayed Android Auto arrival: cleanup/recovery characterization

## 2026-11-06 four-slice continuation (PR305)

APK2259 at e8abc0b5 failed repeated physical arrival continuity despite green CI. Its second connection
created a new selection during cleanup. The capture does not prove every causal callback, so the new
regression reproduces a concrete production path: a speaker policy prediction replaces the live heard
row before the safety pause. Baseline cleanup increments selection 0 to 1 and cancels recovery.

1. Recovery now distinguishes actual non-headset evidence from policy prediction/unknown inventory.
   Three arrival/departure cycles use real service listeners, router retirement and the one-shot gate.
2. Router route events enforce safety explicitly; derived publication is passive. Inventory observation,
   current-track callbacks and playback callbacks retain their independent safety entry points.
3. The monitor can label fresh, exact Bluetooth track identity as RetainedEndpoint when no selectable
   appearance exists. Only an already-owned playing, unmuted headset can use this evidence to continue.
   The registry remains the identity owner; collisions/recycled handles/revocation retire authority.
4. Explicit transfers keep the existing transaction. Retained identity cannot seek, release mute or
   count as new destination proof. Tests cover eventual live proof and Pause while waiting.

No second player/session/track, focus request, retry loop, permission or durable state owner is added.
The existing optional Nearby devices grant is required for exact Bluetooth identity on Android 12+;
address access needs API28+, and missing/redacted addresses retain fail-closed behavior. SDK34 synthetic
tests cover the new path. API36 multi-sink classification remains exercised through the production
classifier; real OEM routing and audio focus remain device acceptance. No ABS API/schema change.

Repowise's initial branch lookup failed, but the subsequent refresh compared main f829f616 to be07e3f9:
changed-code structural health was 8.96 to 8.88, with complexity findings in isMeasuredHeadset (11)
and disconnectedOutputOrigin (9). These are maintainability findings, not runtime defect proof.
jCodeMunch remains indexed at main; current branch source and callers were inspected directly.
Repowise's inferred reach recommends the complete playback suite; no per-test coverage map is available.
The full PR adds service callback wiring and teardown in e8abc0b5; be07e3f9 adds no service changes.
Playback state, checkpoint, progress, sleep timer, profile and button attribution remain with their
existing owners. Dual-book playback is deferred.

The review cleanup separates retained-headset proof from live eligibility and makes the safety pause
an explicit retirement stage. Post-pause and post-continuity-callback scope checks retain their original
short-circuit order. No routing policy, state owner or test expectation changes. Existing interruption,
newer-selection, inventory-gap and transfer regressions guard this behavior-preserving cleanup.
Cleanup verification: formatter, strict verifyDebug and release playback tests passed on 2026-11-06.
The initial Detekt return-count finding was corrected without a suppression or rule change.

### Inventory-only lock revalidation

The logic review reproduced a status defect at 9e5be960: repeated inventory callbacks marked an
otherwise preserved lock Waiting. Equal output rows do not emit through StateFlow, so the service
collector could not restore Active without an unrelated track/playback event. Inventory preservation
now invokes the existing scoped lock verifier on the player dispatcher. It reads fresh proof instead
of setting Active directly; newer intent, pending probes and invalid evidence keep their safeguards.
Retained proof also leaves the guard's missing-endpoint flag and disappearance window intact; only
live route proof can treat the endpoint as present again.
The new inventory-only regression fails before the fix (expected Active, actual Waiting), repeats
unchanged inventory without track callbacks, asserts no pause/preference/mute or selection changes,
and then verifies a wrong route still pauses and leaves the target locked in Waiting.
Final verification passed: formatter, strict verifyDebug and release playback tests; 2,968 debug/shared
and 1,132 release playback tests, with zero failures/errors/skips. One earlier full run returned Refused
instead of Verified in the existing real-Media3 explicit-transfer test. Its fixture has neither an
inventory refresh nor an output lock and does not enter the changed paths. It passed unchanged in
isolation and in the final full gate. The intermittent refusal is recorded, not diagnosed as fixed;
neither the 50ms allowance nor test assertions were changed.

### Local software verification

Formatter, strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` and
`:playback:testReleaseUnitTest` passed on 2026-11-06. Reports contain 2,967 debug/shared tests and
1,131 release playback tests, with zero failures, errors or skips; 16 regressions were added.
The three-cycle recovery test failed against e8abc0b5 with selection 0 unexpectedly becoming 1.
Temporarily removing preservation and restoring transport effects in publication produced three
expected failures: raw retained-track evidence, continuing through an inventory gap, and passive
publication. All fixes were restored before the full gate. The final gate also covers output locks,
wrong/unknown/multiple routes, permission/collision loss, newer selection/book/Pause, diagnostics off,
one transfer restoration and the existing S01-S22/service/progress/sleep regression suites.
Static-check findings in initial runs were fixed without suppressions or gate changes.

### Device acceptance for the next debug APK

Clear diagnostics before testing. With one headset and Nearby devices enabled, perform three complete
Auto connection/departure cycles while playing. Measure audible gaps, then test headset Play, dashboard
Play, headset Play and both output icons. No skipped/jumbled narration or speaker spill is acceptable.
Repeat with diagnostics disabled; reconnect while deliberately paused and verify no automatic start.
Test real headset removal/replacement outside Auto and a normal notification/focus interruption.
Export promptly after any failure and record the APK version. Under one second remains the owner's
latency target; software route timestamps do not establish audible acceptance. Do not merge for green CI
alone. Exact SHA, CI and APK results are recorded on PR305 after verification.

## Earlier baseline and first cleanup correction

Requirements: PLAY-002, ROUTE-001; issue #277; baseline `04186e447fc75d2e19c2068661117be4a0793104`.
The baseline investigation was tests-only. The subsequent bounded cleanup fix is described below;
physical acceptance and avoiding the initial pause remain open.

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

At the baseline, `known defect - pre-arrival NotConnected cleanup erases delayed recovery` deliberately
characterized the failure. Requiring `Ready` instead produced the expected failing acceptance probe.
The follow-up replaces this expectation with successful bounded recovery, retaining all cancellation
controls. The updated regression also failed on unchanged production code before the fix was applied.

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

## Baseline validation

- The temporary acceptance probe failed exactly once among the six cleanup tests:
  `expected:<Ready> but was:<Rejected>`. No production edit was needed to reproduce it.
- After restoring the explicitly labeled characterization expectation, `ktlintFormat verifyDebug
  -Pshelfplayer.warningsAsErrors=true` passed (1,126 tasks; 87 executed, 1,039 up-to-date).
- Debug/shared reports contain 2,945 tests, zero failures/errors/skips. Unchanged suites were up-to-date;
  the playback suite ran. This includes six cleanup tests, 14 AudioTrack route-query tests, and the
  existing 11 disconnected-headset-selection tests. Seven tests were added in total.
- `git diff --check` passed; no production-source changes. No device/DHU acceptance was performed.

## Bounded cleanup fix and next device test

Inventory-driven retirement now consults the existing recovery gate before and after its safety pause.
The second check matters: the real synchronous Media3 pause callback can create the qualified episode.
Only the same trusted headset, book generation and selection can be retained, within the existing
30-second absolute deadline. Repeated inventory callbacks do not extend it. No second timer or owner
is added. Expiry permits cleanup on the next inventory callback; it never issues Play.

Deliberate Play preparation still follows the ordinary retirement path immediately when Auto is positively
absent. Existing explicit locks, active/unknown Auto and pending route verification remain protected.
The new tests cover this escape path, exact expiry, identity revocation and the synchronous pause race.

The first pause is deliberately still fail-closed. A new privacy-safe event, `Missing headset before
route-safety handling`, records `trackBeforePause` before retirement or its fallback pause. It reports
the current raw AudioTrack route using process-local device aliases/types, or no-track/paused/query-failed/
replacement evidence. This is diagnostic information only: an unmapped raw sink still cannot authorize
audible playback. The observer rejects evidence if its track changes during the query.

For hardware comparison, keep the release baseline from main `f829f616` and test the branch debug APK:

1. Clear diagnostics, start headset audio, then connect Android Auto without pressing transport controls.
2. Record whether it stayed audible, briefly paused and recovered, or stayed paused; export promptly.
3. Repeat headset Pause/Play, car Pause/Play, then headset Pause/Play and both dashboard output icons.
4. With Auto disconnected, replace the headset and press Play without manually choosing the new headset.
5. Connect Auto while deliberately paused; it must remain paused. Repeat a true headset disconnection.

The next no-pause decision uses the new pre-pause track evidence. A raw sink still reported during an
inventory gap is a lead, not proof of physical audibility. Actual route loss or Media3 focus loss still
requires safe interruption; this patch does not claim zero silence or successful hardware acceptance.

Implementation validation: the strengthened recovery regression failed on the unmodified baseline, then
passed with the fix. Formatter and strict `verifyDebug` passed, as did `:playback:testReleaseUnitTest`.
Debug/shared reports contain 2,951 tests; release playback contains 1,115 tests, all with zero
failures/errors/skips. Six tests were added, including a newer-selection reentrancy control. Format and
verification were run separately after the initial combined invocation raced on line-length analysis.
