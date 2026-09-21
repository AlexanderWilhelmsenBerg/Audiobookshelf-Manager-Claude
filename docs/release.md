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

**Amended, then corrected.** The first amendment made the build name itself after the **pull request** it
came from — pull request 72 built as `0.9.5.72`, code `72` — on the argument that GitHub issues pull request
numbers from one monotonic per-repository counter and so, unlike a timestamp, they cannot go backwards.

**That argument was about the repository, and the installer does not care about the repository.** A phone
compares the code it holds against the code it is offered. The only order that matters is the order somebody
installs in, and a tester works through open pull requests as they become ready rather than in numeric
order: installing 70 and then 67 offers a lower code and Android refuses it outright
(`INSTALL_FAILED_VERSION_DOWNGRADE`, which the package installer reports as a bare "App not installed" with
no reason, indistinguishable on screen from R-68's signature mismatch). Reported from a device on
2026-09-02. The unnumbered case was worse for the same reason: `main` after a merge fell back to code 40,
below every pull request number in the repository, so no post-merge build would install over any test build.

**What stands now.** The code is a counter that only ever goes up, and the name is a plain product version
that encodes nothing:

| | |
| --- | --- |
| **`versionName`** | `0.10.6.1`. The product version, hand-bumped for the Forgejo migration while preserving the existing suffix. It carries no per-build fact, so there is nothing in it to go stale. |
| **`versionCode`** | `BASE_VERSION_CODE` (2000) **+ the workflow run number**. `apk.yml` passes `BOOKWAVE_RUN_NUMBER`; Forgejo increments it on every run of that workflow. The floor was raised during the Forgejo migration because the new CI system starts a fresh run-number sequence. |
| **Which source branch** | `BOOKWAVE_BRANCH`, shown as the **Source** row in Settings → About. The Forgejo APK workflow resolves the selected branch to an exact commit before checkout. |
| **The commit** | `BOOKWAVE_COMMIT`, shown as the **Build** row beside the build type. |
| **A local build** | No run number, so code `2000` and branch `local`. It will not install over a later CI build; `-Pbookwave.versionCode=N` is the way round that. |

Why any of this was needed at all: the hand-incremented pair went stale exactly as R-04 predicted. The name
sat at `0.9.6-auto-shelves` for nine builds once, and had reached dozens of pull requests at
`0.9.14-browse-and-genres` code 40 by the time it changed. Every device-test result recorded in such a
window names the wrong build, and a tester holding two APKs cannot tell them apart.

**Two things still make codes go backwards, and one edit fixes both.** A local build falls back to the floor;
and the run number restarts when the workflow moves to a new CI counter. The Forgejo migration is one such
restart, so the floor moved from 1000 to 2000. Raise `BASE_VERSION_CODE` past the highest code already
installed whenever that happens again. The same edit is what a Play upload needs if its predecessor used a higher code:
Play requires a strictly increasing code and its refusal of a reused code is permanent, which is the one
part of the original decision that no scheme softens.

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

### In the Forgejo Build APK workflow

Set these four Forgejo repository secrets:

| Secret | Value |
| --- | --- |
| `BOOKWAVE_SIGNING_KEYSTORE_BASE64` | base64 of the upload/signing keystore |
| `BOOKWAVE_SIGNING_STORE_PASSWORD` | the keystore password |
| `BOOKWAVE_SIGNING_KEY_ALIAS` | the configured alias |
| `BOOKWAVE_SIGNING_KEY_PASSWORD` | the key password |

The Forgejo **Build APK** workflow is manual-only and deliberately uses this same signing identity for both
the debug and release APKs. Debug remains a separate install because its application ID is
`org.homebord.bookwave.debug`; the shared certificate only makes the signing identity stable. The workflow
fails if any of the four secrets is absent and verifies the certificate fingerprint from the finished APK
against the staged keystore before uploading the artefact.

### Local debug builds still default to their own stable key

Outside the protected Forgejo Build APK workflow, the debug build still defaults to
`~/.bookwave/debug.keystore`. `DebugSigning.kt` also supports an explicit
`BOOKWAVE_DEBUG_KEYSTORE` plus credentials; Forgejo uses that supported override to point debug at the
same external upload key as release.

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
second machine or a runner shares one key. The legacy GitHub Build APK workflow can restore a dedicated
`BOOKWAVE_DEBUG_KEYSTORE_BASE64`; the Forgejo Build APK workflow instead sets `BOOKWAVE_DEBUG_KEYSTORE`
to the protected upload/signing keystore so debug and release deliberately share the same signer there.

Measured on 2026-08-30: the generated `~/.bookwave/debug.keystore` and the APK built from it both report
`aa5fd8f7…`, matching the `~/.android/debug.keystore` it was copied from — so adoption does what it claims
and no uninstall was needed.

The earlier measurement, on 2026-08-27, is what established that an unsigned debug build is not stable
across machines:

| | Certificate SHA-256 |
| --- | --- |
| No key, build 1 (generated keystore deleted between builds, as a fresh runner has none) | `e005ff57…` |
| No key, build 2 | `aa5fd8f7…` — **different**, hence the uninstall |

The `benchmark` variant follows the debug signing config automatically. In ordinary local builds that is
the local stable debug key; in the protected Forgejo APK workflow the explicit debug-keystore override points
the config at the shared signing key.

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

Forgejo is the active CI path. `.forgejo/workflows/pull-request.yml` is deliberately **manual-only** so
branch updates do not spend runner time before a change is ready for acceptance. The shared Forgejo runner
stays at capacity 1: BookWave reduces queue/startup overhead inside its own workflow rather than increasing
global runner concurrency. One `PR #<number> · Preflight` job performs the current-main/change classification,
Gradle-wrapper verification, pinned gitleaks scan and committed Room-schema immutability checks as separately
named steps over one full-history checkout. The expensive Android gate remains a separate
`PR #<number> · verifyDebug` job and runs exactly once: ordinary changes use `verifyDebug`; build/classpath
changes use `verifyDebug --rerun-tasks` for R-31. The dependency/licence report is generated in that same
warmed Android job instead of bootstrapping a second Android job.
Dispatch it from Forgejo → **Actions** → **Pull request** → *Run workflow*: select the PR branch/ref, enter the required Forgejo **PR number**, and optionally enable **force_rerun** when a reviewer wants the strongest path regardless of the diff. The workflow declares the preferred run name `PR #<number> — <branch>` and prefixes every check/job with `PR #<number>`. Forgejo 16.0.4 currently keeps the top-level Actions run title as `Pull request` even when `run-name` is present, so the check/job prefix is the live-instance fallback until Forgejo surfaces `run-name` as the run title. The workflow rejects a PR number whose Forgejo head ref does not match the selected branch/ref. PR numbers, repository run numbers and Forgejo's internal run IDs remain independent counters.

`.forgejo/workflows/main.yml` runs the release-side checks after a fast-forward merge: release lint, SBOM,
vulnerability scan and an unsigned release assembly. Scheduled/manual main runs also execute `verifyDebug`
so they remain standalone health checks. Push-main does not repeat the already-accepted PR debug gate.

### Dedicated Forgejo Android CI image

The Android-heavy Forgejo jobs use the repository-owned BookWave CI image instead of rebuilding their
toolchain on every run. The current human tag is
`forgejo.homebord.org/alexander/bookwave-ci:android36-jdk17-v1`; workflow consumers deliberately pin the
immutable digest
`sha256:bf0f8f06ed41f9dd98b36332b2f1170906d0363c89fdd7c918ce0186844b6b85`.
That image was published from source commit `65c7928fabad759bf3c666c9a76a4e3030932687` and is also retained as
`sha-65c7928fabad`.

`.forgejo/workflows/build-ci-image.yml` is manual-only. It builds and self-checks the image, and publishing
requires the protected `BOOKWAVE_PACKAGE_TOKEN` repository secret. The image contract is Node 22, JDK 17,
Android command-line tools build 15859902, platform-tools, Android platform 36, build-tools 36.0.0, and
gitleaks 8.30.1. The manually downloaded Android command-line-tools and gitleaks archives are
SHA-256 pinned; Android SDK packages themselves are installed by `sdkmanager`, and the Node base follows
the declared `node:22-bookworm` tag.

Forgejo Runner reaches Forgejo itself over an internal HTTP service URL while jobs consume packages through
the canonical TLS host. Large OCI uploads through the external reverse-proxy path returned HTTP 502 during
validation, so the publish step derives Forgejo's direct service endpoint from `FORGEJO_SERVER_URL` and
uses it only for the layer upload. It then resolves both the human tag and source-SHA tag back through
`forgejo.homebord.org` and requires their digests to match. This keeps the workaround repository-local;
CT520's Podman/runner configuration does not need an insecure-registry exception or any other change.

Only Android-heavy jobs use the image: pull-request `verifyDebug`, main `release-checks`, and the Android
`apk` job. Policy classification, Gradle wrapper validation, Room-schema checks, secret scanning, and the
Loopbound web build remain on the ordinary runner. Those Android jobs still run `scripts/codex/setup.sh`,
but with `BOOKWAVE_ANDROID_SDK_MODE=preinstalled`; setup verifies the exact SDK contract and must not
download or mutate Android packages.

### Forgejo Gradle cache

Forgejo Android jobs persist Gradle state through the runner's Actions cache rather than a host-mounted
Gradle directory. The cache action is pinned to commit
`0057852bfaa89a56745cba8c7296529d2fc39830` (actions/cache v4.3.0). The cached paths are
`~/.gradle/caches` and `~/.gradle/wrapper`, which includes Gradle's existing local build cache because
`org.gradle.caching=true`. Workspace `build/` directories are deliberately not cached.

`scripts/ci/gradle-cache-key.sh` hashes the tracked Gradle configuration inputs (Gradle Kotlin DSL files,
`build-logic`, `gradle/`, `gradle.properties`, and Gradle lockfiles). Each Gradle configuration hash gets one immutable cache
snapshot per trust namespace. Source-only commits reuse that snapshot instead of creating another full archive;
a new archive is created only when tracked Gradle/build inputs change (or the namespace version is deliberately
bumped). This bounds runner disk growth while letting Gradle reuse verified dependencies and any compatible
task outputs; Gradle's normal task input fingerprints remain authoritative for source changes.

The cache namespace enforces a trust boundary:

- pull-request verification is restore-only and can consume the default branch's
  `bookwave-gradle-trusted-v1-` cache, but never publishes branch-controlled cache state;
- main `release-checks` is the sole writer and publishes `bookwave-gradle-trusted-v1-` only when
  `forgejo.ref == 'refs/heads/main'`, with one archive per Gradle configuration hash. On a main push where
  that exact key is missing, it runs one normal `verifyDebug` to seed trusted debug outputs before the
  release checks finish and the cache is saved; ordinary source-only merges with an exact hit keep skipping
  the duplicate PR verification;
- the signing-capable APK job is restore-only and may read the trusted namespace only when the selected
  **BookWave branch** is `main`, before signing secrets are staged. Selecting any feature branch builds
  cold even though the workflow definition itself is dispatched from `main`.

This separation follows Gradle's default CI recommendation that non-default branches read shared cache state
without writing their own entries. It prevents repository-controlled PR code from creating a cache later
consumed by a secret-bearing signing job and avoids branch caches consuming runner disk. Cache misses are
valid and fall back to a normal cold Gradle run; the cache is a performance layer, never a correctness
prerequisite.

The `.github/workflows/*` workflows remain the GitHub fallback while the Forgejo migration settles.
`.github/workflows/contract-capture.yml` captures response shapes from a real server on demand
(`PRODUCT_SPEC 22.5`).

`verifyDebug` itself fans out to every module: ktlint, detekt with type resolution, Android Lint with
warnings as errors, the unit suite, and Kover's coverage gate over domain and core. Dependency
verification is `strict` over 890 pinned components.

**Verify with `--rerun-tasks`.** Gradle can consider a test-compile task up to date when only its
classpath changed, which once let two stale test doubles pass locally and fail in CI. See `docs/risks.md`
R-31.

## Getting an APK without building one

Forgejo → **Actions** → **Build APK** → *Run workflow*.

- Leave Forgejo's native **Use workflow from** ref on `main`. On the current Forgejo UI this is a free-text
  ref control; it selects the trusted workflow definition, not the BookWave source to compile.
- Choose the source from the real **BookWave branch** dropdown.
- Choose `debug` or `release`.
- `run_checks` optionally runs `verifyDebug` before assembly and is off by default so a quick device build
  stays quick.
- `include_loopbound` embeds a freshly built Loopbound bundle when enabled.

Forgejo only renders a dropdown for `workflow_dispatch` inputs declared as `type: choice`, and those
options are static YAML. The BookWave branch picker therefore stores a generated snapshot of repository
branches in `.forgejo/workflows/apk.yml`. If a newly created branch is missing, run
**Refresh APK branch dropdown** once from `main`; it rewrites only that generated option block. Because
refreshing the snapshot commits the workflow file, it moves `main` and may make existing PRs require a
rebase, so the refresh is deliberately manual rather than automatic.

The selected branch is resolved through Forgejo Git to an exact commit SHA before checkout. A stale
choice for a deleted branch fails before Gradle or signing starts.

Both Forgejo APK variants use the protected BookWave signing identity described above, and the workflow
verifies the certificate fingerprint from the completed APK before uploading it. The release run also
uploads its R8 mapping.

The artefact is named from the version read back **out of the built APK**, not out of the build script.
R-04 is why: a `versionName` that had not moved in nine builds made every field report name the wrong
build.

The equivalent `.github/workflows/apk.yml` remains available as the GitHub fallback while that mirror is
maintained; its older picker mechanism does not define the active Forgejo workflow.

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