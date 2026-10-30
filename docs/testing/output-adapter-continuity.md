# Output adapter and prompt continuity verification

**Status:** Implementation candidate; final gates and device acceptance recorded in issue #289.
**Requirements:** PLAY-002/004/008/009, ROUTE-001/002.
**Design:** [Approved adapter plan](../architecture/playback-output-adapter-plan.md).

## Behavior and ownership

AndroidLocalOutputAdapter owns Android inventory callbacks, live platform handles, preference requests
and explicitly labeled framework prediction. It delegates appearance identity to the existing registry.
It owns no player, selection, proof, progress or audio focus. Its application-scope callback outlives a
player attachment; scope completion unregisters it, and queued/late callbacks cannot publish after retirement.
The router reads that inventory rather than retaining another handle map. Auto projection and actual-track
observation remain supplied by the existing service wiring. No remote playback implementation is added.

Healthy preservation reads current AudioTrack observations twice and checks observer epoch, unique live
endpoint, collisions, attachment, book/selection scope and playing state. No framework prediction or
selected preference can establish this result. Reasserting an already measured healthy headset does not
issue a preferred-device request, seek, mute or Play. New intent delivered during measurement invalidates
the old operation before any preference effect.

Eligible paused recovery distinguishes actual measured output from an exact destination ready for a
silent probe. `ProbeReady` means only the latter. Recovery can proceed to the existing one-shot muted
Play without consuming the two-second wait for a paused track to report an actual route. The 400ms
fallback route refresh remains asynchronous and scoped to the request; it does not block that Play.
Missing original endpoints wait on inventory publication; unresolved attempts can wake on state changes
before the bounded retry. None of those events or timers authorizes audible release.

Actual target proof, the current transfer/probe claim and the existing held-position protocol still govern
unmute. Final 0–50ms settling, the two-correction limit, wrong/unknown/multiple-route refusal, cancellation,
rollback, Media3 focus and progress/history/profile/sleep semantics remain in force. A replacement endpoint
cannot inherit identity by name. Virtual keys remain unattributed.

## Software evidence

The pre-extraction characterization passes 18 tests. The platform extraction passes 179 production-path,
callback-lifetime, actual-Media3, route-safety and service tests after moving fixture inventory to its new
owner. An earlier extraction run failed two fixture assumptions about the old field location/source marker;
the assertions were retained and the fixtures corrected.

The corrected timing counterfactual runs unchanged recovery with four tests: three intended failures and
one healthy-arrival control passes. The failures cover unnecessary healthy-route reassertion, prompt silent
recovery on an available paused endpoint, and prompt recovery after that exact endpoint appears. The first
attempt left the paused fixture reporting an active route and is not evidence for those two delay cases.

Feature formatter and strict `verifyDebug --continue -x assembleDebug
-Pshelfplayer.warningsAsErrors=true` pass: 2,831 tests across 341 suites, including 1,007 playback tests;
zero failures, errors or skips. Lint, Detekt and coverage pass. The first full run passed all runtime tests
but failed function-count/return-count checks and the extracted adapter's missing Media3 opt-in. Removing
an uncalled router API, consolidating reassertion scope checks and declaring that opt-in fixed the gate;
no rule or baseline was weakened. A subsequent comment-only encoding repair does not change behavior.
The final combined gate and normal exact-head CI cover the committed candidate.

Agent00 reviewed the changed ownership, current callers and source. Additional independent review could
not run because the available reviewer hit its account usage limit; it remains outstanding. Physical file
lengths are router 991 (previously 1012), adapter 101 (new), recovery 140 (131), resume effects 311 (310).
PlaybackService remains 3410 and the endpoint registry 78. This is focused ownership improvement, not a
claim that the entire playback hotspot has been eliminated.

Final combined verification, exact source, normal CI, code-health comparison and artifact identity must
be taken from the current issue checkpoint. This document does not predeclare those results or a physical PASS.

## Device acceptance

Use the single combined signed candidate after its exact-head verification passes. Retain APK version/source
with each capture. Run stationary, recording audible destination separately from dashboard state:

1. Start audible headset playback, connect Auto, and repeat arrival at least five times. Target no interruption;
   any interruption must be less than one second. Check for no speaker/car spill or skipped/repeated narration.
2. Pause/Play on the headset while it owns output. Use explicit Car, then explicit Headset, then Car again.
   Check the intended route and continuous heard position after every transfer.
3. Connect/disconnect Auto while paused. It must stay paused; subsequent deliberate Play must remain usable.
4. Exercise endpoint loss/replacement, a competing output, and a focus interruption. Unverifiable output must
   fail closed; a deadline or guessed identity must never restore volume.
5. Repeat relevant cases with diagnostics off, and include background/service recreation in the wider matrix.

Measure the interval from the last audible headset sample to resumed audible headset audio. Diagnostic
timestamps locate focus loss, endpoint publication, ProbeReady, proof preparation and completion, but do not
measure acoustic silence themselves. Report every duration and worst observed gap. Android focus/endpoint
unavailability can exceed the target; record that as a failed end-to-end latency case, not as a passing app-only
timing result. Physical headset-button attribution remains a separate capability limitation.
