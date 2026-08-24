# ADR-0026 — The closeout decisions

**Status:** Accepted, 2026-08-24. Answers the five questions `docs/closeout.md` put to the owner. Narrows
ADR-0025's paging clause to a sequencing rule rather than reopening it. Supersedes nothing.

## Context

`docs/closeout.md` was written to be the single ordered list of what remains before v1. Writing it turned
up five questions that were not technical questions with a discoverable answer — they are the owner's, in
the same sense ADR-0024's four were, and they were asked rather than guessed.

Four of the five have an obvious-looking answer that is wrong in a way worth recording, which is why this
file exists instead of a line in a table.

## Decisions

### 1. Library browsing is restricted to trusted controllers; transport is not

**Decided:** browsing goes to trusted/system controllers, Android Auto and Automotive, and BookWave itself.
An untrusted external controller keeps basic playback control but may not enumerate the library. The check
must prefer Media3's own trust and capability predicates over a hard-coded package allowlist. A future
optional setting may re-open third-party browsing if a real need appears.

**What was actually wrong.** `MediaLibraryService` is `exported="true"` — it has to be, or the app vanishes
from Android Auto and Assistant — and `onConnect` handed `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` plus four
custom commands to whatever bound to it. Any installed application could enumerate the active profile's
book titles, which is exactly the private self-hosted data PRODUCT_SPEC 14.5 exists to protect, and could
write a bookmark into the library through `ADD_BOOKMARK`.

**What was already right, and stays.** `onAddMediaItems` gates a *pre-resolved* item — a bare URI or a
track list the player fetches as given — on the caller's **UID**, so no outside app could ever push a URI
of its choosing into the authenticated streaming client. That is the half that would have been serious and
it was closed deliberately in an earlier phase. The first draft of the closeout claimed there was no trust
model at all; that was wrong, and the correction is recorded in the commit that made it.

**Why not an allowlist.** An allowlist is a list of the cars somebody thought of in 2026. Media3 already
computes the fact that matters: `ControllerInfo.isTrusted()` is `MediaSessionManager.isTrustedForMediaControl`,
true for the system, for a holder of `MEDIA_CONTENT_CONTROL`, and for a notification listener the *user*
enabled. That set is maintained by the platform and by the user, and it is the same set every other media
app on the device relies on.

Three package-shaped checks remain and they are **Media3's own**, not this project's —
`isAutomotiveController`, `isAutoCompanionController`, `isMediaNotificationController`. They are needed
because both car predicates additionally require a *legacy* connection (`controllerVersion == 0`), so a car
connecting that way may not also be reported trusted. Losing Android Auto is a worse outcome than admitting
one more caller the platform already ships.

**Why two mechanisms.** The command set in `onConnect` is the real gate — Media3 checks granted commands
before dispatching to a callback. The five browse callbacks check again anyway, because this service also
advertises the legacy `MediaBrowserServiceCompat` action, a browse tree is private data, and the redundancy
costs four lines.

**The one behaviour most likely to need adjusting on a device:** the voice-search branch of
`onSetMediaItems` is gated with the rest. A caller that could not list the library but could ask it to play
a guessed title and watch whether anything started would have the same oracle by a slower route. Assistant
should reach it because the platform reports Assistant trusted — *should*, on the evidence of what
`isTrustedForMediaControl` returns, not on a device test this project has run.

### 2. Normal reauthentication preserves the passcode

**Decided:** signing in again must not destroy an existing profile passcode. Only an explicit
locked-profile recovery may clear it, and only after a clear warning.

`SignInUseCase` calls `clearIfLocked`, which is what makes the curtain's recovery route real and what lets
a card's *Sign in again* rescue an exhausted or unreadable lock record. It also fires from the ordinary
sign-in screen, where a user may only have meant to refresh an expired session and where nothing mentions
the lock at all. The guard fails **closed**, so a transient disk error during sign-in also reaches `forget`.

A security setting the user deliberately chose should not disappear as a side effect of an unrelated,
routine action.

### 3. Recents privacy is targeted, not global

**Decided:** suppress the Recents/app-switcher preview where Android supports doing so. Do **not** enable
global `FLAG_SECURE` for all users by default. Stronger screenshot protection may be tied to a locked
profile, or offered as a privacy setting.

`FLAG_SECURE` is a blunt instrument: it blocks screenshots and screen recording for everybody, including
the user who wants to send a screenshot of a bug to this project, and including accessibility and casting
paths. The app-switcher thumbnail is the actual exposure — a book list visible to whoever picks the phone
up — and Android offers a narrower control for it.

### 4. Paging waits for the measurement

**Decided:** do not introduce Paging yet. Run the 2,000-item benchmarks first and adopt it only if memory
or UI performance justifies the complexity.

This is ADR-0025's position unchanged, restated because the closeout put it back in front of the owner.
ADR-0025's words were *"the measurement may show paging is unnecessary"*, and the harness that takes the
measurement now exists. Adopting paging is a large change to `LibraryRepository`, `HomeViewModel` and every
screen that consumes a list; doing it on a hunch is how a codebase acquires an abstraction nobody can
remove.

### 5. Section 5 of the closeout stays closed for v1

**Decided:** the deliberately-deferred scope is not reopened as part of closeout.

Twelve items in that section look like gaps and are decisions with an ADR behind each — no grid built to
satisfy 17.3, source-file deletion not exposed because neither endpoint can prove it happened, batch
embedding not offered, four job states rather than twelve. A closeout that reopened them would turn a
release checklist into a second design phase.

## Consequences

- Closeout item 1.1 becomes implementation rather than a question. `ControllerTrust` is a pure decision
  function over a value type, tested on the JVM, with the *branch* extracted rather than a predicate —
  R-43 and R-55's lesson, where a complete truth table sat behind a caller that never reached it.
- **The residual is honest and recorded:** the adapter that reads Media3's six facts is not reachable from
  a JVM test, because `ControllerInfo` is final with a package-private constructor. That the policy is
  right is proven; that the service asks it is a device-tier check.
- Items 3.2 and 3.6 of the closeout gain a specified behaviour rather than an open question.
- The paging decision remains blocked on a device run, and is now blocked on *one named command* rather
  than on an unquantified judgement.
- The order of work is the owner's: browse-tree restriction, excluded-track offsets, passcode handling,
  then the device session, then Recents privacy, then paging, then the UX list.
