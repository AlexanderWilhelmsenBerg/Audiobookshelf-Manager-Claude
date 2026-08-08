# ADR-0015 — The skip buttons default to thirty seconds both ways

- **Status:** Accepted
- **Date:** 2026-08-08
- **Requirement:** PLAY-007

## Context

PLAY-007 says the skip intervals are "independently configurable from 5–120 seconds" and that "defaults are
15 seconds back and 30 seconds forward".

The asymmetry is a common convention and a defensible one: a listener skipping back is usually recovering a
few seconds they missed, and a listener skipping forward is usually getting past something. Fifteen and
thirty encode that.

The project owner asked for **thirty both ways**, twice — first when the interval was hardcoded ("Default 30
seconds, but can be set in settings") and again when reviewing the player. This is their app, and the
requirement's default is a default rather than a constraint.

## Decision

`SkipIntervals.Default` is thirty seconds in each direction. Everything else PLAY-007 asks for is
implemented exactly: both directions are configurable, independently, over the full 5–120 second range, and
the configured value is what the buttons, the media session and the notification all use.

## Consequences

- A build read against PLAY-007's literal wording will find one number different. `PlaybackControlsTest`
  asserts the 30/30 default explicitly so that changing it back is a deliberate edit rather than something
  that happens because somebody read the requirement and not this file.
- The skip glyphs had to stop being `Replay30`/`Forward30`, which have the number drawn into them. They now
  follow the configured interval and fall back to a plain arrow when Material has no glyph for the chosen
  value — a button reading "30" that jumps forty-five seconds is worse than one with no number at all. The
  amount is always in the content description.
- Nothing about the *range* is affected, so a user who prefers the requirement's asymmetry can set it in two
  taps under Settings → Playback.

## Alternatives considered

**Ship 15/30 and let the owner change it.** Rejected: they asked for a default, not for the ability to
change it, and a default they have to correct on every install is not a default.

**Make the default asymmetric but configurable per profile.** Rejected as scope with no request behind it.
The setting is device-wide like every other playback control, for the reason the proto records: how far a
skip jumps is a property of the person holding the phone, not of whichever account is signed in.
