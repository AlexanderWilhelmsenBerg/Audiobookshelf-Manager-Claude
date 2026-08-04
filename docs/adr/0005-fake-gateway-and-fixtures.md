# ADR-0005: A fake gateway with a ShelfPlayer-owned fixture format

- **Status:** Accepted
- **Date:** 2026-08-04
- **Requirements:** PRODUCT_SPEC 20 (Phase 0), 22.4, 22.5, 10.4, SYNC-001

## Context

Phase 0 must open a library with no server and no credentials, while `PRODUCT_SPEC 22.4` forbids
inventing Audiobookshelf endpoints or response fields and `22.5` requires a captured contract fixture
before relying on a response shape. `PRODUCT_SPEC 23` also notes that the published API reference is
out of date.

Writing plausible-looking Audiobookshelf JSON would satisfy the demo and violate both rules. Worse,
it would read like documentation: the next contributor would treat the invented shape as verified.

## Decision

Two separate things, kept visibly separate.

**1. A ShelfPlayer-owned fixture format.**
`core/network/src/main/resources/fixtures/demo-library.json` uses a format this project defines. Its
`FixtureModels.kt` KDoc and the file's own `_comment` say so explicitly. It is not an Audiobookshelf
response and must not be read as one.

**2. Contract fixtures, later.** Byte-for-byte captured server responses will live under
`core/network/src/test/resources/contract/`, arrive in Phase 1 with the endpoints they describe, and
be recorded in `docs/api-compatibility.md` with the server version they came from.

`FakeAudiobookshelfGateway` implements `AudiobookshelfGateway` and behaves like a real one where it
matters: it returns `AppResult` including typed failures, it enforces the profile boundary
(a call for a profile it does not serve is `AppError.Authorization`, not an empty list), and it works
off the main thread through an injected dispatcher.

The gateway interface declares only the three sub-APIs the fake genuinely implements
(`CapabilityResolver`, `AccountApi`, `LibraryApi`). The other six from `PRODUCT_SPEC 10.4` arrive
with their implementations; empty marker interfaces would look like coverage that does not exist.

The binding lives in `:app`'s `AppModule`, not in `:core:network`, so Phase 1 replaces one line.

## Consequences

- The whole vertical slice — gateway, repository, Room, UI — is built and tested before any endpoint
  is contract-tested, and CI never needs a server.
- The fixture exercises the awkward cases on purpose: sequences `1`, `2`, `2.5`, `10` and `Prequel`
  for `LIB-003`; a multi-file book with contiguous track offsets for `11.3`; an excluded track for
  `PLAY-003`; a finished book and a partially-played one for `PLAY-004`.
- The fixture does not prove anything about a real server. `docs/api-compatibility.md` says so and
  lists zero verified server versions.
- Phase 1 replaces the fake rather than extending it. If the real gateway needs a different shape,
  that is a signal about the design, not about the fixture.
