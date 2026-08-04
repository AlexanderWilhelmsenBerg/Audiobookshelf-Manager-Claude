# Testing

`PRODUCT_SPEC 17` defines the pyramid, the device matrix and the quality thresholds. This records
what exists now and how to run it.

## Running

```bash
./gradlew test                      # every module's unit tests
./gradlew :domain:test              # one module
./gradlew verifyDebug               # tests plus every other gate
```

There are no instrumentation tests yet: Phase 0 has no playback, no downloads and no permission
matrix, which are what `PRODUCT_SPEC 17.1` puts on a device.

## What is covered today

| Area | Test | Requirement |
| --- | --- | --- |
| Result/error semantics, cancellation rethrow, retry policy | `core/model` `AppResultTest` | 14.2, 14.3 |
| Series sequence parsing and ordering | `core/model` `SeriesSequenceTest` | LIB-003 |
| Redaction of every field type, URL, path, throwable | `core/common` `RedactorTest` | 14.5, AUTH-003 |
| No private value reaches a log sink | `core/common` `RedactingLoggerTest` | 14.5 |
| Base URL normalization | `core/network` `ServerUrlNormalizerTest` | AUTH-001 |
| HTTP status → `AppError` | `core/network` `NetworkErrorMapperTest` | 14.1, 14.3 |
| Fake gateway, capabilities, profile boundary, track offsets | `core/network` `FakeAudiobookshelfGatewayTest` | SYNC-001, 5.2, 11.3 |
| Schema relations, profile-scoped progress, soft delete, cascade | `core/database` `ShelfPlayerDatabaseTest` | 13.1, 13.2, AUTH-002 |
| Library sorting and search predicate | `domain` `BookSortOrderTest` | LIB-002, LIB-003 |
| Use cases follow the active profile; refresh requires one | `domain` `LibraryUseCaseTest` | LIB-001, 5.2 |
| Gateway → Room → domain round trip, failure keeps cached content | `data/library` `DefaultLibraryRepositoryTest` | LIB-001 |
| Home loading/content/error states, refresh guard | `app` `HomeViewModelTest` | LIB-001, 21 |

## Conventions

**Fakes, not mocks.** Every test double in this repository is a hand-written fake with real state.
The bugs that matter here are "does this flow re-emit when the active profile changes?", which a
stubbed call-verification cannot catch.

**Robolectric where SQL semantics are the subject.** `core/database` and `data/library` tests run on
Robolectric with a real in-memory Room database, pinned to `@Config(sdk = [34])`. Cascade rules and
profile filtering either hold in SQLite or they do not; a fake DAO would prove nothing about either.

**Determinism.** `TestAppClock` controls wall-clock and monotonic time independently, so the
clock-skew case `PRODUCT_SPEC PLAY-005` cares about is reproducible. `MainDispatcherRule` replaces
`Dispatchers.Main`. No test sleeps.

**Assert on absence for privacy.** The redaction tests assert that a string does **not** appear in
the rendered output. That is the assertion that protects the user; "the redactor returned something"
is not.

## Not yet covered

Listed so nobody mistakes the gaps for coverage:

- Room migration tests — there is one schema version, so there is nothing to migrate yet.
  `PRODUCT_SPEC 18` requires an old-to-new test with every future version bump.
- MockWebServer contract tests — Phase 1, with the endpoints.
- Compose UI tests, TalkBack semantics, large-font and tablet layouts — Phase 1.
- Playback, download, managed-device and Audiobookshelf-container integration tests — Phases 2–5.
- Coverage measurement. `PRODUCT_SPEC 17.3` targets 80% for domain/core and 90% for the smart
  download, progress sync, security and deletion policies. Those policies do not exist yet, so a
  coverage number today would measure the wrong thing.
