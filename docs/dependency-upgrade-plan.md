# Dependency upgrade plan

**Measured 2026-08-29** against Google Maven, Maven Central and the Gradle Plugin Portal, by reading each
artifact's `maven-metadata.xml` and taking the highest version with a plain numeric form. Pre-releases are
excluded by construction, so every "latest" below is a stable release that existed on that date.

This is a **plan, not a change**. Nothing in `gradle/libs.versions.toml` has moved. PRODUCT_SPEC 16.1 pins
every version and forbids dynamic ranges, so each line here is a deliberate edit somebody has to make and
a gate somebody has to watch go green.

## Where the project stands

| | |
| --- | --- |
| Gradle | 8.14.3 |
| JDK | 21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Pinned versions behind latest stable | **26 of 36** |

Ten are already current: Coil 2.7.0, detekt 1.23.8, javax.inject 1, JUnit 4.13.2, Turbine 1.2.1, the
Retrofit kotlinx-serialization converter 1.0.0, and the SDK levels.

## The full measurement

| Version key | Pinned | Latest stable | Kind |
| --- | --- | --- | --- |
| `androidGradlePlugin` | 8.12.0 | **9.3.2** | major |
| `kotlin` | 2.2.0 | **2.4.10** | minor ×2 |
| `ksp` | 2.2.0-2.0.2 | **2.3.11** | versioning scheme changed |
| `ktlintGradle` | 12.3.0 | **14.2.0** | major ×2 |
| `kover` | 0.9.1 | 0.9.9 | patch |
| `protobufPlugin` | 0.9.5 | 0.10.0 | minor |
| `media3` | 1.7.1 | **1.11.0** | minor ×4 |
| `composeBom` | 2025.06.01 | **2026.08.00** | 14 months |
| `androidxActivity` | 1.10.1 | 1.13.0 | minor |
| `androidxAnnotation` | 1.9.1 | 1.10.0 | minor |
| `androidxCore` | 1.16.0 | 1.19.0 | minor |
| `androidxDatastore` | 1.1.7 | 1.2.1 | minor |
| `androidxLifecycle` | 2.9.1 | 2.11.0 | minor |
| `androidxNavigation` | 2.9.0 | 2.10.0 | minor |
| `androidxRoom` | 2.7.2 | 2.8.4 | minor |
| `androidxWork` | 2.10.1 | 2.11.2 | minor |
| `androidxHiltNavigationCompose` | 1.2.0 | 1.4.0 | minor |
| `hiltExt` | 1.2.0 | 1.4.0 | minor |
| `hilt` | 2.57 | 2.60.1 | minor |
| `kotlinxCoroutines` | 1.10.2 | 1.11.0 | minor |
| `kotlinxSerialization` | 1.8.1 | 1.11.0 | minor ×3 |
| `okhttp` | 4.12.0 | **5.5.0** | major |
| `retrofit` | 2.11.0 | **3.0.0** | major |
| `protobuf` | 4.31.1 | 4.36.0 | minor |
| `robolectric` | 4.15.1 | 4.16.1 | minor |
| `androidxBenchmark` | 1.3.4 | 1.4.1 | minor |
| `androidxTestCore` | 1.6.1 | 1.7.0 | minor |
| `androidxTestExt` | 1.2.1 | 1.3.0 | minor |
| `androidxTestRunner` | 1.6.2 | 1.7.0 | minor |
| `androidxUiAutomator` | 2.3.0 | 2.4.0 | minor |

---

## Wave 1 — the cheap ones

**Twelve version bumps, no expected source change.** AndroidX minors within the same major, plus the test
and tooling libraries. Each is additive by AndroidX's own compatibility policy, and the gate is the
verification.

`androidxActivity`, `androidxAnnotation`, `androidxCore`, `androidxLifecycle`, `androidxNavigation`,
`androidxWork`, `androidxHiltNavigationCompose`, `hiltExt`, `kover`, `androidxTestCore`,
`androidxTestExt`, `androidxTestRunner`, `androidxUiAutomator`.

**Benefit.** Bug fixes and the current security posture on libraries that touch the network stack, the
process lifecycle and the foreground service — the three places where an Android platform behaviour change
lands first. Nothing user-visible.

**Cost.** One commit, one gate run. `--rerun-tasks` is required (`docs/risks.md` R-31: Gradle has treated
test-compile tasks as up to date when only the classpath moved, and that let two stale test doubles pass
locally and fail in CI).

**Do this first**, on its own, so that when a later wave breaks something the bisect is short.

---

## Wave 2 — Kotlin, KSP and Hilt, together

`kotlin` 2.2.0 → **2.4.10**, `ksp` 2.2.0-2.0.2 → **2.3.11**, `hilt` 2.57 → **2.60.1**,
`kotlinxCoroutines` 1.10.2 → 1.11.0, `kotlinxSerialization` 1.8.1 → 1.11.0.

**These move together or not at all.** KSP is compiled against a specific Kotlin compiler; Hilt's processor
runs on KSP; the serialization plugin ships with Kotlin. A partial bump here is the one combination
guaranteed to fail.

**Note the KSP versioning change.** The pinned `2.2.0-2.0.2` is the old `<kotlin>-<ksp>` form. KSP now
publishes plain `2.3.x`, decoupled from the Kotlin version — verified against the plugin portal's own
metadata, where the last twelve releases are `2.3.0` through `2.3.11`. So the version string's *shape*
changes, and anything that parses it (nothing in this repo does, but check the release scripts) needs to
know.

**Benefit.**
- **Two Kotlin minors of compiler fixes**, and this project runs with `-Werror`. A new compiler warning is
  a build failure here, which is exactly why this wave is not "just a number".
- **Coroutines 1.11** and **serialization 1.11** are what the network and playback layers are built on.
- Faster KSP2 processing, which shows up in every build on a laptop.

**Cost.** Expect to fix new warnings. `-Pshelfplayer.warningsAsErrors=true` is the gate CI runs, so budget
for a round of deprecations — Kotlin 2.3 and 2.4 both tightened warnings around nullability inference and
unused expressions. Nothing here is a *breaking* change; it is a cleanup tax.

**Risk if skipped.** Growing distance from AGP 9, which wave 4 needs.

---

## Wave 3 — Media3 1.7.1 → 1.11.0

**The one with a real product benefit, and the one to test hardest.**

Media3 is what plays every book, owns the media session, drives Android Auto and the notification, and this
app leans on its internals more than most: `ExoPlayer.setPreferredAudioDevice` (ADR-0027 and both its
amendments), `CommandButton` slots and the legacy `PlaybackStateCompat` conversion, `MediaLibraryService`'s
browse tree, and a custom `MediaSource` factory.

**Benefit.**
- Four minors of ExoPlayer decoder, buffering and offline fixes.
- Media3's Android Auto and `MediaSessionService` code is where head-unit compatibility bugs get fixed, and
  `docs/risks.md` still carries R-71 (the car's "fetching your selection" defect) unresolved.
- Several of the `@UnstableApi` surfaces this app opted into have been stabilising.

**Cost — assume this is the expensive wave.**
- `@UnstableApi` signatures **may change without deprecation**. `AudioOutputRouter`, `NotificationButtons`,
  `PlaybackService` and `BookMediaSourceFactory` are the files to read first.
- The bytecode facts the output chooser was built on need re-verifying against the new AAR, exactly as they
  were verified against 1.7.1: that `PlayerWrapper` still builds a legacy custom action from
  `CommandButton.iconResId`, and that `getCustomLayoutFromMediaButtonPreferences` still requires
  `SLOT_OVERFLOW`. **Do not assume.** That assumption, made once, is what R-78 records.
- **`docs/device-test-0.9.14.md` §2 must be re-run in the car**, not just on the phone. No JVM test covers
  the session callbacks — `MediaSession.ControllerInfo` cannot be constructed in one.

**Do this alone, in its own PR**, with the device test attached to it.

---

## Wave 4 — AGP 9 and Gradle 9

`androidGradlePlugin` 8.12.0 → **9.3.2**, which requires **Gradle 9.x** (the wrapper is on 8.14.3), plus
`ktlintGradle` 12.3.0 → **14.2.0** and `protobufPlugin` 0.9.5 → 0.10.0.

**Benefit.** Build speed and configuration cache correctness, continued Play Store toolchain support, and
staying on a supported AGP before the next `compileSdk` bump forces it anyway.

**Cost — the largest, and mostly in the build logic rather than the app.**
- `build-logic/convention` is a whole plugin set written against AGP 8's variant API. AGP 9 removed
  long-deprecated DSL and tightened the variant API.
- The custom `verifyDebug` aggregate task, the `shelfplayer.warningsAsErrors` property plumbing, the SBOM
  task and `:app`'s `testReleaseUnitTest` exclusion of `**/*ScreenTest.class` all live there and all need
  re-checking. That exclusion is a **documented contract** — `ui-test-manifest` is a `debugImplementation`
  and a release-variant screen test has no activity to launch — so if it silently stops applying, the
  symptom is a green local build and a red CI, which is the worst shape of failure.
- Gradle 9 removes APIs that Gradle 8 only deprecated.

**Sequence it last** and give it its own PR. There is no user-visible benefit to trade against a broken
build, so it should not ride along with anything that has one.

---

## Wave 5 — OkHttp 5 and Retrofit 3, decided rather than done

`okhttp` 4.12.0 → **5.5.0**, `retrofit` 2.11.0 → **3.0.0**.

Both are major versions and both sit under every server call this app makes, including the token handling
that P0 work bound to its issuing origin.

**Benefit.**
- OkHttp 5 is where the maintained fixes are; the 4.x line is in maintenance.
- Retrofit 3 requires OkHttp 5, so this is one decision, not two.

**Cost and the open question.**
- OkHttp 5 changes several Kotlin-visible signatures relative to 4.x and moves some APIs.
- **`retrofitKotlinxSerialization` is pinned at 1.0.0 and that is the latest.** Before touching this wave,
  confirm the converter supports Retrofit 3 — if it does not, this wave is *blocked* and the plan should
  say so rather than the branch discovering it.
- Every captured contract in the test suite exercises this stack. That is the good news: the contract
  captures are what would catch a serialization behaviour change, and PRODUCT_SPEC 22.4 says the app does
  not guess undocumented server behaviour, so a wire-format difference must fail a test rather than reach a
  user.

**Recommendation: do this last, or not yet.** There is no defect driving it and the converter question is
unanswered. Wave 1 and wave 3 are where the value is.

---

## What is deliberately not here

- **Coil 3.** The pinned `io.coil-kt:coil-compose` 2.7.0 *is* the latest of that coordinate; Coil 3 is a
  different group (`io.coil-kt.coil3`) with a different package name, so it is a migration rather than a
  bump and needs its own decision. Not urgent: covers load, and the authenticated image client works.
- **`javax.inject` 1** is a finished specification. There is no newer version and there will not be.
- **detekt 1.23.8** is current on its own line.
- **compileSdk 37.** Not measured here because it is a platform decision rather than a dependency, and it
  belongs with wave 4.

## Order, and why

1. **Wave 1** — cheap, no source change, shortens every later bisect.
2. **Wave 2** — Kotlin/KSP/Hilt as one unit; budget for new `-Werror` warnings.
3. **Wave 3** — Media3, alone, with the car device test attached. The real product benefit.
4. **Wave 4** — AGP 9 / Gradle 9, alone, no feature riding along.
5. **Wave 5** — OkHttp 5 / Retrofit 3, only after the converter question is answered.

Each wave is one PR, and each ends with the gate:

```bash
./gradlew ktlintFormat
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks
```

`--rerun-tasks` is not optional in any of these: every wave changes a classpath, which is exactly the case
R-31 records Gradle getting wrong.
