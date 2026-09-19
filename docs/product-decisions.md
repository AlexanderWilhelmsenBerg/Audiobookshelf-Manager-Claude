# BookWave product decisions

**Classification:** Canonical settled product-decision ledger.

This file records **definitive owner-approved product and UX decisions from 2026-09-19 onward**. It exists so a
future issue, agent, ADR or implementation cannot quietly reopen a decision that was already made.

## Authority and use

- This file owns **what the product has definitively decided** when the decision is product behavior, UX,
  information architecture, supported workflow or an explicitly accepted product trade.
- `PRODUCT_SPEC.md` remains the requirements baseline. When a new decision changes or narrows an existing
  requirement, the same documentation change must reconcile the affected requirement instead of leaving two
  contradictory authorities.
- `docs/roadmap.md` remains the only sequencing authority. A decision here says **what**, not **when**.
- Accepted ADRs remain the authority for technical architecture and rationale. An ADR may explain how to
  implement a product decision, but must not silently change it.
- Risks, issue bodies, reviews, experiments and implementation notes are evidence. They are not definitive
  product decisions unless the settled outcome is recorded here.
- When the owner makes a new definitive product decision, the implementing PR — or a preceding documentation
  PR — must add or amend an entry here.
- Do not rewrite history to make an old decision look as though it never existed. Mark it superseded and point
  to the newer decision.

## Entry format

Each decision uses a stable `PD-###` id and records:

- **Status** — Accepted or Superseded;
- **Date** — when the owner settled it;
- **Scope** — the product surface it governs;
- **Decision** — the behavior that future work must preserve;
- **Consequences** — important boundaries or deliberately rejected alternatives;
- **Tracked by** — implementation issue/PR when applicable;
- **Supersedes** — older product decisions or prose that no longer applies.

---

## PD-001 — Android Auto browse home is Continue, Series, Authors, Profiles

**Status:** Accepted  
**Date:** 2026-09-19  
**Scope:** Android Auto browse information architecture, artwork and profile switching  
**Tracked by:** Forgejo issue #65

### Decision

The Android Auto root contains exactly these four destinations, in this order:

1. **Continue**
2. **Series**
3. **Authors**
4. **Profiles**

There is no general **Library** root in the Android Auto information architecture.

The **Series** destination is ordered by playback recency when BookWave has playback evidence:

- series with playback activity are ordered by their most recent played/progress activity, newest first;
- series without playback activity follow alphabetically;
- ties use alphabetical ordering for determinism.

Books inside a series keep the canonical series-sequence ordering required by LIB-003.

Android Auto browse surfaces should show artwork from BookWave's safe cached data wherever practical:

- books use their cached cover;
- series use a representative cached book cover;
- authors follow LIB-002's existing portrait/representative-cover policy;
- missing or unavailable artwork falls back cleanly to the host placeholder.

**Profiles** is a first-class Android Auto destination. Switching profile from the car uses the same profile
boundary as the rest of BookWave: active progress is preserved/flushed, playback pauses by default, the
profile context changes atomically, and the car browse tree is invalidated before the new profile's content
is presented. Existing profile-lock rules still apply; Android Auto does not become a lock bypass.

### Consequences

- **History is not part of the Android Auto browse library.**
- Do not recreate History under another car browse destination.
- The standard Android Auto **Queue** is not a BookWave product requirement. Do not synthesize History into
  the player timeline to make Queue behave like History.
- The old Android Auto **Library** subtree — including its Chapters/History/Downloads/Recently added/Listen
  again/Discover/Audio output grouping — is not the target information architecture after #65.
- Audio-output controls remain a player/system concern rather than a reason to keep a general Library root.
- Artwork exposed to a car/media host must be readable by that host without putting reusable credentials or
  tokens into metadata/URIs.
- The root stays at four stable destinations; future additions require a new product decision rather than
  silently appending a fifth item.

### Supersedes

For Android Auto browse information architecture, this supersedes the earlier ADR-0029 decision that fixed
the root as **Continue → Series → Authors → Library** and retained History/Chapters inside Library. ADR-0029
remains authoritative for the Android Auto playback/routing decisions that PD-001 does not replace.
