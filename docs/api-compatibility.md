# Audiobookshelf API compatibility

`PRODUCT_SPEC 19` requires this file to record the server versions tested, the capabilities detected,
known endpoint differences, the fixtures used, and the date last verified.

## Server versions tested

| Server version | Date verified | Auth mode | Websocket | Notes |
| --- | --- | --- | --- | --- |
| 2.36.0 | 2026-08-05 | local (`authMethods: ["local"]`) | not verified | Login, refresh-token behaviour and API-key bearer auth observed directly. Contract capture in CI runs against the same version. |

Verified by observation, not by a released build: the app does not yet make these calls (see
`docs/handover.md`). What is confirmed is the *contract*, not an end-to-end sign-in.

This table is a release blocker for anything that talks to a server (`PRODUCT_SPEC 17.1`: contract
tests against the selected server versions are release blockers). It must have at least one row
before Phase 1 is complete.

## Why Phase 0 defined no endpoints

`PRODUCT_SPEC 22.4` forbids inventing endpoints or response fields, `22.5` requires a captured
contract fixture before relying on a response shape, and `23` records that the published API
reference states it is out of date.

There was no server to capture from in Phase 0, so `AudiobookshelfGateway` declares domain-level
operations and Phase 0 shipped only a fake implementation.

## Where the Phase 1 contracts come from

Two sources, and the difference between them matters when reading anything below.

1. **`docs/openapi.json`, published by the Audiobookshelf project.** Covers libraries, library items,
   series, authors, podcasts, notifications and email, and declares `BearerAuth`. It documents
   **31 paths** and contains **no authentication endpoint at all** — no login, no `/api/me`, no
   playback session, no media progress.
2. **Responses captured from a running server** by `.github/workflows/contract-capture.yml`. This is
   the authority wherever the two disagree, and the only source for anything in the first list's gap.

Nothing here is derived by reading the server's source for its own sake, and no Audiobookshelf code
is copied into this repository (`PRODUCT_SPEC 22.13`; see ADR-0007).

### Endpoints outside the published specification

These exist on the server but not in `openapi.json`, so each one is a capture target rather than
something the adapter may assume:

| Operation | Path | Why it matters |
| --- | --- | --- |
| Server probe | `GET /status` | Reports `serverVersion`, `isInit` and `authMethods`. `AUTH-001` uses it to decide whether a pasted URL is an Audiobookshelf server; `SYNC-001` reads the version and the authentication mode from it. |
| Health | `GET /healthcheck`, `GET /ping` | Readiness only. |
| First-run setup | `POST /init` | Creates the root user. Used by the contract fixture container, never by the app. |
| Login | `POST /login` | See the token note below. |
| Token refresh | `POST /auth/refresh` | What `AUTH-004` needs to extend a session without re-prompting. |
| Logout | `POST /logout` | |
| Token → user | `POST /api/authorize` | The cold-start exchange that turns a stored token into a user and permissions. |

### The mobile token model

`POST /login` sets a refresh-token **cookie** by default. It returns the tokens in the response
**body** instead when the request carries `x-return-tokens: true`, and `POST /auth/refresh` accepts
the refresh token in an `x-refresh-token` header rather than a cookie.

This app depends on the header form. A native client has no browser cookie jar, and
`PRODUCT_SPEC AUTH-003` requires the token to live in encrypted storage under our control — which is
only possible if the server hands it to us rather than setting it as a cookie.

This is exactly the kind of detail that cannot be guessed, and it is absent from the published
specification. It is recorded here because `PRODUCT_SPEC 22.19` requires the compatibility matrix to
be updated for every endpoint the app relies on.

### The library read shapes, and why the list endpoint is not enough

Captured on 2026-08-05 against 2.36.0, from a library the capture itself creates and scans.

| Endpoint | Envelope | What it carries |
| --- | --- | --- |
| `GET /api/libraries` | `{"libraries": [...]}` | `id`, `name`, `mediaType`, `displayOrder`, `folders`, `lastScan`, `lastUpdate`, `settings` |
| `GET /api/libraries/{id}/items` | `{"results": [...], "total", "page", "limit", ...}` | **minified** items |
| `GET /api/items/{id}?expanded=1&include=progress` | the item | the full item |
| `GET /api/libraries/{id}/authors` | `{"authors": [...]}` | `id`, `name`, `numBooks` |
| `GET /api/libraries/{id}/series` | `{"results": [...], "total", ...}` | same envelope as items |

The distinction that decides how `LIB-001` has to be built: **the item list is minified.** Each result
carries `media.numTracks`, `media.numChapters` and `media.numAudioFiles` — counts, not contents — and
`media.metadata.authorName` and `media.metadata.seriesName` as *strings*. There is no `tracks` array, no
`chapters` array, no `authors` array and no `series` array.

The expanded single item has all four, plus `media.metadata.narrators`, `media.metadata.descriptionPlain`
and `userMediaProgress`. Notably `media.tracks[].startOffset` is present and is exactly what
`PRODUCT_SPEC 11.3`'s global timeline needs, so the offsets do not have to be derived by summing
durations.

The consequence is that a sync which stores playable books cannot be one request per library: the list
gives the catalogue, and each item needs its own expanded fetch before it can be persisted as a
`BookSnapshot`. `PRODUCT_SPEC 2.3` ("offline means genuinely offline") is what makes that non-optional —
a book stored without its track offsets cannot be resumed.

Two artefacts of the capture, so a reader does not mistake them for server behaviour: `size` and `ino`
are scrubbed to `0` and `<volatile>` by `scripts/capture-contracts.sh`, and the fixture library has no
series, so `library-series.json` records an empty `results`.

## Capabilities

`PRODUCT_SPEC SYNC-001` requires a persisted capability handshake and requires an unknown capability
to be treated as **unsupported**, never assumed supported.

`ServerCapability` enumerates the capabilities the app will probe for. `ServerCapabilities.unknown()`
returns an empty set, and `FixtureMapper` drops any capability name it does not recognise rather than
guessing.

| Capability | Gates | Verified against a server |
| --- | --- | --- |
| `PlaybackSession` | PLAY-001, streaming session | No |
| `LocalSessionSync` | PLAY-005 | No |
| `RangeDownload` | DL-001 resume | No |
| `ChecksumOrETag` | DL-002 integrity | No |
| `MetadataUpdate` | MGR-001 | No |
| `CoverUpload` | MGR-002 | No |
| `MatchProvider` | MGR-003 | No |
| `ScanItem` / `ScanLibrary` | MGR-004 | No |
| `RemoveFromDatabase` | MGR-005 | No |
| `SourceFileDelete` | MGR-006 | No |
| `UserManagement` | USER-001…003 | No |
| `Websocket` | SYNC-002 | No |

### `RemoveFromDatabase` is not `SourceFileDelete`

These are two capabilities on purpose, and the distinction is a correctness requirement, not a
nicety.

`PRODUCT_SPEC 23` records that the documented item-delete operation removes the item from the
database and **does not delete files**. `PRODUCT_SPEC MGR-005` therefore fixes the action label as
exactly `Remove from Audiobookshelf database` and requires the confirmation to state that media files
remain on the server. `PRODUCT_SPEC MGR-006` makes true source-file deletion capability-gated: the
action must not exist unless a server reports a dedicated, tested source-file-delete capability, and
`22.12` forbids claiming source-file deletion from the database-delete endpoint.

No code in this repository may present one as the other.

## Fixtures

| Fixture | Kind | Purpose |
| --- | --- | --- |
| `core/network/src/main/resources/fixtures/demo-library.json` | **ShelfPlayer-owned format** | The Phase 0 demo library. Not an Audiobookshelf response; see [ADR-0005](adr/0005-fake-gateway-and-fixtures.md). |
| `core/network/src/test/resources/contracts/` | Captured server responses | **Twelve fixtures**, committed. `contract-capture.yml` re-captures on every `:core:network` change and fails on drift. |

### Six capture targets added, none yet committed

`scripts/capture-contracts.sh` now records six more responses. They exist as capture targets only: the
job that produces them needs a real server, so the fixtures land when `contract-capture.yml` next runs
and its artifact is committed. Until then nothing may be mapped from them (`PRODUCT_SPEC 22.5`).

| Target | Why it was missing | What it unblocks |
| --- | --- | --- |
| `me.json`, `authorize-with-progress.json`, `media-progress.json` | Every earlier capture ran against a server that had never played anything, so `user.mediaProgress` was `[]` and the **element** shape has never been seen. The script now records a position before capturing. | P1-09 — a progress-only sync, which fixes acceptance case TC-10 without any websocket at all. |
| `socket-handshake.json` | Never attempted. | P1-06 — a real `Websocket` capability probe instead of an assumption. |
| `socket-connected.json` | Never attempted. Records what the server volunteers after a namespace connect, with no guess involved. | P1-07 — the connection lifecycle. |
| `socket-auth.json`, `socket-event-after-progress.json` | Never attempted. The authentication frame's event name is **a guess** (`42["auth", token]`); the capture is how the guess is checked. | P1-08 — event payloads, if they turn out to exist in the shape the guess assumes. |

The socket frames are recorded parsed rather than as text: an engine.io polling response is `42["event",
{…}]`, and storing it structured is both readable as a contract and reachable by the redaction pass. A
token inside a frame is redacted; the frame's shape is kept.

**If `socket-auth.json` comes back empty or with an error frame, that is a result, not a failure.** It
means the event name is wrong, and the next step is to look at what `socket-connected.json` recorded
rather than to guess again.

## Known endpoint differences

None recorded. This section fills in as contract tests run against real server versions, and every
new privileged endpoint must add a row (`PRODUCT_SPEC 22.19`).

**Last verified:** 2026-08-05 against Audiobookshelf 2.36.0 — the authentication endpoints, API-key
bearer auth, and the library/item read shapes. Playback, progress, downloads, management, users and
websocket are unverified, and every capability in the table above still reads "No" because nothing in
`GET /status` reports one.
