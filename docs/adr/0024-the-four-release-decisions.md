# ADR-0024 — The four release decisions

**Status:** Accepted, 2026-08-21. Supersedes the "Blocking open decisions" table in `docs/release.md` and
closes PRODUCT_SPEC 24 items 1, 2, 3 and 4.

## Context

PRODUCT_SPEC 24 lists open decisions that must be resolved by an ADR before a public build. Five concerned
release; one — whether source-file deletion can be exposed — was settled as *no* by ADR-0021. The remaining
four were not technical questions with a correct answer discoverable from the code. They are the owner's,
and they were asked and answered rather than guessed.

## Decisions

### 1. Application ID: `org.homebord.bookwave`

Reverse-DNS of a domain the owner controls, which is the convention and the only form anyone else can
verify. It replaces `com.example.shelfplayer`, which Play rejects outright.

**This is the `applicationId`, not the `namespace`, and the distinction is load-bearing.** Only the
`applicationId` is the install's identity on a device and in Play. Every module's `namespace` and every
Kotlin package remain `com.example.shelfplayer`, because renaming those would touch essentially every file
in the repository and Play neither sees nor cares about them. A later contributor who "finishes the job"
would spend a very large diff to change nothing observable.

Moved **now**, before any release, for the reason ADR-0019 gave when it declined to move it during the
rename to BookWave: Android identifies an install by its `applicationId`, so changing it after somebody has
the app installs a *second, empty* copy rather than renaming the first — costing a fresh sign-in and every
downloaded book. Before the first release there is no install to lose, which is why the cost is zero today
and permanent tomorrow.

### 2. Licence: GPL-3.0-or-later

`LICENSE` now carries the canonical GNU GPL v3 text, with the project's own copyright notice and an
explicit statement that BookWave is unaffiliated with the Audiobookshelf project.

ADR-0012's posture is unchanged and is what makes this a free choice rather than a forced one: this project
reads Audiobookshelf's source for API facts and has copied **no** code from it. Nothing obliged a copyleft
licence; the owner chose one.

#### The interaction with Play, stated rather than discovered later

GPL-3.0 and Google Play have a real tension. It is navigable — many GPLv3 apps ship on Play — but it should
be understood before the first upload rather than after:

- **Source availability.** GPLv3 obliges the distributor to offer source to anyone who receives a binary.
  The public repository satisfies this, and it must stay public for as long as builds are distributed.
- **Play App Signing.** Google holds the signing key. GPLv3 §6's *Installation Information* requirement for
  User Products is generally treated as satisfied because users can build from source and sideload, so
  nothing prevents them running a modified version. This reading is widely relied on and is not
  authoritatively settled.
- **The Developer Distribution Agreement** grants Google rights to distribute. This is compatible with
  GPLv3 in the ordinary case; the friction people cite arises with anti-modification or DRM terms, which
  this app is not subject to.

Nothing here requires a code change. It is recorded because a licence decision whose consequences are only
found at upload time is a decision that was not really made.

### 3. Distribution: Google Play

This settles the remaining shape of the release:

- an **App Bundle**, not an APK, is the artefact;
- **Play App Signing**, so no key material is in the repository — which `docs/release.md` already required
  and which stays true;
- a **data-safety declaration** is needed. It is short and honest for this app: the only network destination
  is the user's own Audiobookshelf server, there is no analytics and no crash reporting, and
  `PRIVACY.md` already says exactly that;
- **no reproducible-build requirement**, which F-Droid would have imposed and which would have constrained
  the build in real ways.

The `versionCode` rule follows from the channel: Play requires a strictly increasing integer per upload and
never permits reuse. It stays a hand-incremented integer in the application convention plugin. A derived
scheme — a timestamp, or a commit count — was rejected because both can go backwards or collide across
branches, and Play's refusal to accept a reused code is permanent for that package.

### 4. Minimum server version: 2.26.0, enforced at sign-in

`ServerVersion.Minimum` is the single place this is written down, and `SignInViewModel` refuses before a
password is typed.

**Why a floor exists at all, when SYNC-001's capability probe does not work this way.** The probe answers
"does this server do X", which is the right question for a feature the app can degrade without. The
authentication model is not such a feature: below 2.26.0 the server returns only the pre-2.26 `user.token`,
which is not refreshable. `POST /login` would succeed, everything would appear to work, and AUTH-004's
silent renewal would fail hours later on a device — reading to the user as a random sign-out with no cause
they could name. A probe cannot catch that, because the absence shows up only at the first renewal.

**Why 2.26.0 rather than 2.36.0.** Every contract fixture was captured against 2.36.0, and making that the
floor is tempting on the grounds that nothing else is verified. It would also refuse servers that would very
likely work, in exchange for a claim the app cannot substantiate either way — 2.30 is no more tested than
2.26. What *is* known is where the refreshable token arrived, and that is a behavioural boundary rather than
a testing artefact.

The accepted cost: **a server between 2.26 and 2.36 is allowed and unverified.**
`docs/api-compatibility.md` records it.

**The gate fails open, deliberately, and this is the opposite of the profile lock.** A version the parser
does not recognise is *allowed through*. A self-hosted server reporting `2.36.0-beta.1`, or `nightly`, or
nothing at all, is far more likely to be a working server with an unusual build string than a genuinely
ancient one — and refusing it would strand its owner with no way to argue. The gate refuses only what it
positively recognises as too old. `ServerVersionTest` asserts that direction explicitly, because it is the
kind of decision a later "tidy-up" reverses without noticing that it was a decision.

Contrast with AUTH-005, where every uncertainty resolves to *locked*. The rule is not "fail closed
everywhere"; it is that uncertainty resolves towards whichever outcome is recoverable. A lock that fails
open exposes an account. A version gate that fails closed locks somebody out of their own working server.

## Consequences

- `com.example.` is gone from the install identity, so a Play upload is possible. It remains in the Kotlin
  packages, on purpose.
- The repository must stay public while builds are distributed.
- A data-safety declaration has to be written at upload time; `PRIVACY.md` is the source for it.
- `versionCode` must be incremented by hand for every upload, and can never be reused for this package.
- Servers older than 2.26.0 are refused with a message naming the version they need.
- Servers between 2.26.0 and 2.36.0 are accepted without verification, which is a stated risk rather than
  an oversight.

## What this does not settle

Signing key custody beyond "Play holds it", the store listing, screenshots, and the content rating
questionnaire. None of those is a code decision and none blocks the build.
