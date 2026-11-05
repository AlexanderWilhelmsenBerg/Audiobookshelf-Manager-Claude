# ADR-0033 — Route mute and sleep gain share one volume-effect owner

**Status:** Implemented candidate; strict full software verification passes (2,651 tests); device acceptance pending.
**Requirements:** PRODUCT_SPEC PLAY-002, PLAY-004, PLAY-008, ROUTE-002.

## Context

The headset route guard muted the raw Media3 player directly while the sleep timer also wrote that same
player's volume for fades, pause, and cancellation. A timer tick could therefore undo a route-verification mute.
The timer and listened-time accounting also treated the still-playing, internally-muted interval as audible.

## Decision

One Hilt singleton `PlaybackVolumeEffects`, shared by the output router, headset guard, sleep timer, and
session-sync coordinator, is the only writer of `Player.volume`. It binds the exact current player, keeps the
latest requested sleep gain, and applies route-mute claims as a temporary zero-volume overlay. Probe claims are
opaque identities; an old claim can restore only its own retired player's latest gain and cannot write to or
unmute its successor. Detach retires that player's claims without synthesizing an audible resume.

The sleep timer and listened-time owner receive synchronous mute-boundary callbacks on the existing Media3
application dispatcher. They freeze their elapsed accounting at the injected monotonic clock boundary and
resume it only when the current player is still actually playing. Raw `isPlaying` callbacks during a route mute
do not consume an explicit Play marker or independently rearm a scheduled timer. A real Pause remains
authoritative and retains normal sleep-gain restoration behavior.

Any deferred unmute reconciliation carries the existing player, book generation and Play-request generation.
A replacement book, new transport command or another mute revokes the queued work before it can consume
eligibility or start a timer for a successor.

Timer persistence effects live in `SleepTimerHistory`, leaving phase and generation checks in the controller.
Book-history identity, position, owner and time are captured before asynchronous dispatch. Provisional session
ids return synchronously to their claim; retirement preserves close-then-sync ordering. This reduces the timer's
dependency and effect surface without creating a second timer or durable state owner.

Route verification itself still owns no transport intent: it issues no Play or listener Pause. Existing
position journaling, progress/history ownership, sleep policy, Android audio focus, and the single Media3
player/session remain unchanged. The later verified headset handoff and rollback transaction remains separate.

## Consequences

- No route probe or sleep-timer tick can independently overwrite the other component's volume effect.
- Timer countdown and listened duration exclude internally-muted route verification without fabricating a
  user-visible pause or session transition.
- JVM/Robolectric evidence proves state and volume writes, not audible behavior on real Android outputs. Device,
  headset, and Android Auto acceptance remains pending.
- Position compensation, journal checkpoints during transfer, and full verified handoff/rollback acceptance
  remain outside this decision and must not be inferred from these tests.
