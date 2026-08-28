# Testing

`PRODUCT_SPEC.md` section 17 defines the pyramid, device matrix, and thresholds. This file records the
current tiers and how to run them; a green JVM gate is not evidence for an absent hardware tier.

## Required local commands

```bash
./scripts/check-local-environment.sh
./gradlew ktlintFormat
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks
```

Use `--rerun-tasks` after a branch changes a classpath; R-31 records why an up-to-date result once hid stale
test doubles. `verifyDebug` covers formatting checks, detekt, Android Lint, JVM/Robolectric tests, coverage,
and debug assembly. It deliberately does **not** run connected tests.

With an authorized physical device or emulator:

```bash
./gradlew :core:datastore:connectedDebugAndroidTest
```

The first physical run completed on 2026-08-23 against a Samsung SM-S928B running Android 16: **27 tests,
0 failures, 0 errors, 0 skipped**. `KeystoreLockCipherTest` and `ProfilePasscodeStoreTest` exercise the real
AndroidKeyStore wrap, staged lock-record write, tamper/deleted-key handling, and encrypted rate limit. The
test APK has a different package/UID from the installed app, so it cannot erase the user's BookWave records.
This tier never runs in CI and no other module currently has an `androidTest` source set.

### The manual tier

`docs/device-test-0.9.14.md` is the current hardware test, and `scripts/device-test/` holds its commands,
one script per section. Nothing there installs a tool or touches a server —
`scripts/check-local-environment.sh --install` remains the only script in the repository that installs
anything. The number on a script is run order, not section number.

## What is covered today

The suite is distributed by responsibility rather than collected into one end-to-end test:

| Area | Representative evidence | Boundary proved |
| --- | --- | --- |
| Result/error semantics, cancellation, redaction, clock/dispatcher policy | `core/model`, `core/common` tests | Typed failures and privacy-safe diagnostics |
| URL normalization, auth/interceptor policy, captured request/response shapes | `core/network` contract tests and fixtures | Adapter serialization for captured endpoints; uncaptured privileged endpoints remain gaps |
| Room schemas/migrations, profile visibility, progress, downloads, drafts | `core/database` Robolectric migration/DAO tests | SQLite behavior and every exported schema through version 20 |
| Proto settings, session and profile-lock stores | `core/datastore` JVM plus connected lock-store tests | Serialization policy, portable atomic replace-existing commits; AndroidKeyStore only in the connected tier |
| Auth, library, playback-progress, download and management repositories | `data/*` tests | Gateway/Room/domain boundaries and failure retention |
| Sorting/grouping/search, sync, routing, smart download, genre consolidation | `domain` tests | Policy without Android framework dependencies |
| Screens/ViewModels, accessibility semantics, navigation policy | `app` Robolectric tests | Compose semantics/state at the configured JVM SDK, not platform rendering |
| Media3 source/session/library policy | `playback` JVM/Robolectric tests | Pure callback/source behavior, not Binder/controller identity or a real media service |

Genre consolidation has exact domain tests for case-insensitive replacement, stable unrelated genres,
draft conflicts, sequential stopping, profile changes, and partial summaries. The current visual slice has
Robolectric coverage for shelf play targets, collection artwork/fallbacks, launcher aliases, and picker
reachability. A signed-in phone pass found grouped `0 books` summaries and a Genre Edit action intercepted by
its parent card. Both now have mutation-proved regressions and corrected-device recaptures, demonstrating why
the observational phone tier remains important even when Compose semantics tests are green.

The credential/passcode staged-write regressions run on the Windows JVM as well as Android-compatible
production code. They deliberately replace an already existing token or passcode record. Reverting the
shared commit helper to `File.renameTo` made both tests fail on Windows, proving that the tests guard the
cross-platform overwrite behavior rather than merely exercising first-write success.

## Conventions

**Fakes must reproduce production shapes.** Hand-written fakes are preferred to call-verification mocks,
but they are not exempt from fidelity. R-37 records a fake that ignored cached stamps and kept a destructive
production path unreachable. After a regression test guards a fix, revert the fix and verify that test fails.

**Robolectric where SQL or Compose semantics are the subject.** Room tests use real SQLite semantics;
Compose tests inspect the semantics tree. Neither proves a real media session, AndroidKeyStore, system
biometric window, launcher, notification permission flow, TalkBack speech, or car host.

**Determinism.** Injected `AppClock` and dispatchers control time and scheduling. Tests do not sleep. Preserve
and rethrow coroutine cancellation.

**Assert absence for privacy.** Redaction/logging tests assert that sensitive strings never appear. Real-
account screenshots are kept in ignored local storage and must not be attached to a public pull request
without explicit approval and redaction.

## Still missing or partial

- `app`/`playback`/download connected UI, service, Binder, process-recreation, permission, and migration
  smoke tiers; CI managed-device execution.
- API 26/31/34/36 physical coverage, release/R8 launch, low-storage/process-death checks, and the two-hour
  playback soak.
- The baseline profile and the 2,000-item measurement have a harness — `:benchmark`, run with
  `./gradlew :benchmark:connectedBenchmarkAndroidTest` — and no results yet. See `docs/benchmark.md`.
- TalkBack, 2.0x font, RTL, contrast, landscape, tablet/foldable/split-screen, and Mini Player inset passes.
- Android Auto DHU/current-head-unit discovery, browse, voice search, artwork, progress invalidation, buttons,
  and now-playing behavior.
- A second-UID Media3 controller test and two-origin bearer test for the exported-service security boundary.
- Captured adapter contracts for cover upload, metadata embedding, user activation, and one approved exact
  genre mutation; successful/restricted author-portrait fixtures.
- Live profile switching between two server origins while playback is buffered and progress is unsynced.

The full gap/risk accounting is in `docs/gaps.md`, `docs/risks.md`, and the dated documents under
`docs/reviews/`.
