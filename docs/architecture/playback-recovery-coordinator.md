# Playback recovery consolidation — PR B

PR B builds on PR A (live endpoint identity). The behavioral goal is unchanged.

CarContinuityRecoveryCoordinator owns the recovery episode's route-securing attempt,
final one-shot authorization and diagnostic awaiting-Play record. It delegates to the
existing CarArrivalResumeGate for arrival/departure correlation and invalidation, and
CarArrivalRouteRecovery for bounded reassertion of exactly the captured output.

The Media3 service still owns ExoPlayer, actual Play, audio focus, callbacks, and
logging. The AudioOutputRouter still owns the physical route request and muted
AudioTrack verification. No additional playback state machine or session is created.

The tests reject repeated consume, deliberate Pause, empty queue, already-playing
intent, changed profile/book generation, newer explicit output-selection sequence,
wrong headset ID, stale asynchronous route securing, and mismatched diagnostic
completion. They call production components directly.

PR C follows only after B passes required checks, and will shorten service callback
adapters without moving Media3 session ownership. Physical car/headset audible-route
acceptance belongs to the combined C APK; CI cannot establish it.
