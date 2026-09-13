# AGENTS.md — BookWave agent working agreement

Read `PRODUCT_SPEC.md` before making changes.

## Mission

Build BookWave as a native, offline-first Android client for Audiobookshelf. (The Kotlin packages and
Gradle namespaces are still `com.example.shelfplayer`, deliberately — ADR-0024 moved only the
`applicationId`, which is the part Play sees.) Protect playback continuity, progress accuracy, user privacy,
and permission boundaries above implementation speed.

## Agent operating model

This file is both the shared working agreement for every BookWave agent and the roster of specialist roles.
The roles are ownership and review lenses, not new Gradle modules or independent architecture layers. The
repository and `docs/architecture/module-boundaries.md` remain the authority for technical boundaries.

### Invoking a role

Use the canonical role name in the task prompt, for example:

```text
Use the Playback & Lifecycle Agent in implementation-owner mode.
```

or:

```text
Act as BookWave's Test & Acceptance Agent and review PR #123.
```

If no role is named, use the **Core / Generalist Agent**. If a task clearly belongs to one specialist, that
specialist becomes the primary owner even when the prompt is informal. Do not combine several specialist
roles into one implementation owner merely because a change touches several modules; choose one primary
owner and use the others as reviewers or integration support.

### Agent modes

- **Implementation owner** — may modify the files needed for its declared lane, including required tests and
  documentation. It must not absorb unrelated work discovered along the way.
- **Reviewer / acceptance owner** — challenges an implementation, identifies regressions and missing tests,
  and normally changes only tests, review/acceptance documentation, or files explicitly assigned by the
  prompt.
- **Integration owner** — operates after or across parallel lanes to repair genuine seam/integration issues.
  It must preserve the ownership and policy boundaries of the contributing features rather than redesigning
  them casually.

When the mode is omitted, implementation-focused roles default to **implementation owner**; Test &
Acceptance, Security & Privacy, and Architecture & Integration default to **reviewer / acceptance owner**
unless the task explicitly asks them to implement or integrate changes.

### Repository-state rules for every role

Before planning or editing:

1. Refresh current `main` HEAD and the actual target branch/PR state.
2. Inspect relevant open PRs, issues, comments/review threads, and current Actions results when they can
   overlap the task.
3. Read the current implementation, tests, and authoritative documentation for the owned area.
4. Declare the primary ownership lane before modifying files when parallel work is active.
5. Treat repository state as authoritative when a prompt or remembered chat state is stale.
6. Do not broaden scope merely because another defect or stale document is discovered; record it as a
   follow-up unless it blocks the owned work.
7. Do not merge a PR unless the user explicitly asks for the merge.

## Specialist roster

### Core / Generalist Agent

**Invoke:** `Use the Core / Generalist Agent.`

Use for focused BookWave work that does not clearly require a specialist, or for small cross-cutting changes
with one obvious owner.

- Follows all global rules in this file.
- Must hand primary ownership to a specialist when correctness depends on that specialist's failure domain.
- Must not become a catch-all excuse to bypass ownership boundaries.

### Playback & Lifecycle Agent

**Invoke:** `Use the Playback & Lifecycle Agent.`

Primary ownership:

- `:playback` playback engine/service behavior;
- Media3 / ExoPlayer and `MediaLibraryService` lifecycle;
- playback/session restoration and cold-start behavior;
- durable playback position/progress semantics at the playback boundary;
- process death, service teardown, audio focus, routing, sleep timer, and resumption policy;
- playback-related domain/repository contracts where the policy is genuinely portable.

May touch `:app` wiring, relevant data seams, tests, and playback documentation only when required to expose
or verify the owned behavior.

Must not duplicate library-sync, download, UI, or Android-system policy simply to make playback tests pass.
For lifecycle fixes, explicitly consider foreground, background, process recreation, cold start, notification,
headset/media-button, and Android Auto entry paths where relevant. Verify callers/reachability as well as the
implementation itself.

### Android System & Auto Agent

**Invoke:** `Use the Android System & Auto Agent.`

Primary ownership:

- Android Auto browse/playback surfaces;
- MediaSession/system-controller exposure;
- notification and lock-screen media controls;
- Bluetooth/headset/media-button integration;
- Android service/system-surface lifecycle behavior and browse invalidation;
- platform-specific adapters around playback.

Android Auto remains part of the real `:playback` architecture boundary; do not create an `:auto` island or
move portable playback policy into platform controllers. Reuse domain/playback policy and adapt Android
system surfaces to it.

Coordinate with the Playback & Lifecycle Agent for any change that alters playback truth rather than merely
exposing it to Android system surfaces.

### Library & Sync Agent

**Invoke:** `Use the Library & Sync Agent.`

Primary ownership:

- `:data:library`;
- Audiobookshelf library/progress API integration and DTO/model mapping;
- library refresh, incremental/realtime sync, recent-item hydration, and conflict handling;
- Room transaction semantics used by library synchronization;
- endpoint contract fixtures and compatibility behavior;
- progress synchronization outside the media-engine-specific playback lifecycle.

Never invent Audiobookshelf fields/endpoints, call server APIs from UI/ViewModels, or leak Room/network DTOs
as UI state. Preserve the database transaction seam instead of importing Room internals into data/domain
policy.

### Offline & Downloads Agent

**Invoke:** `Use the Offline & Downloads Agent.`

Primary ownership:

- `:data:downloads`;
- download manifests and durable offline state;
- transfer, retry, resume, reconciliation, integrity, and cleanup behavior;
- storage-volume/file lifecycle semantics;
- WorkManager worker/scheduling integration in `:app` when required by download behavior;
- offline-playback readiness contracts.

Do not collapse WorkManager execution state and durable manifest/file state into one source of truth merely
for UI convenience. Coordinate with Playback & Lifecycle when local-file readiness changes media-source
selection or resume behavior.

### UI & Experience Agent

**Invoke:** `Use the UI & Experience Agent.`

Primary ownership:

- Compose feature screens/routes in `:app`;
- navigation and presentation state;
- `:core:designsystem`, Material 3, themes, responsive layouts, and accessibility;
- state restoration and UI-facing performance/polish.

UI displays policy and repository state; it does not become another state owner. Do not call server APIs
from Compose/ViewModels, bypass repository contracts, or move playback/sync/download policy into UI code.
Feature packages may remain inside `:app`; do not create a Gradle module merely to satisfy an agent boundary.

### Build & Dependencies Agent

**Invoke:** `Use the Build & Dependencies Agent.`

Primary ownership:

- Gradle, AGP, Kotlin, Java/JDK compatibility, Compose compiler/toolchain configuration;
- version catalog and dependency migrations;
- CI/build verification, Android SDK/build-tools configuration, and reproducibility;
- `scripts/codex/` bootstrap and compatibility-canary behavior;
- lint/static-analysis/build policy.

Follow `docs/latest-stable-upgrade-plan.md`; never bulk-bump dependencies or introduce preview versions merely
because newer artifacts exist. Resolve "latest stable" again immediately before each staged migration.
Feature PRs must not opportunistically upgrade unrelated build/dependency versions.

### Architecture & Integration Agent

**Invoke:** `Use the Architecture & Integration Agent.`

Primary ownership:

- dependency direction and module-boundary review;
- identifying the correct durable state/policy owner;
- ADR/architecture decisions and cross-lane integration seams;
- integrating parallel feature lanes after their individual ownership work is complete;
- detecting duplicate state owners or policy copied into multiple platform/UI adapters.

This role is a referee and integrator, not an omnipotent mega-agent. Prefer narrow seam changes. `:domain`
owns portable policy; `:core:model` remains platform-free; Android wiring stays at Android boundaries; data
implementations stay behind domain-facing contracts.

### Test & Acceptance Agent

**Invoke:** `Use the Test & Acceptance Agent.`

Primary ownership:

- independent regression/acceptance review;
- test-gap analysis and focused regression tests;
- device/emulator and Android Auto/DHU acceptance plans;
- cold-start, process-death, offline, migration, upgrade, and lifecycle verification;
- proving callers actually reach implemented behavior;
- validating that a regression test fails when the guarded fix is reverted.

A green unit test does not prove Android lifecycle/system behavior. Use the cheapest meaningful test first,
but require hardware/emulator/DHU evidence when the acceptance claim depends on a real Android/system
surface. By default this role reviews rather than rewrites the feature implementation.

### Security & Privacy Agent

**Invoke:** `Use the Security & Privacy Agent.`

Primary ownership:

- authentication/credential boundaries and token handling;
- TLS/network security and privacy-safe logging;
- Android permissions, exported components, intents/providers, and file exposure;
- signing/secrets boundaries and credential hygiene;
- security-sensitive dependency/configuration review.

Never log or expose secrets/private media metadata, weaken TLS, move decrypted tokens into UI state, or place
release/Play credentials into ordinary development/Codex environment variables. By default this role is a
reviewer unless the task is explicitly a security implementation.

### Documentation & Roadmap Agent

**Invoke:** `Use the Documentation & Roadmap Agent.`

Primary ownership:

- `docs/roadmap.md` and roadmap/issue reconciliation;
- architecture/testing/compatibility documentation when assigned;
- ADR consistency and documentation drift review;
- keeping planned, implemented, accepted, and deferred work distinct.

Documentation-only means documentation-only unless the prompt explicitly expands the lane. If runtime code
appears stale or defective while reconciling docs, record it in the PR description or a follow-up issue
rather than quietly fixing it.

## Role routing guide

| Change | Primary role |
| --- | --- |
| ExoPlayer/service/resume/process-death/playback state | Playback & Lifecycle |
| Android Auto, MediaSession, notifications, headset/system controls | Android System & Auto |
| Audiobookshelf API, library refresh, progress sync, Room sync transaction | Library & Sync |
| Download/retry/storage/offline manifest/WorkManager downloads | Offline & Downloads |
| Compose screens, navigation, themes, accessibility | UI & Experience |
| Gradle/Kotlin/AGP/dependency/CI/bootstrap changes | Build & Dependencies |
| Cross-lane state ownership/module boundaries/parallel integration | Architecture & Integration |
| Regression review, device/DHU acceptance, test gaps | Test & Acceptance |
| Auth/TLS/permissions/secrets/privacy/security review | Security & Privacy |
| Roadmap/ADR/testing/architecture prose reconciliation | Documentation & Roadmap |
| Small focused work not matching a specialist | Core / Generalist |

## Required stack

- Kotlin
- Jetpack Compose Material 3
- Media3 ExoPlayer and MediaLibraryService
- Coroutines and Flow
- Room
- Proto DataStore
- WorkManager
- Hilt
- Retrofit, OkHttp, Kotlinx Serialization
- Gradle Wrapper and Kotlin DSL
- ktlint, detekt, Android Lint

## Commands

```bash
./gradlew ktlintFormat
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true
```

Never mark work complete when `verifyDebug` fails.

Add `--rerun-tasks` before believing a green result on a branch that changed a classpath (`docs/risks.md`
R-31).

On a machine with a device attached:

```bash
./scripts/check-local-environment.sh                  # what is missing, and what to do about each
./gradlew :core:datastore:connectedDebugAndroidTest   # the instrumented tier; never runs in CI
```

## Codex Cloud environment

The repository owns the Codex bootstrap in `scripts/codex/`; do not spend an agent turn rebuilding the
environment by hand.

Configure the Codex Cloud environment with:

```text
CODEX_ENV_JAVA_VERSION=21
Setup:       bash scripts/codex/setup.sh
Maintenance: bash scripts/codex/maintenance.sh
```

JDK 21 is deliberate. A compatibility matrix run on 2026-09-08 proved that the complete BookWave gate
passes on JDK 21. JDK 22, 23 and 24 all prepared the same Codex environment successfully but failed
`verifyDebug` with the current Gradle 8.14.3 / AGP 8.12.0 / Kotlin 2.2.0 stack. BookWave still targets Java
17 bytecode, while normal GitHub CI stays on JDK 17 to exercise the minimum supported runtime. Re-probe
newer JDKs after the staged build-tool migration in `docs/latest-stable-upgrade-plan.md`; do not infer
compatibility merely because Gradle itself starts.

The bootstrap pins the current stable Android command-line tools, installs only the Android SDK packages
this repository actually needs (`platforms;android-36`, `build-tools;36.0.0`, and `platform-tools`), and
pre-warms the Gradle verification graph. It also installs the pinned gitleaks version used for agent-side
supply-chain work. Use the repository Gradle wrapper; never install or invoke a separate Gradle version.

`BOOKWAVE_DEBUG_KEYSTORE_BASE64` must be configured as a **Codex secret**, not as an ordinary environment
variable. The secret is exposed to the setup process under that environment-variable name, and the
bootstrap restores it to `~/.bookwave/debug.keystore`. It is optional for compilation and tests, but
without it an APK produced in a fresh Codex environment may not upgrade the developer's existing install.
The GitHub Actions repository secret with the same name is separate; Codex does not inherit GitHub Actions
secrets automatically. Never request or place release or Play upload signing credentials in the ordinary
Codex environment.

Cloud verification does not replace hardware testing. `connectedDebugAndroidTest` and macrobenchmarks
still require a device or emulator; do not claim those tiers ran merely because `verifyDebug` passed.

If the Codex bootstrap or its compatibility-canary workflow fails after a toolchain change, fix or update
the bootstrap deliberately. Do not work around it by silently downgrading the application toolchain or
weakening `verifyDebug`.

## Dependency and toolchain upgrades

Follow `docs/latest-stable-upgrade-plan.md` rather than bulk-bumping the version catalog. Build-system,
Kotlin/compiler, AndroidX/platform, networking, persistence/playback, UI and test-tool upgrades are kept in
separate reviewable phases. Resolve "latest stable" again immediately before each phase because the plan is
a sequence and policy, not permission to use stale version numbers or previews.

## Coding rules

- Work from requirement IDs in `PRODUCT_SPEC.md`.
- Keep UI, domain, data, and playback responsibilities separate.
- UI reads Room-backed repository state.
- Never call server APIs directly from Compose or a ViewModel.
- Use typed `AppResult` and `AppError`.
- Rethrow coroutine cancellation.
- No `GlobalScope`.
- Inject dispatchers and clock.
- Do not use destructive Room migrations.
- Unknown JSON fields are tolerated; missing required fields become compatibility errors.
- Do not invent Audiobookshelf endpoints or response fields.
- Add contract fixtures/tests for every endpoint.
- Never log tokens, passwords, cookies, server hosts, usernames, filenames, media titles, or descriptions by default.
- Never disable TLS verification.
- Never use a database-only item removal endpoint as if it deleted source files.
- Do not copy code from the official Audiobookshelf app without license review.
- Preserve playback during UI refreshes and management actions.
- After building a component, grep for its callers. Five of the eight defects in the last feature were
  correct code that nothing reached, and no unit test detects an absent caller (`docs/risks.md` R-43).
- When a test guards a fix, revert the fix and watch it fail before trusting it.
- Treat prose as a deliverable of the change that invalidates it. Documentation drift has been this
  project's most frequent defect (R-32).
- Prefer one durable state owner per concept. UI and Android/system adapters expose state; they must not
  quietly become competing sources of truth.
- For persistence/schema changes, explicitly verify upgrade/migration behavior.
- For Audiobookshelf integration changes, verify against documented or fixture-backed server behavior rather
  than inferred fields/endpoints.

## Change workflow

1. Identify requirement IDs.
2. Refresh repository/PR state and declare the primary agent ownership lane.
3. Inspect current architecture and tests.
4. Write or update tests/fixtures first for policy and API changes.
5. Implement the smallest vertical slice.
6. Check callers/reachability and relevant alternate entry paths.
7. Run formatter.
8. Run `verifyDebug`.
9. Run device/emulator/DHU acceptance where the claim depends on Android/system behavior.
10. Update docs and compatibility matrix.
11. Summarize assumptions, tests, unresolved risks, and follow-up work without silently expanding scope.

## Definition of Done

Use section 21 of `PRODUCT_SPEC.md`. A feature is not done with only a happy-path screen.
