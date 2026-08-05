# Changelog

Notable changes to ShelfPlayer. Requirement identifiers refer to `PRODUCT_SPEC.md`.

## Unreleased

### Phase 0 — Repository foundation

**Build and quality gates** (PRODUCT_SPEC 16)

- Gradle Wrapper 8.14.3, Kotlin DSL, `gradle/libs.versions.toml` version catalog with every version
  pinned; repositories restricted and content-filtered.
- Convention plugins in `build-logic/` for the Android application, Android library, JVM library,
  Compose, Hilt, Room and the shared quality gate.
- ktlint 1.5.0 with `.editorconfig` (Kotlin official style, 120 columns, trailing commas, no wildcard
  imports, LF, final newline).
- detekt 1.23.8 with type resolution, no baseline, and rules encoding the `PRODUCT_SPEC 16.3` list:
  no `GlobalScope`, no raw dispatchers, no swallowed exceptions, no empty catch, exhaustive `when` on
  sealed types, no `System.currentTimeMillis()` in domain logic, no `println`.
- Android Lint as a blocking gate with warnings as errors and no baseline.
- Kotlin compiler warnings as errors in CI via `-Pshelfplayer.warningsAsErrors=true`.
- `./gradlew verifyDebug` — ktlint, detekt with type resolution, Android Lint, unit tests, Room schema
  verification and the debug assembly, for every module.
- Dependency locking and dependency verification configured, with bootstrap scripts
  (see ADR-0006).
- GitHub Actions: pull-request workflow (wrapper validation, secret scan, `verifyDebug`, Room schema
  diff, debug APK, dependency report) and main workflow (release lint, unsigned release build).

**Architecture** (PRODUCT_SPEC 9, 13, 14)

- Ten modules: `:app`, `:core:model`, `:core:common`, `:core:designsystem`, `:core:database`,
  `:core:datastore`, `:core:network`, `:core:testing`, `:data:library`, `:domain`. `feature:*` are
  packages inside `:app` (ADR-0002).
- Typed `AppResult` / `AppError` with the full `PRODUCT_SPEC 14.1` taxonomy, `isRetryable` encoding
  the `14.3` retry policy, and `resultOf` as the single exception boundary that rethrows cancellation
  (ADR-0003).
- Injected `AppClock` exposing wall-clock **and** monotonic readings, and injected dispatchers with
  an `@ApplicationScope` replacing `GlobalScope`.
- Redacted structured logging where the field *type* decides what survives; `:app` owns the only
  `android.util.Log` call site (ADR-0004).
- Room as the UI source of truth: compound `(serverId, remoteId)` identity, soft delete, freshness
  columns, profile-scoped progress filtered in SQL, exported schemas, no destructive migration.
- Proto DataStore for settings, including the active profile selection.
- OkHttp/Retrofit foundation: timeouts per endpoint class, header-only authorization, a redacting
  HTTP logger, `ServerUrlNormalizer` and `NetworkErrorMapper`. No endpoints are defined.
- Fake Audiobookshelf gateway with a ShelfPlayer-owned fixture library (ADR-0005).

**Application**

- Compose Material 3 shell: home (library list) → library (search, sort) → book detail, all reading
  Room-backed state, with distinct loading, empty, error and content states.
- First launch seeds the demo library through the same repository the real gateway will use.

**Documentation**

- `README`, `CONTRIBUTING`, `SECURITY`, `PRIVACY`, `CHANGELOG`, `LICENSE` (undecided, ADR pending).
- `docs/architecture/` (overview, module boundaries, build), `docs/adr/0001`–`0006`,
  `docs/api-compatibility.md`, `docs/testing.md`, `docs/release.md`.

### Known follow-ups

- Bootstrap dependency verification checksums and lockfiles, then flip
  `org.gradle.dependency.verification` to `strict` (ADR-0006).
- Add `distributionSha256Sum` to `gradle-wrapper.properties`.
- Enable the Gradle configuration cache once the protobuf/KSP/AGP combination is validated against it.
- Replace the placeholder application ID `com.example.shelfplayer` before any release.
- Choose a licence (PRODUCT_SPEC 24.2).
