# ADR-0012: The official Audiobookshelf app is an API reference, not a source

- **Status:** Accepted, and reversible by the owner
- **Date:** 2026-08-07
- **Requirements:** PRODUCT_SPEC 22.4, 22.5, 24; CLAUDE.md ("no copied GPL code without a recorded
  licensing decision")
- **Related:** ADR-0007 (contract capture and licensing), ADR-0008 (AudioBooth as an API reference)

## Context

`github.com/advplyr/audiobookshelf-app` is the **official** Audiobookshelf mobile app, published under
**GPL-3.0**. It was offered as a Phase 2 reference, explicitly including the option of copying from it.

It is a real candidate rather than a courtesy one. Unlike the two clients ADR-0008 covers, it is not
Swift: it is a Vue/Capacitor app with a substantial **native Kotlin Android layer**, including a media
service, and that layer solves the same problem `PLAY-001` describes.

## The licensing position

GPL-3.0 is strong copyleft. Copying code from it into ShelfPlayer would make ShelfPlayer a derivative
work that must itself be distributed under GPL-3.0 — not the file, not the module, the whole
application, permanently.

That is a decision the project owner is entitled to make. It is recorded here because `CLAUDE.md`
requires it to be recorded, and because it is not reversible: a project can move to GPL-3.0, but it
cannot move back out once code has been taken in.

Note also that this is the **official** app. `PRODUCT_SPEC 24` and `CLAUDE.md` both bar official
branding without a recorded decision, so its name, icon and assets are out regardless of what happens
with its source.

## Decision

**Read it for API facts. Do not copy code.** Same posture as ADR-0008, for a stronger licence.

This is the default, and it is chosen on merit rather than caution:

- **The valuable part is not copyrightable.** Phase 2 needs what the *server* does — how a playback
  session is opened, what `POST /api/items/{id}/play` returns, what a session sync accepts, how the
  server reconciles two devices' progress. Those are facts about a protocol. ADR-0007 already
  establishes that this project learns them by capturing fixtures, and `PRODUCT_SPEC 22.5` requires a
  fixture before any of it may be relied on. A fixture is worth more than borrowed code, because it
  keeps working when the server changes and it fails loudly when it does not.
- **The code would not transfer well.** The parts most worth reading are wired to Capacitor's bridge
  and to a Vue front end. What survives translation into Compose and Hilt is the *approach*, which is
  not what a licence protects.
- **The cost of the conservative choice is zero and reversible.** Nothing written under this decision
  needs undoing if the owner later chooses GPL-3.0; the project would simply gain an option it does not
  have today. The reverse is not true.

## What "read for API facts" permits, concretely

Permitted: reading it to learn which endpoint to call, what parameters it takes, what the response
contains, in what order requests must happen, and which server versions behave differently. Recording
those in `docs/api-compatibility.md` and turning them into capture targets.

Not permitted: copying source, translating a file, or reproducing its structure closely enough that the
result is recognisably the same expression.

Every endpoint learned this way still needs a captured fixture before the app relies on it. Reading the
official app tells us where to point the capture script; it is never itself the evidence.

## Consequences

- Phase 2 proceeds without waiting on a licensing decision, because the default costs nothing.
- ShelfPlayer's licence is unchanged.
- If the owner decides otherwise, this ADR is superseded rather than contradicted, and the change is
  additive: existing code stays, new code may be copied, and the project relicenses to GPL-3.0.

---

## Amendment, 2026-08-15: the server repository, on the same terms

Phase 5 needed four API facts that no capture this project can run would produce, and reading
`advplyr/audiobookshelf-app` produced none of them — the official mobile app has no management surface
at all. The answers are in `advplyr/audiobookshelf`, the server itself, which is also **GPL-3.0**.

**This ADR's decision extends to it unchanged: read it for API facts, do not copy code.** Same licence,
same reasoning, same limits — and one that binds harder here than it did for the app.

The app was read to learn what a client *sends*. The server is read to learn what it *answers*, which is
closer to the thing this project must reproduce, and therefore closer to the line. So the rule is applied
strictly:

- What may be taken is the **observable HTTP behaviour**: the route, the parameters, the status codes, the
  shape of the body, the order things must happen in, and which of them are gated on what. Those are facts
  about a protocol that any integrator could establish by watching the wire with enough patience. Reading
  the source is a shortcut to the same knowledge, not a different kind of knowledge.
- What may not be taken is anything that is the server's *implementation*: its structures, its algorithms,
  its naming, its control flow. None of it is needed. This app does not implement Audiobookshelf; it talks
  to one.
- Every finding is written in this project's own words, marked as source-derived rather than captured, and
  is still subject to PRODUCT_SPEC 22.5 — the app does not rely on any of it until a fixture exists. The
  first section of `docs/api-compatibility.md` written this way says so in its own opening paragraphs.

Reading the server also settled a question in the *other* direction, which is worth recording as the kind
of thing this permission is for: it established that the server **cannot** confirm a source-file deletion,
which is why MGR-006 ships no feature (ADR-0021). A capture would never have found that — every capture
that route can produce is a success, including the ones where the file is still there.
