# ADR-0014: A shake restarts the sleep timer, and every timer is recorded locally

- **Status:** Accepted
- **Date:** 2026-08-08
- **Requirements:** PRODUCT_SPEC PLAY-008, SET-002 (Playback). **Extends** PLAY-008 in two places — see below.
- **Decided by:** the project owner

## Context

PLAY-008 already specifies a sleep timer, and specifies it well: the preset lengths, end-of-chapter, the
fade, survival across activity recreation, and — explicitly — *"optional shake-to-extend requires
explicit opt-in and must not run motion sensing continuously when no timer is active"*.

So the feature was never a gap. What the project owner asked for differed from the written requirement
in two ways, and both are worth recording rather than quietly implementing.

## Decision 1: the shake **restarts**; the notification action **extends**

PLAY-008 says the shake *extends*. The owner asked for a shake that *restarts*. On a timer part-way
down these are different:

| Timer | Shake, as written (extend by 30 min) | Shake, as asked (restart) |
| --- | --- | --- |
| 30 min set, 1 min left | 31 minutes | 30 minutes |
| 30 min set, 25 min left | 55 minutes | 30 minutes |

Both are implemented, on different controls:

- **The notification action extends**, exactly as PLAY-008 says. It is a deliberate press on a control
  the listener is looking at, and adding to what is left is what "extend" means.
- **A shake restarts.** It is a gesture made in the dark by somebody who has lost track of how long is
  left, and "put it back to what I set" is the only answer they can predict. Under the extend rule, the
  same gesture gives a different result depending on information the listener does not have.

An **end-of-chapter** timer has no length of its own, so a restart moves it to the end of the *next*
chapter. Restarting it to the current chapter's end would be a shake that changed nothing, which reads
as the feature being broken.

### What did not change

The opt-in and the sensor bound. `ShakeDetector` is registered when a timer starts and unregistered when
it ends, so there is no state in which the accelerometer is running and no timer is. That is the half of
PLAY-008's shake requirement that is easy to get wrong, and it is enforced structurally: the detector
holds no "enabled" flag of its own that could leave a listener registered.

## Decision 2: every timer is recorded locally

PLAY-008 says nothing about a history. The owner asked for one — "local tracking of when sleep timer
starts, and stops" — and it earns its place for a reason the requirement did not anticipate:

**"I set a timer last night and the book was still playing this morning" is unanswerable without one.**
A sleep timer is used by somebody who is asleep before it fires. Every other feature in this app can be
verified by watching it; this one cannot, and the only evidence of what happened is what the app wrote
down.

A row records when a timer started, how it was set, when and why it ended, and **how many times it was
pushed back**. That last number is the one that makes the log worth keeping: a timer restarted eleven
times is a person fighting to stay awake, and the pattern is not visible from any single night.

`SleepTimerOutcome` distinguishes four endings, and the distinctions are the point:

| Outcome | What it tells the user |
| --- | --- |
| `Expired` | It worked. |
| `Cancelled` | You turned it off. |
| `PlaybackStopped` | The book ended, or you started another one, first. |
| `Abandoned` | The app was killed while it was running — so nobody knows whether the book kept playing. |

### What the record deliberately does not contain

**No media titles.** A row stores a book *id*, and the screen resolves the title from Room at render
time. A screenshot of the history — which is exactly what somebody would attach to a bug report — says
when timers ran and how they ended, and nothing about what was being listened to (PRODUCT_SPEC 14.5,
SET-002's privacy defaults).

The table is bounded at 200 rows per profile and pruned on each new timer, and it cascades on profile
deletion (PRODUCT_SPEC AUTH-002): removing an account removes what it did.

## Consequences

- **Two controls with different semantics** need explaining once, in the UI copy, and are documented
  here so a later reader does not "fix" the inconsistency by making the shake extend.
- **A schema migration** (version 8) for a table the requirement did not call for. Additive, tested
  both ways — data survives, and the new table accepts a write — but it is a migration that exists
  because of a decision recorded here rather than one traceable to PRODUCT_SPEC.
- **PLAY-008's "custom" length is not implemented.** The eight presets and end-of-chapter are; a free
  number entry is not, and it is listed as outstanding rather than quietly dropped.
- **Sensing depends on hardware.** A device with no accelerometer starts the timer and cannot shake it.
  `ShakeDetector.start` reports that rather than pretending, and refusing to set a timer on such a
  device would be an optional feature breaking a required one.
