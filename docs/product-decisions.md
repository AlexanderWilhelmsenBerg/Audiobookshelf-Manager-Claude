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

BookWave requests an artwork-first grid/card presentation for Continue, Series, Authors and the book rows reached
through Series/Authors. Profiles stays a text-first list so active/locked/account state remains legible. The
Android Auto/automotive host still owns the exact background, card chrome, spacing and typography; BookWave
supplies its shipped teal accent to platform media surfaces where the host honours the app accent rather than
attempting to draw a custom car background.

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

---

## PD-002 — Sleep timer stays visible and shake restart has a bounded grace window

**Status:** Accepted  
**Date:** 2026-09-22  
**Scope:** Sleep-timer system surfaces, shake-to-restart behavior and settings  
**Tracked by:** Forgejo issue #77

### Decision

When a sleep timer is active, Android system media controls must visibly communicate the authoritative remaining
time even in the compact/background presentation. A timer glyph alone is not sufficient. The existing
notification/session action still extends the same playback-owned timer.

On Android 13 and newer, where System UI renders the media card from the MediaSession, BookWave may add the
localized sleep countdown to the current media byline while the timer is active. It must preserve and restore
the original byline exactly when the timer becomes idle.

Shake-to-restart remains explicit opt-in. After a timer naturally expires and pauses playback, motion sensing
may remain active for a configurable grace period of at most ten seconds. The default is ten seconds and the
listener may turn the grace period off. A qualifying shake inside that window starts a new timer session with
the same timer mode and resumes playback; a shake after the window has no effect.

Shake sensitivity is configurable as **Low**, **Normal**, or **High**. Normal preserves the behavior that
predates this setting, High is easier to trigger, and Low requires a more deliberate movement.

### Consequences

- Motion sensing is never an indefinite background listener. It exists only for an active opted-in timer or
  its bounded post-expiry grace window.
- Manual cancellation, disabling shake-to-restart, changing books, or playback-service teardown clears stale
  grace state.
- Post-expiry grace owns only the pause created by that expiry. A newer Play, Pause, Stop, listener seek/media
  replacement, or service-owned continuity resume supersedes that authority. A valid grace resume enters the
  existing resume-freshness owner rather than issuing an independent raw Player Play.
- A grace shake after an automatically scheduled timer expires is explicit listener intent, but it does not
  override the schedule end boundary.
- Natural expiry still records/closes the expired timer and performs its progress sync. A grace restart is a
  new timer session rather than rewriting the completed history entry.
- The MediaSession metadata projection is shared with Android system media surfaces; it must not create a
  second countdown owner or modify the book title/progress identity.

