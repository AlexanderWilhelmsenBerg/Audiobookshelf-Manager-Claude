# ADR-0023: The profile passcode is a curtain, not a vault

- **Status:** Accepted
- **Date:** 2026-08-21
- **Requirements:** PRODUCT_SPEC 3.2, 3.3 ("Profiles"), 8.12, AUTH-003, ROUTE-002, ROUTE-003;
  `docs/gaps.md` AUTH-005
- **Resolves:** PRODUCT_SPEC open decision **24.14** — "whether profile PIN/biometric protection is
  version 1 or 1.1"
- **Related:** ADR-0022 (`androidx.appcompat` weighed and refused once already), ADR-0004 (redaction is a
  property of the field type)

## Context

The specification asks for this lock five times and never once says what it defends against. 3.2 lists an
*"Optional profile PIN or biometric gate"*; 3.3 puts *"PIN/biometric lock"* among the per-profile settings;
8.12 wants it *"especially for admin accounts"*; ROUTE-002 contributes the only behavioural clause anywhere
in the document — *"Auto-play never starts when the active profile is biometric/PIN locked"* — and 24.14
leaves the feature open between version 1 and 1.1. `docs/gaps.md` collects those under the label AUTH-005;
the specification itself has no `AUTH-005` section.

The owner has now chosen version 1, and that choice needs the threat written down first, because every
decision below follows from the threat and none of them follow from the five sentences above.

**The threat is somebody holding this phone, already unlocked, who is not the account's owner** — a
household member picking it up from the table, a passenger handed it for directions. That person is
physically present, has as long as they like, and, which decides several things later, already holds the
device credential, because the phone is unlocked in their hand. The threat is not a stolen device, not an
attacker with the filesystem, not a rooted phone, and not the server's own authorisation model.

## Decision

**AUTH-005 ships in version 1, as a per-profile presence check in front of that profile's own screens.**
Six to twelve digits, or the platform's biometric prompt where the device has one and the user has asked
for it. Unlocks are tickets held in memory and nowhere else, so a cold start is locked and no reboot or
background kill can leave one open, and the relock delay is evaluated against the clock when the ticket is
read rather than expired by an event — otherwise the media service and the UI could give different answers
to the same question.

The curtain replaces the app's content rather than covering it, and `MainActivity` draws neither while the
state is still `Resolving`. An overlay would leave what it hides in the semantics tree, and `MiniPlayer`
marks its title as a polite live region, so TalkBack would read the locked account's book aloud over the
passcode field. A lock that announces what it is protecting is not a lock.

## What it is not, and the arithmetic that says so

AUTH-003 drew the boundary before this feature existed, and it still holds:

> Optional biometric locking protects profile selection, not server authentication semantics.

So, the arithmetic, which the product must never imply away. A six-digit passcode is a million
possibilities. At 210,000 PBKDF2-HMAC-SHA256 iterations each, exhausting that space costs roughly
2 × 10^11 hash iterations, which a single modern GPU gets through in minutes. **The verifier does not resist
an attacker who is holding the file.** Raising the cost does not rescue it: OWASP's 600,000 would roughly
triple a number that already loses, while making a five-year-old phone take about three seconds to reject a
typo — and a lock that slow is one its owner switches off, which protects nothing at all.

The Keystore wrap therefore earns exactly one sentence of claim, and this is it: reading the record requires
executing code on this device, because the key cannot leave the Keystore, so the file copied off the phone
is bytes nobody can use. Anything past that sentence would be invented. The rate limit — four free attempts,
then thirty seconds doubling to a fifteen-minute cap, and refusal at ten — sits inside the encrypted record
for the same modest reason: it cannot be reset by force-stopping the app or clearing its data, and it binds
the only attacker this design is for, the one who goes through the app.

Biometric unlock is app-enforced policy and not cryptography. The stored verifier is a one-way derivation,
so no fingerprint can produce it; the gateway asks the platform, believes the answer, and grants a ticket.
That is weaker than a key a biometric releases, and it is the only shape available, because the thing being
checked is a hash by design.

The rest is said in the product rather than only here. The curtain carries a "What this lock does not cover"
block naming four bypasses that exist and are not closed: the media notification and lock-screen transport
keep working, because this app has no interception point for a media button and ROUTE-001 treats one as
explicit intent; a connected car can still browse and play; downloaded audio is ordinary unencrypted files;
and the lock does nothing against somebody who can read this phone's files.

## Three separations, and each one is a bug that would have shipped

**A separate proto.** `AppSettingsDataSource.settings` catches `IOException` and emits
`getDefaultInstance()`. For a preference that is right, and the app stays usable. For a lock it is
**fail-open**: an unreadable record would read as "no passcode" and the curtain would never be drawn. The
record is read by `ProfilePasscodeStore` instead, where every failure to read, unwrap or parse resolves to
*locked*.

**A separate Keystore alias.** `shelfplayer.lock.v1`, not `shelfplayer.session.v1`, because sharing would
have caused three silent failures. `SessionTokenStore.clear(profileId)` iterates `SessionTokenKind.entries`
and would delete somebody's passcode when they signed out, which AUTH-003 says is not lock state at all.
`SessionTokenStore.clearAll()` calls `cipher.clear()` and would destroy the verifier's key — and an
unreadable record fails closed, so signing out of everything would leave the app permanently locked.
`storedCredentialCount()` counts distinct file stems to answer "how many accounts have a credential on
disk", so a lock file would inflate a diagnostic the owner reads.

**A separate directory.** `locks/` beside `sessions/`, which is what keeps that third count honest. File
names are hashes of the profile id for the reason `SessionTokenStore` hashes its own: a name reaches an I/O
error message and `adb shell ls`, and a server-derived identifier belongs in neither (PRODUCT_SPEC 14.5).

## No Room migration

The schema stays at version 19. There is no `isLockEnabled` column, no `AppSettings` field and no flag
anywhere: **the record's existence is the fact**, so there is no second source of truth to disagree with the
first — and the disagreement would not be symmetrical, because a flag reading "no passcode" beside a record
that exists means the curtain is never drawn. The `profiles` table was rejected for a second reason as well:
every other column there is server-derived and is rewritten by `ProfileDao.setAccountState` on each
permission refresh, so a local security setting in that table would be one careless `UPDATE` from gone.

## Stricter than the specification, twice, on purpose

**Arming is suppressed as well as auto-play.** ROUTE-002's sentence names only auto-play;
`AutoStartDecision` suppresses `ArmOnly` and `Ask` too. Arming makes no sound, and it puts the locked
account's title, author and cover on the lock screen, one headset press away from audio — and that press
cannot be intercepted, because there is no `onPlayerCommandRequest` and no `ForwardingPlayer` anywhere in
this app. An armed locked profile is a lock with a hole in it that the lock screen advertises.
`DevicePolicy.Never` still reports `None` rather than `Suppressed`: the lock changed nothing about that
device, and a log line claiming it did would be false.

**ROUTE-003's startup restore is suppressed, and ROUTE-003 has no lock clause at all.** Both remaining
modes, not only the one that makes a sound, because `RestorePaused` puts the same three pieces of the locked
account's book on the same lock screen. The two requirements are one event seen from different sides —
something other than a person's deliberate press is about to reveal or play a locked account's book — and
product priority 4 decides it where the document is silent.

Product priority 1 is untouched by both. `OutputDeviceWatcher` consults the guard *after*
`actions.isBusy()`, so a profile that is already playing is never interfered with, and that ordering is
structural rather than a matter of comment.

## Alternatives rejected

**`androidx.biometric`.** Three measured facts, and the second decided it. It resolves, but pulls in the
full `androidx.appcompat:1.6.1` where only `appcompat-resources` is on the classpath today — the same
dependency ADR-0022 declined for the language setting. More seriously, its API 26/27 compatibility path
constructs an `androidx.appcompat.app.AlertDialog`, which throws
`IllegalStateException("You need to use a Theme.AppCompat theme")` under this app's platform-parented
`Theme.ShelfPlayer`: a crash on the two oldest levels this app supports, in a code path no test in this
repository can reach, because there is no instrumented tier at all. Third, `strict` dependency verification
is on — 890 components and 1,618 checksums as this is written — so adopting it means regenerating that
metadata for an optional feature. What was *not* the objection: `androidx.fragment:1.5.1` is already on the
classpath, so `FragmentActivity` was never in the way.

**The device credential as an unlock factor.** `KeyguardManager.createConfirmDeviceCredentialIntent` is the
obvious fallback and it is refused, because the threat is somebody holding the already-unlocked phone and
that person has the device credential by construction. A factor the attacker already holds is not a factor.
So the biometric prompt's negative button dismisses to the passcode rather than offering the credential, and
the record's key sets `setUserAuthenticationRequired(false)` — requiring device authentication in order to
check a passcode would be circular as well as useless.

## Consequences

**API 26 and 27 get no biometrics at all.** `android.hardware.biometrics.BiometricPrompt` starts at 28, and
the older `FingerprintManager` would need this app to draw its own dialogue, could not report sensor
strength, and could not be exercised by any test here either. Those two levels see a row disabled with the
reason on it rather than a hidden one, because a hidden row was reported from a Phase 5 device run as an
unbuilt feature. The passcode is the floor on every supported release. On 28 and 29 the platform reports
only "some biometric", so no strength claim is made there; `BIOMETRIC_STRONG` is asked for from 30.

**An unreadable record is a locked profile.** A lock-screen change that invalidates the key, or a restore
onto new hardware, produces exactly that, and the curtain says so in those words instead of saying "wrong
passcode" to somebody whose correct passcode cannot work. Clearing it needs re-authentication, which is what
AUTH-003's backup criterion asks for.

**The escape hatch is the account password, which makes clearing the record part of signing in.** Somebody
who can sign in has cleared a strictly higher bar than six digits, and AUTH-003 already says this lock is
not about server authentication — so it is a feature rather than a bypass, and its cost is on screen before
the passcode is set: it needs the server to be reachable, so it is no help offline.
`SignInUseCase` makes that call, through `LockedProfileRecovery.clearIfLocked`, and the curtain carries the
password field that reaches it — so the route the copy describes is one the code now keeps. It is
conditional on purpose: a profile that was already unlocked is somebody re-authenticating after an expired
session, and their lock is left exactly as they set it.

**A locked profile that is not the active one is opened from the switcher, not the curtain.** The curtain
reads `activeProfileId`, so it draws for one profile only, and `SwitchProfileUseCase` refuses a locked
target *before* it becomes active. Those two rules first met in a dead end: the refusal said "Enter its
passcode to switch to it" and the app contained no field in which to. `ProfileSwitcherViewModel` now asks
`ProfileLockRepository.isLocked` before attempting the switch and opens a passcode dialogue instead of
producing an error. `isLocked` is defined as the negation of the same `mayActivate` the use case calls, so
the prompt and the refusal cannot disagree about one profile. The dialogue offers no biometrics — stacking
the platform's activity-level prompt on a dialogue is a shape nothing here can test — and no recovery
field, because re-authenticating destroys a passcode and that does not belong behind a gesture meaning
"show me my other account". A profile whose record is exhausted or unreadable is served by the card's own
*Sign in again*, which is the route that clears it.

**Nothing obliges an admin account to take a passcode.** 8.12 asks for the lock *"especially for admin
accounts"*, and what ships is offered per profile with no policy behind it. That half of 8.12 is unmet
rather than satisfied by proximity.

**The automated evidence is JVM tests only, and what they reach is worth naming exactly.**
`PasscodeKdfTest`, `AutoStartDecisionTest` and `ProfileLockGateTest` cover the derivation and its policy,
ROUTE-002's truth table, and the gate's clock arithmetic. `LockCurtainScreenTest` covers the curtain under
Robolectric, including the disclosure block and the two states that offer no passcode field.
`ProcessLockWatcherTest` covers the caller none of the others could notice was missing —
see the paragraph below. `ProfileSwitcherViewModelTest` covers the switcher's prompt.

**The Keystore wrap and the biometric prompt are exercised by nothing here, and cannot be.** Both need a
device. That is recorded so the covered half is not read as coverage of the whole; R-07 holds the absent
instrumented tier.

**A component's arithmetic being tested is not the same as it being reached.** `ProfileLockGateTest` passed
in full while the lock was inert on a device, because nothing in production called `onBackgrounded` — so
`isUnlocked` was true for the life of the process and all three relock delays behaved identically. Four of
the seven defects found in this feature after it first looked finished were of that shape: correct code
nothing reached. R-43 records the habit that catches it — grep for callers once a component builds.

**`docs/gaps.md` changes on both sides.** The AUTH-005 row closes, and the ROUTE-002 row marked *"Blocked on
AUTH-005"* is unblocked by a decision function rather than by a promise.
