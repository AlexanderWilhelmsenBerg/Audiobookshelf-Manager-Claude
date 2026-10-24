# Playback recovery coordinator

The A/B/C foundation is merged. The behavioral goal remains unchanged in the
unmerged Phase 3 continuation for issue #289.

CarContinuityRecoveryCoordinator owns the recovery episode's route-securing attempt,
final one-shot authorization and diagnostic awaiting-Play record. It delegates to the
existing CarArrivalResumeGate for arrival/departure correlation and invalidation, and
CarArrivalRouteRecovery for bounded reassertion of exactly the captured output.

The Media3 service still owns ExoPlayer, audio focus, callbacks, and logging.
`CarContinuityResumeEffects` executes actual Play against that service-owned player
and owns its cancellable effect lifetime. The AudioOutputRouter still owns the physical route request and muted
AudioTrack verification. No additional playback state machine or session is created.

The tests reject repeated consume, deliberate Pause, empty queue, already-playing
intent, changed profile/book generation, newer explicit output-selection sequence,
wrong headset ID, stale asynchronous route securing, and mismatched diagnostic
completion. They call production components directly.

Final consumption and scoped cancellation match the gate-issued Target instance,
so an older suspended completion cannot clear a newer claim, even for identical
route/generation/selection values. Diagnostic awaiting records are likewise cleared
only by their own effect or a real global invalidation. Physical car/headset
audible-route acceptance remains separate; CI cannot establish it.
