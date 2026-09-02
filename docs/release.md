# Release

`PRODUCT_SPEC 18` defines the pipeline and `PRODUCT_SPEC 25` the acceptance checklist. This records the
process, what it already does, and what still blocks a public build.

**As of:** 2026-08-20, entering Phase 6. Phases 1–5 are complete.

## Blocking open decisions

**All five are now settled.** ADR-0021 closed source-file deletion; ADR-0024 closed the other four, after
the owner was asked rather than guessed at.

| Decision | Settled as |
| --- | --- |
| **Application ID** | `org.homebord.bookwave` — reverse-DNS of a domain the owner controls. Only the `applicationId` moved; every module's `namespace` and every Kotlin package stay `com.example.shelfplayer`, because Play neither sees nor cares about those and renaming them would touch every file for no observable effect. Moved before the first release, which is the only moment it is free (ADR-0019). |
| **Licence** | **GPL-3.0-or-later.** `LICENSE` carries the canonical text. ADR-0012's posture is what made this a free choice: this project reads Audiobookshelf's source for API facts and copied none of its code, so nothing obliged copyleft. ADR-0024 records the real interaction with Play — source must stay available, and Play App Signing versus GPLv3 §6 rests on a widely-relied-on but not authoritatively settled reading. |
| **Distribution channel** | **Google Play.** An App Bundle, Play App Signing (so still no key material in the repository), and a data-safety declaration whose content `PRIVACY.md` already supplies. No reproducible-build requirement, which F-Droid would have imposed. |
| **Minimum server version** | **2.26.0**, enforced in `SignInViewModel` before a password is typed. Below it the server issues no refresh token, so AUTH-004's silent renewal would fail hours later and read as a random sign-out. Chosen over 2.36.0 because the refreshable token is a behavioural boundary while 2.36.0 is a testing artefact. Accepted cost: 2.26–2.36 is allowed and unverified. |
| **Whether true source-file deletion can be exposed** (`MGR-006`) | **No.** ADR-0021. Both endpoints exist and neither can prove the deletion happened. |

## Versioning

`versionCode` and `versionName` live in the application convention plugin. The channel decided the rule:
Play requires a strictly increasing integer per upload and never permits a code to be reused for a package,
so ~~it stays a **hand-incremented integer**~~. A derived scheme — a timestamp, or a commit count — was
considered and rejected: both can go backwards or collide across branches, and Play's refusal of a reused
code is permanent. See ADR-0024.

**Amended.** The build now names itself after the **pull request** it came from: a build of pull request 72
is `0.9.5.72`, code `72`. The rejection above stands as written and does not reach this scheme — GitHub
issues pull request numbers from one monotonic per-repository counter, so unlike a timestamp or a commit
count they cannot go backwards and cannot collide between branches. `BuildIdentity` carries the reasoning
next to the code.

Why it was amended: the hand-incremented pair went stale exactly as R-04 predicted. The name sat at
`0.9.6-auto-shelves` for nine builds once, and had reached dozens of pull requests at
`0.9.14-browse-and-genres` code 40 by the time this changed. Every device-test result recorded in such a
window names the wrong build, and a tester holding two APKs cannot tell them apart.

| | |
| --- | --- |
| **`0.9.5`** | The product version, still hand-bumped, and only when the product moves. |
| **`.72` / code `72`** | The pull request, supplied by CI. `apk.yml` resolves it from the ref and passes `BOOKWAVE_PR`. |
| **No pull request** | A local build, or `main` after a merge: `0.9.5.local` at code `BASE_VERSION_CODE` (40). |
| **The commit** | `BOOKWAVE_COMMIT`, shown as the **Build** row in Settings → About and in the debug console. |

**The one constraint the amendment keeps: a Play upload must be built from a pull request**, or
`BASE_VERSION_CODE` must first be raised past the last code Play accepted. An unnumbered build reports 40,
which is below every pull request number this repository has issued, and Play's refusal of a reused code is
permanent.

## Signing

**No key material lives in this repository and none ever may.** What changed on 2026-08-27 is that the
build now *accepts* a key from outside it, because the previous state — no signing configuration at all —
meant the release variant could not be installed on any device and there was no supported way to produce
an upload artefact either. That was the whole of "the release build is not installable".

The default is unchanged: supply nothing and the release APK is unsigned, exactly as before. `main.yml`,
which is push-triggered, supplies nothing and stays unsigned — `PRODUCT_SPEC 18` forbids signing in a
workflow a push can start.

### Locally

Put the four values in `~/.gradle/gradle.properties`, which is outside the checkout:

```properties
bookwave.signing.storeFile=/home/you/.bookwave/upload.jks
bookwave.signing.storePassword=…
bookwave.signing.keyAlias=upload
bookwave.signing.keyPassword=…
```

Then `./gradlew :app:assembleRelease` produces a signed, installable APK. Verify it with
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

Generate an upload key with, and keep the answers somewhere a lost laptop does not take with it:

```bash
keytool -genkeypair -v -keystore ~/.bookwave/upload.jks -alias upload   -keyalg RSA -keysize 4096 -validity 10000
```

On Windows PowerShell 7, use the interactive helper instead. It locates this repository's JDK, keeps the
key outside the checkout, hides passwords while they are entered, and safely updates the user Gradle
properties. It never overwrites an existing key; it can explicitly adopt and verify one instead:

```powershell
& .\scripts\device-test\06-create-signing-key.ps1
```

### In the Build APK workflow

Set four repository secrets and choose the `release` variant:

| Secret | Value |
| --- | --- |
| `BOOKWAVE_SIGNING_KEYSTORE_BASE64` | `base64 -w0 ~/.bookwave/upload.jks` |
| `BOOKWAVE_SIGNING_STORE_PASSWORD` | the keystore password |
| `BOOKWAVE_SIGNING_KEY_ALIAS` | `upload` |
| `BOOKWAVE_SIGNING_KEY_PASSWORD` | the key password |

The run summary reports signed or unsigned by asking `apksigner` about the artefact, not by checking
whether a secret was set.

### The debug build has its own key, and the upload key must not be it

**Set nothing.** The debug build signs itself with a stable key at `~/.bookwave/debug.keystore`, created by
the build on first use, and the four values above have nothing to do with it.

That is a change from what this section used to say. The four inputs used to sign the debug build as well,
on the reasoning that a stable key turns `adb install -r` into an upgrade — the sign-in, the passcode, the
progress journal and every downloaded book surviving instead of being wiped. The reasoning was right; using
the *release* inputs to achieve it was not. It made a debug APK's signature depend on **whether four
environment variables were set in the shell that ran Gradle**, so a release build in one terminal and a
debug build in another produced two differently-signed debug APKs — and every switch between them cost the
uninstall the arrangement existed to prevent. A tester reported having to uninstall before every build.

`DebugSigning.kt` now owns the debug key, and nothing else can change it:

- **It adopts `~/.android/debug.keystore` if you have one**, copying it rather than generating something
  new. That is what makes this free: the signature you have been using is the one you keep, and installs
  already on your device keep working.
- **On a machine with none — a fresh checkout, a CI runner — it generates one** with Android's own public
  debug credentials, so a keystore it created and one the SDK would have created are interchangeable.
- **It refuses a path inside the repository**, the same refusal the upload key gets.

`bookwave.signing.debug.stable=false` opts out and restores AGP's own behaviour.
`bookwave.signing.debug.keystore` (or `BOOKWAVE_DEBUG_KEYSTORE`) points it somewhere else — which is how a
second machine or a runner shares one key. The Build APK workflow reads
`BOOKWAVE_DEBUG_KEYSTORE_BASE64` for exactly that.

Measured on 2026-08-30: the generated `~/.bookwave/debug.keystore` and the APK built from it both report
`aa5fd8f7…`, matching the `~/.android/debug.keystore` it was copied from — so adoption does what it claims
and no uninstall was needed.

The earlier measurement, on 2026-08-27, is what established that an unsigned debug build is not stable
across machines:

| | Certificate SHA-256 |
| --- | --- |
| No key, build 1 (generated keystore deleted between builds, as a fresh runner has none) | `e005ff57…` |
| No key, build 2 | `aa5fd8f7…` — **different**, hence the uninstall |

The `benchmark` variant follows the debug key automatically: it pins `signingConfigs.debug`, and this
changes that config rather than the build type, so it stays installable on a machine with no upload key.

### An unsigned release now says so, while it is building

```
BookWave: this release APK will be UNSIGNED and cannot be installed.
```

Printed by `assembleRelease` itself, not at configuration time, so it appears when it applies and not on
the hundreds of Gradle invocations that never build a release. It exists because "the release APK is not
installable" was reported from a device session as a mystery, and the build had known the answer all along
and said nothing.

### The three things the build refuses

`build-logic`'s `ReleaseSigning.kt` fails the build rather than warning, in each case:

- **a partly supplied set** — a keystore without an alias would otherwise produce a silently *unsigned*
  APK that fails at install with a message about the package rather than about the alias;
- **a keystore inside the checkout** — `.gitignore` lists `*.jks` and `*.keystore` and is advisory: one
  `git add -f`, one rename to `upload.key`, or one archive of the working tree defeats it. R-05 predicted
  exactly this, and the refusal is the part that cannot be bypassed by accident;
- **a keystore that is not there** — named at configuration time rather than as a signing-task stack
  trace four minutes in.

A relative path is resolved against the home directory, never against the project.

### What this is not

It is not the key end users verify against. ADR-0024 chose Play App Signing, so Play holds that key and
re-signs every upload; this is an *upload* key, and losing it is recoverable by asking Play to reset it.
Signature schemes are left to AGP, which selects them from `minSdk`: at 26 every supported device
verifies v2, and an explicit `enableV1Signing = true` was tried, observed to be ignored, and removed.

## The pipeline today

`.github/workflows/pull-request.yml` — Gradle wrapper validation, secret scan, `verifyDebug` with
warnings-as-errors, Room schema diff, debug APK, dependency report.

`.github/workflows/main.yml` — the above plus release lint and an unsigned release assembly. It stays
unsigned deliberately: it is push-triggered, and `PRODUCT_SPEC 18` allows signing only in a workflow
somebody starts on purpose.

`.github/workflows/contract-capture.yml` — captures response shapes from a real server on demand
(`PRODUCT_SPEC 22.5`).

`verifyDebug` itself fans out to every module: ktlint, detekt with type resolution, Android Lint with
warnings as errors, the unit suite, and Kover's coverage gate over domain and core. Dependency
verification is `strict` over 890 pinned components.

**Verify with `--rerun-tasks`.** Gradle can consider a test-compile task up to date when only its
classpath changed, which once let two stale test doubles pass locally and fail in CI. See `docs/risks.md`
R-31.

## Getting an APK without building one

GitHub → **Actions** → **Build APK** → *Run workflow*. Choose the branch and `debug` or `release`; the APK
lands on the run's summary page under **Artifacts**, which a phone can download directly. `run_checks`
adds `verifyDebug` first, off by default so a quick device build stays quick.

`workflow_dispatch` only. Every pull request already runs `verifyDebug` and `main` runs the full release
gate, so an APK built for a commit nobody asked about is storage and runner time for an artefact that
expires unread.

**The release variant installs only if the four signing secrets are set** — see *Signing* above. Without
them it is unsigned and is for inspecting what R8 produced; with them it is an ordinary installable APK.
The run summary says which, having asked `apksigner` rather than assumed. The release run also uploads the
R8 mapping, because an APK kept without its mapping is one whose crashes cannot be read.

The artefact is named from the version read back **out of the built APK**, not out of the build script.
R-04 is why: a `versionName` that had not moved in nine builds made every field report name the wrong
build.

## Building this locally

`./scripts/check-local-environment.sh` reports whether this machine can build, test and device-test the
app, and `--install` adds any missing Android SDK packages. `docs/handover.md`'s "Running this locally"
section explains each requirement and which task it gates.

## What must be added before a release

| Step | Requirement | Blocked on |
| --- | --- | --- |
| ~~Restrict the exported session's browse tree~~ | AUTH-003, PLAY-001, 15 | **Done 2026-08-24 — ADR-0026.** All three parts now closed: the bearer reaches only its issuing origin, `onAddMediaItems` gates pre-resolved items on the caller's UID, and `ControllerTrust` splits library access from transport using Media3's own trust predicates. A device check that Android Auto still browses is the residual (R-60). |
| ~~Make profile switching an ordered playback transaction~~ | 6.5, AUTH-002, PLAY-005 | **Done 2026-08-23.** `PlaybackHandover` pauses, awaits the flush, clears the queue and releases before `setActiveProfile` runs; every write carries an explicit owner. The two-origin buffered-playback device run is still worth doing and is a device item, not a build one. |
| ~~Capture or gate privileged management writes~~ | 17.1, MGR-001/002/007, USER-002 | **Done 2026-08-23.** Cover upload, metadata embed, user activate/deactivate and the item update behind the genre mutation all have captured fixtures for the permitted *and* the refused shape, against a real 2.36.0, replayed by `AbsManagementContractTest`. |
| ~~Correct automatic-download traffic policy~~ | DL-004, SET-002 | **Done 2026-08-23.** `DownloadScheduler.enqueue` takes the `TrafficCategory`, so an automatic download is constrained by the smart rule and a manual one by the manual rule; the setting that nothing read now decides something. |
| Managed-device tests | 18, 17.2 | CI has no emulator. Still the largest single hole, though no longer a total one: `:core:datastore` has an instrumented suite over the profile lock's storage; its first physical run passed 27/27 on an API-36 Samsung on 2026-08-23. No other module has one, and none of it runs in CI. |
| Two-hour playback soak; process-death progress budget | 25, 17.3 | A device and patience. No new infrastructure. |
| Android Auto verification in the Desktop Head Unit | 17.2 | Nothing. An older build passed discovery/media-button resume in a car on 2026-08-14, but the current browse tree and rendered host surface have not run in DHU/a head unit. Phone screenshots cannot substitute for a car host. |
| Launch the release APK once | 15 | Nothing, and it is now possible: the release variant can be signed from a key held outside the repository, so the R8 build can be installed. A signed APK was produced and `apksigner`-verified on 2026-08-27 with a throwaway key; launching one on hardware is the remaining step. |
| The four 17.3 numbers, and the baseline profile | 17.3 | The `:benchmark` module exists and is compiled on every pull request. What is missing is a run: `./gradlew :benchmark:connectedBenchmarkAndroidTest` with a phone attached, then `docs/benchmark.md`'s results table filled in and the recorded `baseline-prof.txt` committed. |

## The Software Bill of Materials

`./gradlew :app:sbom` writes CycloneDX 1.5 JSON to `app/build/reports/sbom/bom.json`, and the main-branch
workflow uploads it beside the R8 mapping as `release-supply-chain`. **175 components, 130 of them with a
pinned SHA-256.**

It is a task in `build-logic` rather than the `org.cyclonedx.bom` plugin, because this build already holds
every input the document needs and a plugin would add its own transitive tree inside `strict` dependency
verification. It reaches no network.

**Two sources, each for the one thing it is authoritative about.** Which components ship comes from
`releaseRuntimeClasspath`'s resolution result — the graph *after* conflict resolution. It does not come
from `verification-metadata.xml`, which lists every version Gradle ever resolved metadata for and would
name four versions of `androidx.activity` as shipped when only 1.10.1 is. Integrity comes from
`verification-metadata.xml`, because that is where this project's pinned checksums live; re-hashing the
Gradle cache would only prove the cache agrees with itself.

**Reading the fields honestly:**

- **`hashes`** is the SHA-256 of the component's shipped binary, selected by extension from what the
  metadata actually lists rather than by constructing a file name. That distinction is not pedantic:
  AndroidX publishes its AAR as `animation-release.aar`, not `animation-1.8.3.aar`, and a first version of
  this task that built the expected name found hashes for 96 of 175 components — which read as "this
  project does not pin most of its dependencies" when in fact it pins all of them.
- **45 components carry no hash**, every one of them with a
  `shelfplayer:hash-absent` property saying why. All 45 are `no-binary-published`: a Kotlin Multiplatform
  parent such as `androidx.annotation:annotation`, whose binary is published as `annotation-jvm`, or a BOM
  that publishes only a POM. **None is `not-pinned`** — the value that would mean a binary reaches the
  application without a pinned checksum, which `strict` verification should make impossible and which
  **fails the task** rather than appearing quietly in the document.
- **`licenses`** is copied verbatim from each component's own POM `<licenses>` and is **omitted** when the
  POM declares none — which is the case for five components today, among them Guava and protobuf-javalite.
  An omitted licence means *the publisher did not state one in its POM*. It does not mean the component is
  unlicensed, and nothing here is an audit. SPDX identifiers are not inferred from the free text, because
  mapping "The Apache Software License, Version 2.0" to `Apache-2.0` is a judgement this build is not
  entitled to make on a publisher's behalf; CycloneDX's `license.name` exists for the quotation.

The document's own metadata component carries `GPL-3.0-or-later` (ADR-0024), written as a literal in the
convention plugin so that changing the project's licence has to touch that line — an SBOM naming the wrong
licence for the work itself is the one field in it nobody would think to check.

## The dependency vulnerability scan

`./scripts/vulnerability-scan.sh` reads the SBOM and asks OSV whether any component has a known advisory.
It runs in the main-branch workflow immediately after `:app:sbom`, and it exits non-zero on a finding.

**As of 2026-08-21 there are no known advisories against any of the 175 components.** That is a result
rather than a default: the script was checked against a poisoned SBOM carrying
`org.apache.logging.log4j:log4j-core:2.14.1`, and it reported all seven Log4Shell advisories and failed.

`curl` against OSV's documented batch endpoint rather than a third-party scanning action. Every other
action in this repository is first-party — `actions/*` and `gradle/*` — and the SBOM already carries every
purl the query needs, so the scan introduces nothing new to trust.

**It says nothing about reachability.** An advisory in a transitive library the app never calls fails the
build exactly like one in a library it calls on every screen. That is the right default for a release
gate: deciding a vulnerability is unreachable is a judgement that belongs in a written exemption, not in a
script's silence. There is no exemption mechanism yet, and the first time one is genuinely needed is the
right time to design it.

## The release note, and where it stops

`.github/release.yml` maps pull-request labels to sections, so GitHub's own "Generate release notes"
produces the note for a tag. No action and no script — the same first-party-only reasoning that kept the
vulnerability scan to `curl`.

**It does not replace `CHANGELOG.md`, and the division matters.** The generated note is an index of what
merged. The changelog is where a decision is *explained* — why the version gate fails open while the lock
fails closed, why the passcode is a curtain, why `applicationId` moved before the first release. None of
that fits in a pull-request title, and it is the part of this project's history worth keeping.

The labels, in the order the note presents them: `breaking`, `migration`, `playback`, `downloads`,
`library`, `sync`, `auth`, `security`, `auto`, `routing`, `accessibility`, `layout`, `bug`, `build`,
`tests`, `supply-chain`, `docs`. `chore` and `dependencies` are excluded from the note. **Anything
unlabelled lands in "Other changes" rather than being dropped**, so a forgotten label costs a misfiled line
and never a missing one.

Nothing enforces a label. A check that failed a pull request for missing one would block the fix for a
labelling mistake, which is a poor trade for a note nobody reads twice.

## Pre-release checklist

Use `PRODUCT_SPEC 25` verbatim. Do not mark an item complete on the strength of a happy-path screen;
`PRODUCT_SPEC 21` requires error, loading, empty, offline and permission states, accessibility semantics,
and evidence that nothing private is logged.

Repeated device runs have found defects that the entire unit suite passed through — eight in the audit of
2026-08-16, four more on 2026-08-20, and on 2026-08-23 false grouped counts plus a Genre Edit action that
the clickable browse card intercepted. Several were the same shape: correct code that could not be reached
from the UI. **A green build is not a tested build.**
