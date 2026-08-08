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
| `PlaybackSession` | PLAY-001, streaming session | **Yes**, 2026-08-07 — see the playback section |
| `LocalSessionSync` | PLAY-005 | **Partly** — the route is observed; multi-track is not |
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

## The websocket contract — observed 2026-08-06, Audiobookshelf 2.36.0

Nothing about the socket had ever been observed. It has now, over engine.io's **polling** transport,
which is plain HTTP and therefore capturable without a socket.io client. The frames are identical on
either transport; only the carriage differs.

### The sequence

| Step | Sent | Received |
| --- | --- | --- |
| Handshake | `GET /socket.io/?EIO=4&transport=polling` | `0{"sid":…,"upgrades":["websocket"],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}` |
| Namespace connect | `40` | `40{"sid":…}` — a **socket.io** sid, distinct from the engine.io one above |
| Authenticate | `42["auth","<accessToken>"]` | `42["user_online",{…}]` then `42["init",{"userId","username","usersOnline":[…]}]` |
| A progress change made over REST | — | `42["user_updated",{ the entire user object }]` |

`upgrades: ["websocket"]` is the answer `SYNC-001`'s websocket capability needs, and it is a property of
the *deployment*: a reverse proxy that strips the upgrade will not list it, which is exactly why the
capability is probed rather than derived from a version.

`pingInterval: 25000` / `pingTimeout: 20000` are the server's heartbeat terms, so a client that has heard
nothing for 45 s can consider the connection dead rather than guessing an interval.

### The authentication event name was a guess, and it was right

`42["auth", "<accessToken>"]` is not in `openapi.json` and is not documented. It was sent as a guess and
the server accepted it, answering `user_online` and `init`. That is now an observation rather than an
assumption, which is the whole point of capturing before implementing (`PRODUCT_SPEC 22.4`, `22.5`).

The token travels inside the frame, not in the query string, which is what `PRODUCT_SPEC 22.6` requires.

### `user_updated` is bigger than expected, and it changes the plan

A progress change made through the REST API came back over the socket as `user_updated` carrying **the
whole user object** — `mediaProgress`, `permissions`, `librariesAccessible`, `itemTagsSelected`,
`isActive`, `isLocked`, `type`. Three separate problems collapse into one handler:

- **TC-10** — progress played on another device. `mediaProgress` arrives unprompted.
- **TC-37 / P1-02** — a grant changed on the server. `permissions` and `librariesAccessible` arrive in
  the same frame, so the permission refresh becomes event-driven rather than switch-driven.
- **TC-45** — an account disabled server-side. `isActive` and `isLocked` are in the frame.

`PRODUCT_SPEC 13.2` says an event "may mark an entity stale and trigger refresh rather than carrying a
complete trustworthy object". Here the object *is* complete for the user, so it can be applied directly —
but only to the profile whose socket received it, and the frame contains no server identity, so the
binding must come from the connection rather than from the payload.

### `mediaProgress`, the element

Empty in every previous capture, because no capture had ever played anything. Observed fields:

```
currentTime, duration, progress, isFinished, hideFromContinueListening,
libraryItemId, mediaItemId, mediaItemType, episodeId, ebookLocation, ebookProgress,
id, userId, startedAt, finishedAt, lastUpdate
```

`startedAt`, `finishedAt` and `lastUpdate` are wall-clock milliseconds and are stabilised by the capture;
`duration` and `progress` read `0` against the synthetic fixture media, which has no real duration.

### What is still unobserved

- Every other event name. `user_updated` and `init` are the only ones this capture provoked; item
  changes, library scans and session events have not been seen and must not be assumed.
- Behaviour of the actual websocket upgrade, as opposed to the polling transport.
- What the server does with an **invalid** token in the `auth` frame.

### Fixtures

`me.json`, `media-progress.json`, `socket-handshake.json`, `socket-connected.json`, `socket-auth.json`,
`socket-event-after-progress.json`. Frames are stored parsed rather than as text, so the redaction pass
can reach a token inside one: the shape is kept, the credential is not.

## `isbn` and `asin` — present, and null (LIB-002)

`media.metadata` in the captured `library-item.json` carries both keys, and both are `null`: the
seeded book has neither, and the container runs with `scannerFindCovers` off and no metadata provider
configured, so nothing filled them in.

So the *presence* of both fields is observed, and their nullability is observed. What is **not**
observed is their type when populated — no capture has ever produced a non-null value. They are
mapped as `String?` on the strength of Audiobookshelf's documented metadata schema, which is a
narrower assumption than `PRODUCT_SPEC 22.5` normally allows and is recorded here rather than left
implicit.

The exposure is small and one-directional. `kotlinx.serialization` fails a `String?` field that
arrives as a number or an object, and the item mapper runs inside `resultOf`, so the worst case is a
single item reported unreadable rather than a wrong value written to the cache. If a server is ever
found that returns a numeric ISBN, the fix is a lenient deserializer here, not a schema change.

## Wave A capture — 2026-08-07, Audiobookshelf 2.36.0

Four new fixtures and two expected drifts. What each one unblocked, and what it did **not**:

| Fixture | Result | Verdict |
| --- | --- | --- |
| `item-cover.json` | **200**, `image/webp`, and **`unauthenticatedStatus: 200`** | **P1-14 unblocked** |
| `library-search.json` | 200. `book[]` carries a full `libraryItem`; `authors`, `series`, `genres`, `narrators`, `tags` are all **`[]`** | **P1-20 partially unblocked** — books only |
| `library-collections.json` | 200, paginated envelope, `results: []` | **Still blocked** — the envelope is observed, a collection object is not |
| `library-personalized.json` | 200. An array of `{id, label, labelStringKey, type, total, entities}`; this server returned *Recently Added* (book), *Discover* (book), *Newest Authors* (authors) | Held, not adopted — see ADR-0008 |
| `library-item.json` | drift: `media.coverPath` is now `/audiobooks/…/cover.jpg` | The seed fix working |
| `library-items.json` | drift: `libraryFiles` gained the image | The seed fix working |

### The cover endpoint does not require a credential

`unauthenticatedStatus: 200` — this server serves `/api/items/{id}/cover` to anyone who knows the item
id. That is the server's choice and not something the app can rely on: a deployment behind a
reverse proxy with forward auth, or a future Audiobookshelf release, may well require one.

**Covers are therefore fetched through the authenticated client anyway.** It works in both cases,
costs nothing extra, and keeps the token in a header rather than a URL. Coil handles `image/webp`
natively.

### Search: books yes, everything else no

The `book[]` element is a complete library item and can be mapped. The other five arrays came back
empty, so **no fixture has ever shown an `authors`, `series`, `genres`, `narrators` or `tags` search
result**. PRODUCT_SPEC 22.5 applies to each independently: search enrichment ships for books only, and
the other five stay unmapped until a capture against a library that actually matches them.

Two further facts the fixture settled, both of which changed the implementation:

- **`book[].libraryItem` is the *expanded* shape.** It carries `media.tracks`, `media.chapters`,
  `metadata.authors` and `metadata.series` — none of which `…/items` sends. A search hit is therefore a
  complete, playable book with no follow-up request, which is what makes server search worth the round
  trip at all rather than a way to learn some ids.
- **There is no `userMediaProgress`.** The endpoint takes no `include` parameter and the response has
  no such key. Every hit maps with `progress = null`, and `LibrarySnapshotWriter` writes only non-null
  progress — so searching for a book you are halfway through updates its metadata and leaves the
  position alone. Had that not been checked, a search would have been able to rewind a book.

### Paging `…/items` (D1)

`limit`, `page`, `offset` and `total` are all in the committed `library-items.json` envelope. The
capture was taken without paging parameters and the server answered `limit: 0` with every item, which
is a **default, not a contract** — so the client states `limit=100&page=N` and reads `total` to decide
whether to ask again.

A server that ignores `limit` is handled by the same check: `received >= total` is true after the first
response, so it is asked once. A server that ignores `page` would loop, which is why a page cap exists
and why tripping it is reported as a failure rather than as the end of the library — a truncated
catalogue treated as complete would let reconciliation soft-delete everything past the cut.

### Collections: the envelope is not the element

`results: []` is a real observation — this server has no collections — but it says nothing about what a
collection *looks like*. This is the same gap `mediaProgress` had before the progress capture, and it
has the same answer: the axis waits.

## The cover endpoint — `404`, and why (LIB-004)

The first capture of `GET /api/items/{id}/cover` returned **404, `text/plain`**. Not a wrong path: the
same capture recorded `"coverPath": null` on the item. The seeded book had no cover art, so there was
nothing to serve.

`scripts/seed-contract-media.sh` now generates a flat `cover.jpg` beside the audio, and the capture
treats a non-200 as a hard error rather than committing a 404 as though it were the contract. The
re-run also probes the endpoint without a credential, because whether a cover URL can be handed
straight to an image library or must travel through the authenticated client is the decision the whole
of the cover work turns on.

**Superseded: that capture returned 200 on 2026-08-07. See the Wave A section above.**

## How books should be fetched — the N+1, measured (LIB-001)

A full refresh today is **1 + N requests**: one `GET /api/libraries/{id}/items`, then one
`GET /api/items/{id}?expanded=1&include=progress` for **every** item, sequentially
(`AbsLibraryApi.snapshots`). On the 490-book library a device run used, that is 491 round trips before
the sync is finished.

AudioBooth does not do this. It fetches `GET /api/libraries/{id}/items` with `minified=1` plus
`limit`/`page`/`sort`/`desc`/`collapseseries`/`filter`, and calls `GET /api/items/{id}?expanded=1`
**only for the book the user opened**.

### What each response actually carries

Compared from the two committed fixtures, so this is observed rather than assumed:

| | `…/items` (list) | `…/items/{id}?expanded=1` |
| --- | --- | --- |
| title, subtitle, description | ✅ | ✅ |
| genres, tags, publisher, publishedYear, language | ✅ | ✅ |
| **isbn, asin** | ✅ | ✅ |
| duration, size, numTracks, numChapters, coverPath | ✅ | ✅ |
| addedAt, updatedAt | ✅ | ✅ |
| authors / series / narrators | **flat strings only** (`authorName`, `seriesName`, `narratorName`) | structured arrays **with ids and sequences** |
| `media.tracks`, `media.chapters`, `media.audioFiles` | ❌ absent | ✅ |
| `userMediaProgress` | ❌ | ✅ (`include=progress`) |

So the per-item fetch **cannot be deleted**. Track offsets and chapters are what make a downloaded book
resumable (PRODUCT_SPEC 2.3), and `LIB-003`'s series *sequence* only exists in the structured
`metadata.series` — the list's `seriesName` is a display string with no position in it.

But it can be **deferred**. The list alone is enough for the entire browse surface: the shelf, search,
genres, and the book detail screen minus its track count. The right shape is therefore:

1. one list request → write rows → **the library is browsable**;
2. expand items in the background, and on demand when a book is opened.

That turns "wait 491 requests for a shelf" into "wait one". It is the single largest improvement
available and it is **not blocked on a capture**: both shapes above are already committed.

### What *is* blocked

- **`minified=1`** changes the item shape (a reduced `media` object). Not captured → not used.
- **`sort` / `desc` / `filter` / `collapseseries`** — the sort keys are literal server field paths
  (`media.metadata.title`, `media.metadata.authorNameLF`, `addedAt`, `progress.finishedAt`, …). Sorting
  server-side would also *break offline sorting*, which Room does today for free. Recorded, not adopted.
- **`limit` / `page`** are safe on the shape — the committed `library-items.json` already carries
  `total`, `page`, `limit`, `offset` at the top level, so the envelope is observed. Paging is worth
  having for a 5,000-item library, but it is not what is slow today.

## Known endpoint differences

None recorded. This section fills in as contract tests run against real server versions, and every
new privileged endpoint must add a row (`PRODUCT_SPEC 22.19`).

**Last verified:** 2026-08-05 against Audiobookshelf 2.36.0 — the authentication endpoints, API-key
bearer auth, and the library/item read shapes. Playback, progress, downloads, management, users and
websocket are unverified, and every capability in the table above still reads "No" because nothing in
`GET /status` reports one.

## Playback sessions — captured 2026-08-07, Audiobookshelf 2.36.0 (PLAY-001, PLAY-004, PLAY-005)

Five fixtures, and they answered two of `item-play`'s three questions outright, contradicted one
assumption, and left one thing unverified that the seed script has since been changed to cover.

### `POST /api/items/{id}/play` — the session

Top-level: `id`, `userId`, `libraryItemId`, `libraryId`, `bookId`, `episodeId`, `mediaType`,
`mediaPlayer`, `playMethod`, `startTime`, `currentTime`, `duration`, `timeListening`, `startedAt`,
`updatedAt`, `displayTitle`, `displayAuthor`, `coverPath`, `mediaMetadata`, `libraryItem`, `chapters`,
`audioTracks`, `deviceInfo`, `serverVersion`, `date`, `dayOfWeek`.

**1. The track URLs are relative, and carry no credential.**

```
"contentUrl": "/api/items/{itemId}/file/{ino}"
```

No token, no query string, no signature. Two consequences, and both are good news:

- The player must resolve it against the profile's server base URL, so a track URL is only meaningful
  alongside the profile that produced it.
- It must be fetched with the `Authorization` header, which means ExoPlayer's data source has to be the
  app's **authenticated OkHttp client** rather than the default. PRODUCT_SPEC 14.5's no-token-in-a-URL
  rule is therefore satisfied by the server's own design rather than in spite of it — the alternative,
  a pre-signed URL with a token in it, would have been a finding to design around.

**2. `playMethod: 0` — direct play.** The fixture book is MP3 and the request advertised
`audio/mpeg`, so the server streamed the file rather than transcoding. What the other values of
`playMethod` mean has **not** been observed and must not be assumed; a transcoding session is a
different shape and is a separate capture.

**3. `startTime` is where the session resumes from.** It came back `128.25`, which is the position the
capture had written with `PATCH /api/me/progress/{id}` moments earlier. The server seeds the session
from stored progress rather than starting at zero.

**Chapters are on the session**, in seconds, as `{id, start, end, title}` — and also, separately, on
each audio track. The session-level array is the one PLAY-003 wants.

### What is still unverified: `startOffset` across tracks

The seed library held **one single-file book**, so `audioTracks` had one element with
`startOffset: 0` — which is consistent with `startOffset` being a global offset into the book *and*
with it being a per-file zero. The capture proves nothing either way.

This matters more than it sounds. PLAY-003 requires a seek across a track boundary to preserve the
global book position, which is arithmetic in one reading and a no-op in the other, and getting it wrong
is a bug that only appears on multi-file books.

`scripts/seed-contract-media.sh` now creates a second book of **two files, six seconds then four**, and
the capture opens a session against it as `multi-item-play.json`. Six-then-four rather than two equal
files, so that "startOffset is index × duration" cannot survive either. **Until that fixture exists,
nothing in the app may compute a global position from `startOffset`.**

### `POST /api/session/{id}/sync` and `/close` — 200, and nothing else

Both answer **`200` with an empty `text/plain` body**. No JSON, no echo of the accepted position, no
session state.

The client therefore gets no server-side confirmation to reconcile against, and has to treat `200` as
"accepted" and read `GET /api/me` if it wants to know what the server actually stored. PLAY-005's
outbox is built on exactly that: local session identity, retry until `200`, and a separate read to
verify.

**Idempotency**: both syncs returned `200`, and the resulting `currentTime` is the value sent rather
than twice it. The position is **set, not accumulated**, which is what PLAY-005's "retrying a session
sync is idempotent" needs. Whether `timeListened` accumulates across syncs is *not* settled by this
capture — the two requests sent the same value, so an accumulating counter and an idempotent one are
indistinguishable in the result.

### A capture artefact worth reading carefully

`me-after-session.json` shows `isFinished: true`, `progress: 1` and a non-null `finishedAt`. **That is
not what an ordinary mid-book sync looks like.** The capture sent `currentTime: 63.5` for a book eight
seconds long, and the server did the reasonable thing: clamped to the end and marked it finished.

Two things follow. The script now syncs `4.5` so the next capture records a normal mid-book sync. And
the accident documented something worth keeping: **the server marks a book finished from a session sync
when the position reaches the duration**, without being asked to. PLAY-004 sets the app's own finished
threshold at 95% and requires marking-finished to be explicit when server data is unreliable, so the
two can disagree — and the app has to decide which wins rather than discover it in the field.

### Why `/api/session/{id}/sync` rather than the progress route the app already uses

`PATCH /api/me/progress/{id}` is captured and in use, and it is enough to *record* a position. It says
nothing about a listening **session** — `timeListened`, the device that produced it, or the identity a
retry has to match on. PLAY-005's outbox is built on session identity, so the session route has to be
observed even though a simpler one already works.

## Offline sessions — the endpoints Phase 2's outbox should use (PLAY-005)

Found by reading the server's own route table and session manager, and by the fact that the official
Android app relies on the same behaviour. **Not yet captured**, so nothing may be built on the shapes
below until fixtures exist (22.5) — but they change the design enough that wave 3 should be planned
around them rather than retrofitted.

| Route | Purpose |
| --- | --- |
| `POST /api/session/local` | Sync **one** client-generated session |
| `POST /api/session/local-all` | Sync **many** — `{"sessions": [...]}` → `{"results": [...]}` |
| `GET /api/session/{id}` | Fetch an open session rather than opening a new one |
| `POST /api/items/{id}/play/{episodeId}` | The podcast-episode variant of the play route |

### Why this matters more than it looks

The plan had wave 3's outbox retrying `POST /api/session/{id}/sync` per session. **That cannot work for
an offline session**, and the reason is structural: `/api/session/{id}/sync` needs a session id the
server issued, and a session recorded on a train has never been to the server. There was no route in
the plan by which an offline session could ever be uploaded.

`POST /api/session/local` is the answer, and it is built for exactly this: the **client** generates the
id, and the server treats an id it has never seen as a new session. That is why PLAY-005 says "every
offline listening session has a UUIDv4 identifier" — the identifier is the client's, and it is what
makes a retry idempotent, because the second attempt carries the same id and is recognised as the same
session.

`POST /api/session/local-all` takes an array and answers with a per-session result, which is an outbox
drain in one request instead of N.

Fields the server reads from a local session: `id`, `libraryItemId`, `episodeId`, `currentTime`,
`timeListening`, `updatedAt`, `displayTitle`. Response per session: `{id, success, error?,
progressSynced}`.

### Conflict resolution is already what PLAY-004 asks for

The server compares the incoming `updatedAt` against the stored progress's and takes the newer.
**Progress can move backwards.** PLAY-004's "conflict resolution never blindly chooses the maximum
position" is therefore satisfied by the protocol rather than fought against — an intentional rewind
survives, provided the client sends an honest `updatedAt`.

Two consequences for the app:

- It must never clamp its own position to the maximum before sending, or it defeats a rule the server
  is already implementing correctly.
- PLAY-005's clock-skew detection stops being hygiene and becomes load-bearing. The server trusts
  `updatedAt`, so a device five minutes fast wins every conflict it takes part in.

## The finished threshold is the server's, and it is already in a committed fixture (PLAY-004)

`syncSession` marks a book finished using **library settings**, not a constant:
`markAsFinishedTimeRemaining` and `markAsFinishedPercentComplete`. Both are already in
`libraries.json`, captured since Phase 1 and never read:

```json
"markAsFinishedPercentComplete": null,
"markAsFinishedTimeRemaining": 10
```

This explains the `me-after-session.json` artefact completely. The capture synced a position past an
eight-second book; the position clamped to the end; zero seconds remained, which is under the library's
ten-second rule; the server marked it finished. Not a quirk — the library's configured policy.

**It also creates a conflict PLAY-004 does not anticipate.** The requirement fixes the app's finished
threshold at 95%, configurable 90–99%. This library's rule is "ten seconds remaining", which on a
ten-hour book is 99.97%. The two will disagree constantly, and a book the app calls unfinished can come
back from the server marked finished.

The app should **read the library's thresholds and prefer them**, treating its own setting as the
fallback for a server that reports none. Disagreeing with the server about whether a book is finished
is a bug the user sees as a book that will not stay finished. Recorded here rather than decided
unilaterally: it is a deviation from PLAY-004's literal wording and wants an ADR.
