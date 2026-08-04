# CLAUDE.md — Claude Code project instructions

The authoritative requirements are in `PRODUCT_SPEC.md`. Treat its identifiers and acceptance criteria as contractual.

## Product priorities

1. Do not interrupt playback.
2. Do not lose progress.
3. Offline playback must be complete and reliable.
4. Do not cross profile or permission boundaries.
5. Destructive actions must describe their actual effect.
6. Do not guess undocumented server behavior.
7. Keep private self-hosted data out of logs and reports.

## Architecture

- Native Kotlin Android.
- Compose UI with unidirectional data flow.
- Room is the UI source of truth.
- Repositories mediate local/network/filesystem data.
- MediaLibraryService owns the player and media session.
- WorkManager owns constrained persistent work.
- Audiobookshelf integration is behind a gateway/capability layer.
- Hilt provides dependency injection.
- DTOs and entities never escape their data modules.

## Before editing

- Read the relevant requirement section.
- Read existing ADRs and `docs/api-compatibility.md`.
- Search for existing implementation before creating a parallel abstraction.
- Identify tests that prove current behavior.

## Implementation constraints

- No direct API calls from UI/ViewModel.
- No `GlobalScope`.
- No swallowed exceptions.
- No broad `catch (Throwable)` except at a deliberate process boundary, and cancellation must be rethrown.
- No dynamic dependency versions.
- No cleartext secrets.
- No TLS bypass.
- No destructive database migration.
- No unverified source-file deletion.
- No claims that the Audiobookshelf database-delete endpoint removes media files.
- No official branding or copied GPL code without a recorded licensing decision.

## Verification

Run:

```bash
./gradlew ktlintFormat
./gradlew verifyDebug
```

For playback/download work, also run the relevant integration or managed-device tests.

## Response/report format after a coding task

- Requirement IDs implemented.
- Files changed.
- Behavior added/fixed.
- Tests added and commands run.
- Compatibility assumptions.
- Remaining risks or follow-up issues.

Keep changes scoped. Prefer a working vertical slice with tests over a broad unfinished scaffold.
