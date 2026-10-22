# Generation-aware preferred-output effects — Phase 3

This is the stacked, unmerged Phase 3 route-effects slice. Its base is draft
[PR #291](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/291),
which itself is based on the Phase 2 regression branch in draft PR #290.

## What was extracted

`RoutePreferenceEffects` provides one monotonically increasing *effect generation* and
runs preference-setting effects on the **existing Media3 application dispatcher**.
It owns no device identity, selection, remembered output, audible-route evidence,
listener origin, mute state, focus state, player, or car lifecycle. Those remain in
`AudioOutputRouter`, `RouteHeardOwnership`, `CarContinuityRecoveryCoordinator`
and the single `PlaybackService`.

`AudioOutputRouter` invalidates earlier queued route preference work whenever:
- a player is attached or detached;
- the listener explicitly selects an output, Automatic or Car (including repeated null choices);
- a car recovery reasserts the original guarded headset;
- the router starts protecting an already-audible headset without changing its route;
- a selection falls back after a device disappears; or
- the same guarded headset returns and the router reapplies it without Play.

All requests still use the exact live process-scoped endpoint mapping and
`setPreferredAudioDevice` on the Media3 dispatcher. One preference-write is
synchronous on that dispatcher. A suspended 400ms settle of an older generation
cannot republish the result of a superseded command. Car-driven background
route *observations* (`resettle`) are not listener preference commands and remain
independent. No queue/actor is added, and we do not infer a successful physical
route from an AudioManager preference result.

Why **output ID equality** was insufficient: two successive Car/Automatic choices
both request `null`; a policy-protected headset and an earlier queued request can
also race before Media3's dispatcher runs. The router now also guards effect
generation, not just `selectedId`.

## Test contract

`RoutePreferenceEffectsTest` verifies repeated equal-target supersession,
mid-delay generation changes, dispatcher confinement, and silent protection.
`AudioOutputRouterThreadTest` tests actual router behavior: repeated Car/Automatic
choices produce only the latest preferred-device call; detaching the player
invalidates queued work; and an ExoPlayer proxy throws if its preferred-device
write crosses the player thread. Existing S01–S22 route-evidence and cancellation
tests stay in the stack.

**Safety boundary:** a requested or OS-predicted route is still not proof of the
playing `AudioTrack` or what the listener heard. Unverified output must fail
closed, and the physical headset-button handoff remains open under #276/#277.
