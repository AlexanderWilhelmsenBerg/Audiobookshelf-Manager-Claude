# Output settling regression from build 2242

Requirements PLAY-002/004/008/009, ROUTE-002 and SYNC-002. Device acceptance failed on 29 October 2026 for
combined source `27c1a921dc0c`: the owner reported silence on car arrival, jumbled audio after the dashboard
Headset choice, and headset Pause/Play controlling car audio. The diagnostic is retained privately, not as
a repository fixture.

The trace records initial Media3 focus loss, disappearance/re-enumeration of the protected Bluetooth
appearance, and a paused retained route. It later records 64 buffering/Ready transitions and 13 accepted
SeekCompleted writes in a five-second explicit-transfer window; the full race capture contains 46 such
seek writes. Repeated AudioTrack observation resets accompany that window. This supports investigating
the compensation feedback path; it does not measure human-heard output or prove a physical button source.

Three deterministic regressions fail against unchanged playback source `0c966ed6`: a clock advancing
40ms after message delivery, a seek that buffers before returning to Ready, and excessive advancement
that must pause rather than repeat correction. A late-route positive control already passes. The old
proxy kept Ready and its position exact after every seek, so it missed the feedback exposed by the device.
The pinned real Media3 service tests remain required for ordinary synchronous seek provenance and later
genuine user seeks; delayed SEEK_ADJUSTMENT is not established as the observed cause.

The historical 2242 correction followed ADR-0035: buffering does not trigger another seek, a truly playing unknown-route
interval gets at most one extra compensation, and a final fresh target proof permits at most 50ms of
normal media movement. Larger/backward movement restores and pauses. The bound is rechecked after
success publication with the prior lock still available. Tests cover the inclusive boundary, a late second
unknown interval, cancellation of its barrier and movement inside the success observer. A small bounded
residual is explicit; uninterrupted audio and zero silent media advancement are not claimed.

The real pinned Media3 integration test uses a normal-speed ExoPlayer and production silence source with
actual seek callbacks and playback-thread message delivery. Only endpoint observations are injected; it
does not establish a physical route. Read-only service logging records actual compensation deltas and
settling refusals, so a repeat diagnostic can distinguish these effects from other seek requests without
exporting media/device metadata or assigning a physical input origin.

The saved foreground-service deadline crash in this export occurred on 27 October against earlier source
`476d53e1f6f0`, not this 2242 playback session. It remains relevant to the separate #295 startup matrix;
absence of a new crash here does not complete that acceptance. Ordinary successful requests do not test
the #288 expired-token path. Virtual key input ID -1/source 0 remains physically unattributed.

The restored feature candidate passes strict `verifyDebug --continue -x assembleDebug` with warnings as
errors: 2,793 tests /329 suites /969 playback, zero failures/errors/skips, plus formatter, lint, Detekt
and coverage. The focused gate passes 76 tests across six suites. Removing the final publication check
causes its regression to fail; exact source bytes were restored before full verification. Normal CI and
the forced combined-source gate remain separate checks pinned in issue #289; these numbers are software
evidence, not acceptance of a new APK or proof of physical sound.

Record exact source, strict gates, normal CI and replacement APK identity in issue #289 and the draft PR.
Repeat the stationary device procedure using the replacement candidate before accepting #276/#277:
Auto arrival, explicit Headset/Car transfer, pause/resume, repeated connect, genuine route loss, held
position/history/progress, sleep, and foreground/background/cold entry. The diagnostic's initial real
endpoint loss may still require a safe pause; this software correction does not promise audio through an
absent route or automatically infer a headset from a virtual transport event.

## Superseding transaction candidate

The 2246 report reproduced distortion with only one recorded correction and three track attachments in
the complete capture, so repeated compensation is not established as its native cause. The current
[transaction design](../architecture/output-handoff-transaction.md) waits for the destination before one
restoration, then proves the resulting output. It never spends a second live correction. Buffering/late
proof outside 0–50ms pauses before failure restoration. Existing tests now enforce that stricter behavior.
Bounded opt-in native timing plus Media3 speed/pitch evidence supports the next physical investigation;
no new clock-health heuristic or physical headset-key attribution is introduced.
