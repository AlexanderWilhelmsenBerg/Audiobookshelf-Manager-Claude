# Recovery-to-Play effects — Phase 3

This describes the unmerged Phase 3 implementation stacked on
[draft PR #292](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/292).

## One owner for the effect transaction

`CarContinuityResumeEffects` now groups formerly service-local logic for
securing the **previously captured exact headset**, performing the final
generation/explicit-selection/precheck assessment, obtaining a muted headset
probe, and issuing **at most one** Media3 Play after the recovery coordinator
consumes its one-shot claim.

It introduces **no mutable recovery state** and no additional player,
MediaLibrarySession, car lifecycle authority or output-route policy.
`CarContinuityRecoveryCoordinator` still owns episode eligibility, invalidation,
route recovery and consumption. `AudioOutputRouter` still owns preferred-output
requests, guard/muted probe, and physical AudioTrack verification. The enclosing
`PlaybackService` retains the one ExoPlayer, all system callbacks, lifecycle,
profile/book ownership, focus and event dispatch.

The extracted component receives the live ExoPlayer through a service-owned
provider instead of storing another player. Its effects are invoked on the
existing service/Media3 dispatcher, never from the audio-device callback.
The same current route generation and explicit-selection sequence are read
before and after suspended route securing; pending work may not resurrect
Play for an old book, old output intent or detached session.

## Fail-closed behavior

A player whose `playWhenReady` is already true keeps Media3's native focus
restoration; the effect does not reassert or issue duplicate Play. A missing
player, empty queue, invalid generation, or rejected recovery claim cannot
issue Play. Even a successful preferred-device request is not route proof:
a headset recovery must establish a muted verification probe, and only a
confirmed playing AudioTrack at the exact endpoint permits unmuting.
Unknown, competing and wrong physical sinks remain silent or paused.

Diagnostics retain route **kind**, event phase and private-safe snapshot
fields, never the raw headset name or endpoint ID. `onIsPlayingChanged`,
not the issuance of Play, reports actual playback becoming active.

## Regression evidence

`CarContinuityResumeEffectsTest` verifies the real effect boundary does not
request any route or player operation when Media3 already has active Play
intent, and that emitted diagnostics do not leak endpoint IDs.
`CarContinuityPlayPrecheckTest` retains the final claim-before-single-Play
reachability invariant using the extracted source, while the Phase 2 S01–S22
and service-level Robolectric scenarios guard recovery ordering, cancellation
and one-shot consumption.

The hardware headset button handoff remains separately unresolved (#276/#277).
No new hardware destination attribution is introduced; progress HTTP 401
belongs to #288.

## Phase 3 continuation — diagnostic boundary and stale-effect rejection

The effect transaction now accepts a privacy-safe diagnostic emitter supplied by
`PlaybackService` instead of depending directly on `AndroidAutoTraceRecorder`.
The existing recorder still owns event correlation, elapsed timing and log privacy;
no second trace clock or emitter state was created.

The direct Robolectric/coroutine test covers a suspended route-availability wait
interrupted by either a replacement book generation or a newer explicit output
selection. Both must reject the old claim without Play or sleep-timer resume,
and diagnostics name only the invalidation reason. The existing active-intent,
no-duplicate-Play, route evidence, scenario and Media3 tests remain required.
