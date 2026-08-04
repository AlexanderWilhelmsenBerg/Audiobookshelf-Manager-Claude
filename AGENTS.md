# AGENTS.md — Codex working agreement

Read `PRODUCT_SPEC.md` before making changes.

## Mission

Build ShelfPlayer as a native, offline-first Android client for Audiobookshelf. Protect playback continuity, progress accuracy, user privacy, and permission boundaries above implementation speed.

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
./gradlew verifyDebug
```

Never mark work complete when `verifyDebug` fails.

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

## Change workflow

1. Identify requirement IDs.
2. Inspect current architecture and tests.
3. Write or update tests/fixtures first for policy and API changes.
4. Implement the smallest vertical slice.
5. Run formatter.
6. Run `verifyDebug`.
7. Update docs and compatibility matrix.
8. Summarize assumptions, tests, and unresolved risks.

## Definition of Done

Use section 21 of `PRODUCT_SPEC.md`. A feature is not done with only a happy-path screen.
