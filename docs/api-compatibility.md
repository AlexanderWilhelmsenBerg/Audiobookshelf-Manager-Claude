# Audiobookshelf API compatibility

`PRODUCT_SPEC 19` requires this file to record the server versions tested, the capabilities detected,
known endpoint differences, the fixtures used, and the date last verified.

## Server versions tested

**None.** Phase 0 makes no network requests and defines no Audiobookshelf endpoints.

| Server version | Date verified | Auth mode | Websocket | Notes |
| --- | --- | --- | --- | --- |
| — | — | — | — | No server has been contacted by this repository. |

This table is a release blocker for anything that talks to a server (`PRODUCT_SPEC 17.1`: contract
tests against the selected server versions are release blockers). It must have at least one row
before Phase 1 is complete.

## Why Phase 0 defines no endpoints

`PRODUCT_SPEC 22.4` forbids inventing endpoints or response fields, `22.5` requires a captured
contract fixture before relying on a response shape, and `23` records that the published API
reference states it is out of date.

There is no server to capture from yet, so `AudiobookshelfGateway` declares domain-level operations
and Phase 0 ships only a fake implementation. No Retrofit service interface and no wire DTO exists in
this repository.

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
| `core/network/src/test/resources/contract/` | Captured server responses | Does not exist yet. Arrives in Phase 1 with the endpoints it describes. |

## Known endpoint differences

None recorded. This section fills in as contract tests run against real server versions, and every
new privileged endpoint must add a row (`PRODUCT_SPEC 22.19`).

**Last verified:** never — Phase 0 has contacted no server.
