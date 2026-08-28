# Archive

Documents that described work that is finished. They are kept, not deleted, because several of them are
the **only** record of why something is the way it is — a defect found on a device in June, the reasoning
behind a decision that is now just a line of code, a delta test that explains why a threshold is 10 seconds
and not 5.

**Nothing in here is current.** Numbers, checklists and "what is left" sections describe the state of the
project on the day they were written. For where things stand now:

| Question | Document |
| --- | --- |
| What is left to do | [`../closeout.md`](../closeout.md) |
| What is known to be wrong, and what was done about it | [`../risks.md`](../risks.md) |
| What is built versus what the spec asks for | [`../gaps.md`](../gaps.md) |
| How to test the current build on hardware | [`../device-test-0.9.14.md`](../device-test-0.9.14.md) |
| How the code is arranged, and why | [`../architecture/overview.md`](../architecture/overview.md) |
| What the server actually returns | [`../api-compatibility.md`](../api-compatibility.md) |

## What is in here

**Phase 1 — sign-in, profiles, the library.** `phase-1-acceptance.md` is the acceptance run; the five
`phase-1-delta-test-*.md` files are the re-tests after each fix, in version order;
`roadmap-to-phase-1-close.md` and `phase-1-remaining.md` tracked what was outstanding at the time.

**Phase 2 — playback.** `phase-2-plan.md` and `phase-2-gaps.md` set the work out; the eight
`phase-2-*-device-test.md` files are the device runs, one per wave and one per feature that needed its own;
`phase-2-closeout*.md` closed it.

**Phases 3 and 5 — downloads, and the management tools.** `phase-3-plan.md` and `phase-5-plan.md`.

## Why the phase vocabulary is gone from everywhere else

"Phase 2", "wave 3" and "after wave 5" were useful while the work was being sequenced and became noise
once it was done — they date a document without describing it, and a reader has to know the project's
history to know whether a thing labelled "wave 3" is current. Live documents are named for what they are
about. This directory is where the sequencing lives.
