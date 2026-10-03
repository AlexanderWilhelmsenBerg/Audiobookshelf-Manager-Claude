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
the original byline exactly when the timer becomes idle. (#77 later moved the countdown from the byline to the
title, because the byline was invisible on the compact media card in physical testing; see the amendment below.)

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
  second countdown owner or modify the book's identity inside BookWave (its own players keep the book title
  and progress).

### Amended 2026-10-03 — the sleep timer never shows in Android Auto

Owner decision: "A sleep timer should never show in Android Auto. I shouldn't sleep while driving."

- While an Android Auto or Android Automotive controller is bound (`CarConnections.isConnected`), BookWave
  projects no countdown into the session (the book title stays), leaves the timer button out of every
  media-button slot, does not grant the extend command to car controllers and refuses it if one sends it.
  The countdown and button return, within a second, when the car is no longer bound.
- The timer keeps running. Only its presentation changes. BookWave's own full player still shows it.
- Media3 has one shared session with no per-controller metadata or custom actions, so the phone's notification
  and lock screen also lose the countdown and the sleep/extend button for as long as a car is bound. This is a
  deliberate carve-out from "must visibly communicate the remaining time" above and from PLAY-008's notification
  wording.
- A plain Bluetooth car without Android Auto is not a Media3 controller and cannot be detected. It keeps the
  countdown as the title.
- Open owner question: the night schedule (BW-SLEEP-01) can arm a timer automatically when playback starts
  inside the window, which a night drive satisfies. A timer that is invisible in the car can then expire and
  pause the book mid-drive with no explanation on the car screen. Options: keep as is; do not auto-arm
  scheduled timers while a car is bound (recommended); or also cancel manual timers when a car connects.
  Not implemented until the owner decides.

---

## PD-003 — Downloads use one device copy, profile claims, and a device-level pin

**Status:** Accepted  
**Date:** 2026-09-27  
**Scope:** Shared offline media, profile download state, removal, pinning and cross-profile presentation  
**Tracked by:** Forgejo issue #21 and PR #94

### Decision

BookWave stores at most one physical downloaded copy for a server item on a device. Profiles do not own
separate media files; each entitled profile records its own claim on that shared copy.

When an entitled profile that does not yet claim a book selects **Download** and another local profile already
has a complete physical copy, BookWave adds the new profile claim immediately and does not download the media
again.

Removing a download is profile-scoped:

- the active profile's claim is removed;
- if another profile still claims the book, the physical media stays;
- physical media is removed only after the final claim is removed.

Pinning is device-level. The pin belongs to the one physical copy and protects it from automatic cleanup.
Every authorized profile that can see the copy sees and changes the same pin state.

### Presentation and privacy

A profile that is entitled to a book but does not claim it may be told that the book is **downloaded on this
device for another profile**. The UI must not reveal which other profile owns a claim. Selecting Download from
that state attaches the current profile to the existing copy.

A profile-scoped Remove, Pause, Resume or Retry action must not be offered merely because another profile has
a claim on the physical copy. Device-level storage management may still show the physical row subject to the
existing metadata-redaction rules.

### Consequences

- Download progress and listening progress remain profile-specific where they already are; media bytes are not.
- A shared-copy attach still performs the active profile's entitlement check. Knowing a server item ID is not
  sufficient to gain offline access to another profile's media.
- Existing database storage may represent the device pin redundantly on claim rows, but repository behavior
  must expose one logical physical-copy pin and keep those stored bits coherent.
- Losing or deleting one profile must never silently delete a physical copy that another profile still claims.


---

## PD-004 — The Book download button shows live percent and asks Pause or Stop

**Status:** Accepted  
**Date:** 2026-10-03  
**Scope:** The Book screen's download button, in-flight download controls, and what Stop does to shared copies  
**Tracked by:** GitHub #111 (Forgejo issue #21)

### Decision

1. **Joining a downloaded copy only adds a claim.** When the book is downloaded on this device for another
   profile, the Book screen says so, and tapping Download adds the active profile's claim without another
   transfer (PD-003). It never changes another profile's claim.
2. **Removal is profile-scoped.** The files are deleted only when the last claiming profile removes the
   download. Removing or stopping while other profiles claim the copy releases only the active profile's
   claim and never stops another profile's transfer.
3. **In flight, the button shows an integer percent from 0 to 99 inside the button, with a progress ring
   around it.** The percent never reads 100 while a transfer is running: completion is shown by the
   Downloaded state. Before the first byte the ring is indeterminate and the percent reads 0.
4. **Tapping an in-flight download opens a prompt: Pause, Stop or Keep downloading.** A tap never pauses or
   stops by itself.
   - **Pause** keeps everything downloaded so far and can be resumed. It is offered while the download is
     queued, running, waiting for a network or retrying, on the Book screen and on the Downloads rows alike.
     Pause is offered **only for a copy no other profile claims**: the transfer belongs to the physical
     copy, so pausing a shared one would stop the other profile's download too. For a shared copy the prompt
     offers Stop or Keep downloading, the Downloads row shows no Pause, and the pause itself is refused.
   - **Stop** releases this profile's claim. If it was the last claim, the transfer is cancelled and the
     partly downloaded files and the manifest are deleted. If another profile still claims the copy, only this
     profile's claim is released: the other profile's download continues and no files are deleted. The prompt
     says which of the two applies.
   - **Keep downloading** dismisses the prompt.
5. **A paused download shows its percent with a muted ring and no play glyph.** Tapping it resumes, without a
   prompt, because resuming loses nothing.

### Consequences

- The Book button reads WorkManager's execution evidence through the same recovery policy the Downloads rows
  use, so a retried download is never shown as Failed or stuck, and a paused one is never shown as
  downloading. Evidence is transient and never written to the database.
- Notifications stay per book; there is no group summary.
- Stop is the same claim-aware removal as Remove. The Book overflow's *Delete local item* is unchanged and
  still applies only to completed downloads.
- Copies left without any claim after a profile is removed are a separate, recorded risk and are not handled
  here.

**Supersedes:** the Book button's former "tap cancels" behaviour, which kept the partial files but left the
manifest unchanged, so the book looked stuck.
