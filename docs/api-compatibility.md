# Audiobookshelf API compatibility

`PRODUCT_SPEC 19` requires this file to record the server versions tested, the capabilities detected,
known endpoint differences, the fixtures used, and the date last verified.

## Server versions tested

| Server version | Date verified | Auth mode | Websocket | Notes |
| --- | --- | --- | --- | --- |
| 2.36.0 | 2026-08-05 | local (`authMethods: ["local"]`) | not verified | Login, refresh-token behaviour and API-key bearer auth observed directly. Contract capture in CI runs against the same version. |

The app now makes these calls and has signed in/synchronized against real servers on hardware. The table's
date remains the date the server version and contract were explicitly recorded; the private signed-in UI
review on 2026-08-23 did not capture the server version and therefore does not add an inferred matrix row.
No released build has been tested.

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
| `GET /api/libraries/{id}/authors` | `{"authors": [...]}` | `id`, `name`, `numBooks`, `imagePath`, `updatedAt` |
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

### Author portraits: persist the fact, not the path

`library-authors.json` confirms that each author carries nullable `imagePath` and a server `updatedAt`.
The path is an absolute path on the Audiobookshelf host, so the client deliberately reduces it to a
non-sensitive `hasPortrait` flag at the network boundary and never stores or logs the path. The real
`updatedAt` is retained as the image cache key; the fixture's `0` is the capture scrubber replacing a
volatile timestamp, not a client-generated value.

The [published Audiobookshelf OpenAPI](https://github.com/advplyr/audiobookshelf/blob/master/docs/openapi.json)
separately documents `GET /api/authors/{id}/image` and its `ts` query parameter. The UI may construct that
documented endpoint only when `hasPortrait` is true, with
`ts=<updatedAt>` when the server supplied a revision. An author-directory failure is an optional-section
failure under LIB-001: it is logged by typed error code and counted, while the book sync continues and the
UI uses its cover-based fallback. Expanded item responses contain only author id/name, so their Room write
updates the name without erasing portrait facts learned from the richer directory response.

There is not yet a committed request/response capture of a successful author-image response: the contract
fixture's author has `imagePath = null`. A signed-in physical-device review on 2026-08-23 did render one
real server author portrait through the authenticated image client, so the route, credential delivery, and
decoding worked end to end on that deployment. That observation is not an adapter contract: its private
bytes and server identity were not retained, it does not pin headers/status/content type, and it does not
exercise a revision change. The route and `ts` syntax therefore still need a scrubbed successful fixture.
The client never puts a credential in the URL.

The capture used an account with unrestricted item-tag access. It does **not** prove that the author
directory applies the same tag filter as the item catalogue, so profiles with `accessAllTags = false` do
not request it and receive cover-based author artwork only. Returning an unverified directory would expose
names outside that profile's item grant; portrait enrichment waits for a restricted-account capture.

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
| `core/network/src/test/resources/contracts/` | Contract fixtures | **57 files**, committed as of 2026-08-23. Most are scrubbed running-server captures; later sections identify the public-demo and source-derived exceptions and must remain authoritative about their weaker evidence. |

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

### Read, as of 2026-08-14

ADR-0013 settled the deviation and Phase 2 closeout PR 2 implemented it. `LibrarySettingsDto` takes
`markAsFinishedTimeRemaining`, it is stored per library in Room in the server's own unit — seconds, schema 15
— and **the app inherits it**: where a library sets it, that number is the rule for its books, and the
listener's own setting applies only to a library that sets none.

Three notes for anyone reading the fixture:

- **The app keeps no threshold of its own, and does not write the library's back.** Two earlier builds of the
  same day did keep one — a setting, then a `max` of the setting and the library's value — and both could
  disagree with the web interface. The setting is gone. There is nothing on the server to synchronise a
  per-listener value with: **`me.json`'s user object has no `settings` key at all**, so the only writable copy
  is `library.settings`, which is shared by every account and which this app models one field of out of twelve.
  Nothing captured says whether a partial settings PATCH merges or replaces, so writing it back could discard
  eleven settings — including the ordered `metadataPrecedence` — on somebody else's library. ADR-0013 records
  the decision and the fact that it widens the deviation from PLAY-004, which asks for a configurable value.
- **`markAsFinishedPercentComplete` is sent, is `null` in every capture ever taken, and is deliberately not
  read.** The owner rejected the unit: 95% of a hundred-hour book leaves five hours to go. `CapturedShapesTest`
  pins it as *sent and unread*, so a later reader finding it in the fixture does not mistake the omission for
  an oversight. A library configured on a percentage alone will not be honoured by this app — the one place it
  knowingly diverges from the server.
- **No new endpoint was needed.** The plan for PR 2 opened by naming "capture the library-settings endpoint"
  as its blocker. There is nothing to capture: `settings` has been nested in the `GET /api/libraries` response
  since the wave A capture. The test asserts it, so the dependency is pinned to the observation rather than to
  this paragraph.

## The offline routes, captured 2026-08-07

Four fixtures, and they changed the design of the outbox before a line of it was written.

| Fixture | Route | Status | Body |
| --- | --- | --- | --- |
| `session-local.json` | `POST /api/session/local` | 200 | **empty**, `text/plain` |
| `session-local-repeated.json` | the same call again | 200 | **empty**, `text/plain` |
| `session-local-all.json` | `POST /api/session/local-all` | 200 | `{"results":[{"id":…,"success":true,"progressSynced":false}]}` |

### The outbox should use `local-all` even for a single session

`POST /api/session/local` answers `200` with **nothing in it**. It reports neither which session it
accepted nor whether the progress was applied, so a client using it cannot distinguish "stored and
applied" from "stored and ignored".

`POST /api/session/local-all` answers with a per-session result. For an outbox — whose entire job is to
know which entries may be retired and which must be retried — that is the difference between a queue
that drains correctly and one that guesses. So the batch route is the one to use, with a single-element
array when there is one session.

### `progressSynced: false` is the conflict rule, demonstrated

The captured result says `success: true` and `progressSynced: false`, and the reason is instructive:
the capture sent `updatedAt: 0`. The server compares the incoming `updatedAt` against the stored
progress and takes the newer; epoch 1970 is not newer, so the session was recorded and the progress was
**not** applied.

That is the last-writer-wins rule working, observed rather than read. Three things follow:

- **`success` and `progressSynced` are different questions.** An outbox that retired an entry on
  `success` alone would retire one whose progress the server discarded. Both belong in the record.
- The app must send a **truthful** `updatedAt`. It is the entire basis of the server's decision.
- PLAY-005's clock-skew detection is load-bearing rather than diagnostic: a device whose clock runs
  fast wins every conflict it takes part in, and one running slow silently loses progress it thinks it
  saved.

The capture should send a realistic `updatedAt` next time so the *accepted* path is recorded too. The
rejected path is worth keeping regardless — it is the only fixture that shows what a declined sync
looks like.

### What wave 3 built on this, and the one thing it had to leave unsettled

Everything above is captured and is now load-bearing in `AbsPlaybackApi` and
`DefaultSessionSyncRepository`. Two things went in as *stated assumptions* rather than as observed
behaviour, and both are listed on the app's own Settings → About → Testing screen so a device run settles
them rather than a code review guessing:

**1. `timeListened` on the live sync route.** The capture cannot tell an accumulating counter from an
idempotent one, because both syncs sent the same value (noted above). Wave 3 sends the **delta since the
previous sync** on `POST /api/session/{id}/sync`, and the **session total** on the offline route, whose
field is named `timeListening` and whose payload is a whole session. The delta is the conservative
choice: against a server that accumulates it is correct, and against one that replaces it is a small
under-report of one interval rather than a large over-report of the whole session. `ListenedTime` keeps
the two readings apart so the answer can change without changing what either route sends today
(PRODUCT_SPEC 22.5).

Nothing about a **position** depends on this. `currentTime` is set rather than accumulated, which the
capture *did* settle.

**2. The `Date` response header as the clock-skew source.** Not an Audiobookshelf behaviour — it is
HTTP's own field (RFC 9110 §6.6.1) — so it needs no fixture. `ServerClockInterceptor` reads it on every
response and a missing header records **nothing** rather than zero, because "we do not know" and "the
clocks agree" must not look the same in diagnostics. The reading includes the round trip, which is why
PLAY-005's threshold being five minutes matters: the question is not whether the clocks are in sync but
whether this device is wrong enough to corrupt a conflict.

### The route wave 3 does not use

`POST /api/session/local` is captured and deliberately unused, for the reason two sections up: it cannot
report a per-session verdict. `PlaybackService` (the Retrofit interface) has no method for it, so the
absence is structural rather than a convention.

## `startOffset` is global — settled 2026-08-07 (PLAY-003)

`multi-item-play.json`, from a two-file book of six seconds then four:

| Track | `startOffset` | `duration` |
| --- | --- | --- |
| 1 | `0` | 6 |
| 2 | **`6`** | 4 |

Track two starts at six, which is the duration of track one. **`startOffset` is an offset into the
book**, not a per-file zero — and the unequal file lengths rule out "index × duration" as well, which
two equal files would have left open.

Chapters are globalised the same way. The second file's chapter was embedded at `0–4000 ms` *within
that file* and the session reports it as `start: 6, end: 10`:

```
{"start": 0, "end": 6,  "title": "The Ebb"}
{"start": 6, "end": 10, "title": "The Flood"}
```

So neither track offsets nor chapter times need deriving by summing durations — the server has already
done it. What still needs arithmetic is the other direction: Media3 plays a **playlist**, and its
position is per-item, so a global book position has to be mapped to a window index and an offset within
it. `startOffset` is what makes that mapping exact rather than accumulated, and an accumulated one would
drift on a book whose durations are not whole seconds.

## Re-run — 2026-08-13, Audiobookshelf 2.36.0

The owner re-ran `scripts/capture-contracts.sh` against the same server five days after the wave-A
capture and supplied all thirty-one fixtures. **Twenty-nine were byte-identical.** The two that differed —
`item-play.json` and `multi-item-play.json` — differed in exactly two fields:

```
- "date": "2026-08-08",  "dayOfWeek": "Saturday"
+ "date": "2026-08-13",  "dayOfWeek": "Thursday"
```

That is the capture's own wall clock, not the contract. Neither field is read by any mapper. Both are now
in the harness's `VOLATILE_KEYS`, for the reason `lastScan` and `startedAt` are already there: a drift
check that reports a difference on every run teaches its reader to ignore it, and here it very nearly hid
the actual result — **five days of the same server produced no change in the contract at all.**

The committed fixtures were left as they are. Re-committing two files to move a date would be churn, and
the next capture will stabilise both fields anyway.

### What the re-run did *not* settle

It was a re-run of the same script, so it captured the same thirty-one endpoints. Two things this
document has been recording as unverified therefore remain unverified, and both are now probed by the
harness so that the *next* run settles them:

| Unverified | Now captured by |
| --- | --- |
| The bookmark endpoints — create, read back off `user.bookmarks`, update, delete | `bookmark-create`, `me-with-bookmark`, `bookmark-update`, `bookmark-delete` |
| `PATCH /api/me/progress/{id}` with **`isFinished: true`** — the app sends it, no fixture has seen it return | `media-progress-finished`, `media-progress-unfinished` |

Until those fixtures exist, **bookmarks stay unbuilt** (PRODUCT_SPEC 22.4/22.5) and the finished flag's
`true` value stays an assumption — a narrow one, since it is the same field on the same route whose
`false` value round-trips, but an assumption recorded rather than forgotten.

Both were captured the same day; the section below is what they said.

## Bookmarks and the finished flag — captured 2026-08-13, Audiobookshelf 2.36.0

The owner ran the harness again with the two probes the section above added. Six new fixtures. One
question is fully answered, one is answered halfway, and the half that is missing is missing because of a
mistake in the script rather than anything the server did.

### Bookmarks — settled, and buildable (PRODUCT_SPEC 11.1)

| Request | Response |
| --- | --- |
| `POST /api/me/item/{id}/bookmark` `{"time":31,"title":"…"}` | `200` — the bookmark: `{createdAt, libraryItemId, time, title}` |
| `GET /api/me` | the bookmark appears in a top-level **`bookmarks`** array on the user |
| `PATCH /api/me/item/{id}/bookmark` `{"time":31,"title":"…"}` | `200` — the whole updated bookmark |
| `DELETE /api/me/item/{id}/bookmark/31` | `200`, `text/plain`, body `OK` |

Three things a client written from memory would get wrong, and all three are now pinned by
`CapturedShapesTest`:

1. **A bookmark has no id.** It is keyed by its `time` in whole seconds, which is why the delete route
   ends in `31` rather than in a UUID. Two bookmarks at the same second are therefore not expressible.
2. **Bookmarks live on the user, not on the item.** They arrive as one flat array across every book, so a
   client showing one book's bookmarks filters by `libraryItemId` itself. No bookmark endpoint says this;
   only reading `me` back after a create does, which is why the capture does exactly that.
3. **Delete answers with plain text**, not JSON and not `204`. A client that parses the success case as
   JSON throws the first time a user deletes something.

This cleared the block PRODUCT_SPEC 22.4/22.5 placed on the feature, and it is **built** — `AbsBookmarkApi`,
a `bookmarks` table at database version 13, and `AbsBookmarkContractTest` covering all three writes.

One design consequence is worth stating here rather than only in the code, because it is the kind of thing a
future reader will assume is a local shortcut: **the local primary key is `(profile, book, seconds)`**, with
no generated id. That is not a simplification of the server's model, it *is* the server's model, and a
locally-minted id would be an identifier the server could never be asked about.

### `isFinished: true` — settled

`PATCH /api/me/progress/{id}` with `{"currentTime": 8, "isFinished": true}` on an eight-second book reads
back as:

```
"currentTime": 8, "duration": 8, "progress": 1, "isFinished": true
```

The server takes the position it was given and derives `progress` from it. The app already sends the end
of the book when a listener ticks *Finished*, so that behaviour is confirmed rather than assumed.

### `isFinished: false` — accepted, then overruled by the server's own threshold

The first probe was badly built: it sent `{"currentTime": 42.5, "isFinished": false}` to a book **eight
seconds long** and discarded the PATCH response with `>/dev/null`. The fixed probe sends `currentTime: 2`
and records the response, and the CI run answers the question outright.

**The PATCH is accepted** — `200`, `text/plain`, body `OK`, same as the bookmark delete. So the server does
not reject an un-finish, which was the worrying of the two possibilities.

What overrules it is the server's own rule, and the container log names it:

```
[MediaProgress] Marking media progress as finished because time remaining (8)
                is less than 10 seconds (media item …)
```

That is **`markAsFinishedTimeRemaining`**, default ten seconds. The contract library's book is eight
seconds long, so *every* position in it is inside the last ten and it can never be anything but finished.
This is a property of the fixture, not of the API: a real thirty-hour book at two seconds is nowhere near
its last ten.

So PLAY-004's "marking finished is explicit, in both directions" is **not** at risk, and the app's
*Finished* checkbox does reach the server. Two consequences worth carrying forward:

1. **`markAsFinishedTimeRemaining` is real, and its default is 10 s.** PLAY-004 requires the app to honour
   the library's value, and as of 2026-08-14 it does — see the section above. This log line is the first
   observation of the setting *in action*, which is a stronger fact than reading it in `libraries.json`:
   it shows the server applying the rule to a write the app made.
2. **The fixture cannot demonstrate un-finishing.** Doing so needs a seeded book longer than the threshold,
   which changes the duration in a dozen committed fixtures. That belongs with the
   `markAsFinishedTimeRemaining` work rather than bolted onto this capture, and is recorded in
   `docs/archive/phase-2-gaps.md` as such.

### Two fixtures that were only ever capture noise

The drift check was red on every commit of this branch for two reasons, neither of them the server:

- **`library-personalized`'s shelves were arriving in a different order.** "Recently added" sorts by
  `addedAt`, both books are added by the same scan in the same second, and the tie is broken arbitrarily.
  The harness now sorts `entities` by title — the same treatment `VOLATILE_KEYS` gives a timestamp, and for
  the same reason: a check that goes red on every run is a check its reader learns to ignore, and this one
  was hiding the two genuinely new fixtures underneath it.
- **`media-progress-set-finished` and `media-progress-set-unfinished` had never been committed**, which is
  the expected state of the pull request that adds a capture target. They are committed now.

---

## The management endpoints, captured 2026-08-15 against 2.36.0

The first capture of anything in EPIC MGR or EPIC USER. Everything below is observed, not documented —
PRODUCT_SPEC 22.4 and 22.5 exist because none of it could be guessed, and three of these findings
contradict what a client written from the requirements alone would have assumed.

| Endpoint | Status | Body | Content type |
| --- | --- | --- | --- |
| `POST /api/items/{id}/scan` | 200 | `{"result": "UPTODATE"}` | JSON |
| `POST /api/items/{id}/match` | 200 | `{"warning": "No google match found"}` | JSON |
| `PATCH /api/items/{id}/media` | 200 | `{"libraryItem": { … }}` | JSON |
| `DELETE /api/items/{id}/cover` | 200 | `OK` | **text/plain** |
| `POST /api/libraries/{id}/scan` | 200 | `OK` | **text/plain** |
| `GET /api/users` | 200 | `{"users": [ … ]}` | JSON |
| `POST /api/users` | 200 | `{"user": { … }}` | JSON |
| `DELETE /api/items/{id}` | 200 | `OK` | **text/plain** |
| `GET /api/items/{id}` after deletion | 404 | `Not Found` | text/plain |

## The server's own listening history, captured 2026-08-27 against 2.36.0

`GET /api/me/listening-sessions?itemsPerPage=10&page=0`, fixture `me-listening-sessions.json`.

### Why it matters, and what the app was doing instead

The history pane's remote rows are *derived*: `LibrarySnapshotWriter.recordRemoteChange` diffs a book's
stored progress against what a sync just fetched. That works, and it has two holes it cannot close.

- **It needs a previous row.** It returns early when `before == null`, so a book listened to elsewhere that
  this device has never played produces no history at all.
- **It only sees the endpoints.** Two sessions between one sync and the next collapse into one row, and a
  session that started and finished inside that window is invisible.

This endpoint is the server's own record rather than a reconstruction.

### The envelope and the units

| Field | |
| --- | --- |
| `sessions[]`, `total`, `page`, `itemsPerPage`, `numPages` | paged; `total` is the account's whole history, not the page |
| `timeListening`, `startTime`, `currentTime`, `duration` | **seconds** (fractional — the capture shows `5.5`) |
| `startedAt`, `updatedAt` | **epoch milliseconds** |
| `deviceInfo.deviceId` / `deviceName` / `clientName` | which device and which client played it |

**The units are the part a reader guesses wrong**, which is why they are pinned by test: seconds and
milliseconds sit side by side in the same object, and reading `startedAt` as seconds puts a row in 1970.

`timeListening` is not elapsed wall time — a paused session accrues none.

### One field this app must never keep

`deviceInfo.ipAddress` is in the response. `ListeningSessionDeviceDto` models three fields and not that
one, and `ListeningSessionContractTest` asserts **both** halves: that the wire still carries it, and that
it does not reach the model. Same rule as `GET /api/users`' live token — the way to be certain a field is
never logged is for no type to hold it (14.5).

`chapters`, `mediaMetadata`, `coverPath` and `bookId` are present and deliberately dropped: the app already
holds them for a book it knows, and a history row is the wrong source for one it does not.

### How the app reads it, and the three filters

`PlaybackHistoryRepository.refreshServerSessions` is called when the history pane opens — on the player and
on the book screen — not on every sync. **The server has no per-book route**, so this reads a page of the
*account's* sessions and keeps the ones for the book being looked at; putting that on `SyncAccountUseCase`
would add a request to the app's cheapest and most frequent call, for data one screen reads.

The rows are **persisted** into the same table the local events use, so the pane fills from Room and the
imported rows are still there with no network. Merging at read time was the alternative and it makes the
remote half vanish exactly when somebody is most likely to be wondering where their position went.

Three filters, each removing a specific false row:

- **this book only**, because the endpoint is account-wide;
- **other devices only** — this phone's own sessions come back too and would duplicate the `Play` and
  `Pause` rows the player already writes. Told apart by the per-install `deviceId` the app sends when it
  opens a session (`PlaybackDeviceIdentity`). A session with **no** device id counts as another device's:
  dropping a real session from a client that did not identify itself is a bigger loss than a duplicate row;
- **something actually listened**, because opening a book and closing it leaves a zero-second session.

Importing is **idempotent without a Room migration**: the history row's `entryId` is `abs-session:` plus the
session's own id rather than a fresh UUID, and the table's primary key was already a `String`.

A failed fetch is logged and otherwise silent. A pane whose local half is good must not become an error
because the network was not there, and the rows from earlier refreshes stand.

---

## The embed task's own frames, captured 2026-08-23 against 2.36.0

`POST /api/tools/item/{id}/embed-metadata` answers `200` when the task is **queued**. What happened to the
audio files arrives later on the websocket, and until now nothing had watched it: `TaskFrames.parse` was
written from Audiobookshelf's source, and `TaskFramesTest` said so in its own header — *"source-derived
rather than captured"*.

An embed produces four events, in this order, and `socket-embed-task.json` is those four:

| Event | What it says |
| --- | --- |
| `task_started` | `isFinished: false`, `action: "embed-metadata"` |
| `track_started` | `ino` and `libraryItemId` only — **newly observed**, and not a task |
| `track_finished` | the same two fields |
| `task_finished` | `isFinished: true`, `isFailed: false`, `error: null` |

### The poll window also carries frames the fixture does not

The fixture is the four above and nothing else, and that is a decision the 2026-08-23 CI run forced. The
frames arrive when the task runs rather than when the client asks, so a poll boundary can fall anywhere:
the runner's window also held the socket's own `init` frame and two progress events that a local capture
of the same server version had not seen. Committing whichever ones happened to land would make the file
report drift against a server that had not changed — R-53's false positive from the other side, where
there a frame was lost and here frames were gained. `capture-contracts.sh` therefore records the first of
each of the four lifecycle events and drops the rest.

Dropped is not unobserved, so the two progress events are written down here instead:

| Event | Fields |
| --- | --- |
| `task_progress` | `libraryItemId`, `progress` (`0.5` with one of two files tagged) |
| `track_progress` | `ino`, `libraryItemId`, `progress` |

Nothing in the app reads either. They would be the obvious source for a determinate progress bar in place
of the indeterminate one `EmbedTaskWatcher` drives today, and anybody building that should capture them
deliberately rather than trust this table — it is one observation from a CI log, not a fixture a check
holds the server to.

### Every field the parser names is real

`id`, `action`, `data.libraryItemId`, `isFinished`, `isFailed` and `error` are all present and mean what the
app assumed. That is worth recording as plainly as a defect would have been: the whole embed progress
surface — `EmbedTaskWatcher`, the pending state, the confirmation the user reads — hangs off those six
names, and one of them being wrong would have meant a UI that silently never completes.

`libraryItemId` is nested inside `data`, not at the top level. Moving it up is the single most plausible
wrong guess, and `TaskFramesTest` now fails if anybody makes it.

### And the private half is exactly what was predicted

`TaskFrames`' comment argued against a `@Serializable` DTO on the grounds that *"the next person to need
something adds `description`, which is `Embedding metadata in audiobook "<the book's title>"`"*. The capture
proves that verbatim, and adds three more:

- `description` and `descriptionSubs` both carry the book's title;
- `data.libraryItemDir` is `/audiobooks/<author>/<title>` — a path inside somebody's library;
- `data.itemCachePath`, `data.coverPath` and `data.audioFiles[].path` are more of the same.

None of it is deserialized. `ServerTask` holds five values and the test asserts that none of those strings
survives a parse, checked against the parsed task's `toString` because that is what a log line would render
(PRODUCT_SPEC 14.5).

### What is still not proven

That a **failed** embed reports itself. `isFailed` and `error` are read, and no capture exercises them —
provoking a real failure needs a file the server cannot write, which the throwaway container makes awkward
to arrange. The success path is evidence; the failure path is still source-derived.

---

## The three privileged writes, captured 2026-08-23 against 2.36.0

The 2026-08-22 review's third P0: cover upload, metadata embedding and user activation were live
production writes with **no captured contract**. They are captured now, together with each one's refusal —
and one of the four findings contradicts a claim this repository had written down as fact.

| Endpoint | Status | Body | Content type |
| --- | --- | --- | --- |
| `POST /api/items/{id}/cover` (multipart) | 200 | `{"cover": "…", "success": true}` | **JSON** |
| `POST /api/tools/item/{id}/embed-metadata?backup=1` | 200 | `OK` | text/plain |
| the same request again, immediately | **400** | `Library item is already in queue or processing` | **text/html** |
| `PATCH /api/users/{id}` | 200 | `{"success": true, "user": { … }}` | JSON |
| any of the three, as a non-admin | **403** | `Forbidden` | text/plain |

### The upload answers JSON, and it is the only write here that does

Every other write on this API answers `OK` as `text/plain`. This one returns an object — and what it
returns is a **server filesystem path** plus a flag, neither of which a screen can render. That is why
`AbsManagementApi.uploadCover` follows a success with `library.fetchBook`: the response confirms the write
and describes nothing, so the book has to be read back. Pinned by
`AbsManagementContractTest`, including the absence of that second read after a refusal.

### The multipart part must be named `cover`

A part named `image` is refused. `AbsManagementApi` therefore builds the part by hand rather than letting
Retrofit name it from the parameter, and the contract test asserts the encoded body contains
`name="cover"` — the only place in the request where that name survives.

### The duplicate-embed `400` is `text/html`, and this file used to say otherwise

`ManagementService`'s KDoc asserted both embed `400`s arrive as `text/plain`, *"like every other
`sendStatus` route on this API"*. The capture shows `text/html`. Nothing breaks — `acceptanceOf` matches on
the sentence, not the declared type — but the comment was wrong and is now corrected in place. It is
recorded here because it is a small, exact instance of what PRODUCT_SPEC 23 warns about: a plausible
generalisation from the routes somebody had already seen, believed because it was written down.

### `PATCH /api/users/{id}` also returns a live token

The section below records that `GET /api/users` hands back every user's working credential. The activation
route does the same thing for the single user it updates: the `user` object in its response carries a
`token`. The same rule applies and is now proven by test rather than by inspection — `setUserActive`
returns `AppResult<Unit>` and closes the body without parsing it, and
`AbsManagementContractTest` fails if that ever changes.

### The refusals carry no JSON at all

All three answer `403` with the bare text `Forbidden`. A client that tried to read an error envelope for a
message to show the user would find none, which is why every refusal path in this app produces its own
sentence rather than relaying the server's.

---

### `GET /api/users` returns every user's live token

**The most important thing these captures found.** Each element of `users` carries a `token` field, and it
is a working API credential for that account — not a hash, not a placeholder. An admin signing in to this
app is handed credentials for everybody else on the server.

USER-001 says tokens are never *displayed*. That is not a strong enough rule for a field like this, so the
rule this project adopts is stricter:

> **The app never models the field.** `UserDto` has no `token` property. There is nothing to store, nothing
> to log, nothing to put on a screen by accident, and nothing for a future refactor to expose.

A field that is never parsed cannot leak. `CapturedShapesTest` pins the finding so the absence reads as a
decision rather than an oversight.

The capture script's redaction already replaces it with `<redacted-secret>` in the committed fixture, so
the repository is safe; the wire is not.

### A created user is inactive

`POST /api/users` with a username, a password and a type answers with `isActive: false`. The account
exists and **cannot sign in**.

USER-002 therefore cannot report "user created" and stop. Either the request has to carry `isActive`, or
the screen has to say that somebody still needs to activate the account — and which of those is right
depends on whether the server accepts the field, which this capture does not answer because it did not
send one.

### Three endpoints answer `text/plain`

Cover removal, library scan and item deletion all return the two characters `OK`. A client that assumed
every 2xx carried JSON would fail to parse a success and report a failure — for the deletion, that means
telling somebody their book is still on the server when it is gone.

### The metadata PATCH returns the whole item

`PATCH /api/items/{id}/media` answers with `{"libraryItem": …}` — the complete updated item, in the same
shape as the expanded single-item read.

That settles how MGR-001's *"On success, Room updates immediately and then refreshes from server"* should
work: **the refresh is the response.** A follow-up `GET` would be a request for data the client already
holds, with a window in which the two could disagree.

### The two scans are not the same kind of operation

An **item** scan is synchronous and reports a conclusion: `{"result": "UPTODATE"}`. A **library** scan
acknowledges with `OK` and runs on afterwards.

MGR-004 asks for "started, running if detectable, completed, and failed". On this server an item scan has
no detectable running state — it is over before the response arrives — while a library scan has no
completion the response can report. They need different treatment, and neither response says so.

### What the captures did **not** settle

- **A successful match.** `POST /api/items/{id}/match` with an empty body defaults to the **Google**
  provider and found nothing, so the only shape recorded is the miss. MGR-003 requires showing "provider,
  candidate title, author, year, cover, and fields that will change", and none of that is in this capture.
  The container has no provider key and nothing to match against; a successful match needs a different
  fixture environment, not a different script.
- **Cover upload.** Deliberately not attempted: it needs a multipart body and an image the capture script
  has no business inventing. Only removal is recorded.
- **Source-file deletion (MGR-006).** No endpoint was probed, because none is known to exist. MGR-006's
  first criterion — the action does not exist unless the server reports a dedicated capability — is
  currently satisfied by there being no capability and no action.
- **Permission failures.** Every capture ran as `root`, so every management response here is the
  *permitted* one. What a `403` looks like on these routes is still unknown, and the app's gating is
  therefore built on the permissions in `me.json` rather than on recognising a refusal.

---

## What the official project's own source settles, 2026-08-15

Everything above this line was **captured**: a real request, a real response, a fixture on disk. This
section is different, and the difference is the point. It was **read from the Audiobookshelf project's
own source** at `advplyr/audiobookshelf` v2.36.0 — the same version the captures ran against — because
four questions the captures left open cannot be answered by any capture this project can run.

ADR-0012 records the licensing posture and it applies unchanged here: *read it for API facts, do not copy
code.* Nothing below is source. It is a description of observable HTTP behaviour, in this project's own
words, of the kind an integrator would write after watching the wire.

**This is weaker evidence than a capture and is treated as such.** PRODUCT_SPEC 22.5 requires a fixture
before the app relies on a response shape, and reading a server's source is not a fixture: it proves what
that version's code does, not what a user's deployment behind their reverse proxy actually returns. Every
finding here is therefore marked with what still has to be captured, and the code written against it
**fails closed** — an unrecognised shape reads as "not supported", never as "assume it worked".

### The mobile app was the wrong place to look

`advplyr/audiobookshelf-app` was read first, and the finding is worth recording so nobody repeats it: the
official mobile app **has no management surface at all**. Its only writes are progress, authentication,
playback sessions and podcast-feed lookups. It never edits metadata, uploads a cover, runs a match,
scans, deletes an item or touches `/api/users`.

So it settles none of EPIC MGR. What it does settle is that a native Audiobookshelf client can be
complete without any of it — which is a fact about scope, not about endpoints.

### `sendStatus` is why three endpoints answered `text/plain`

The captures found cover removal, library scan and item deletion answering `text/plain "OK"` instead of
JSON, and recorded it as three separate quirks. It is one: those handlers end with Express's
`sendStatus`, which writes the status *name* as a plain-text body.

That generalises in a way that matters more than the original finding. **Every refusal on these routes is
`text/plain` too** — `403` arrives as the body `Forbidden`, `404` as `Not Found`. A client that parses a
management failure as JSON gets a parse error where it should get a permission error.

The app's `NetworkErrorMapper` already keys on the status code rather than the body, so this costs nothing
today. It is recorded because the obvious future change — reading an error message out of a failed
management response — would be wrong on exactly these routes.

**Captured 2026-08-15.** The capture run creates an active non-admin account and attempts three management
operations with it. All three are refused with **`403`, `Content-Type: text/plain`, body `Forbidden`** —
committed as `item-update-forbidden`, `item-delete-forbidden` and `item-scan-forbidden`, and pinned by
`CapturedShapesTest`.

The scan refusal is the one worth reading twice. The refused account holds `download` and nothing else, so
the update and delete refusals are explained by its grants — but the scan refusal is not: the server gates
scanning on being admin or root, and that account would be refused holding every permission there is.

### Permissions are checked per-method, and cover upload needs two grants

The item routes gate on the HTTP method: `DELETE` requires the account's delete grant, and `PATCH` or
`POST` requires the update grant. Playback session creation is exempt, which is why an ordinary listener
can start a book.

Two routes then check *again*, more narrowly:

| Route | Grant required |
| --- | --- |
| `PATCH /api/items/{id}/media` | update |
| `DELETE /api/items/{id}` | delete |
| `DELETE /api/items/{id}/cover` | delete |
| `POST /api/items/{id}/cover` | update **and** upload |
| `POST /api/items/{id}/scan` | admin or root |
| `POST /api/libraries/{id}/scan` | admin or root |
| `POST /api/items/{id}/match` | update |
| every `/api/users` write | admin or root |

The cover row is the one a client would get wrong. Uploading a cover is a `POST`, so it passes the
method gate on the *update* grant, and is then refused separately unless the account also has *upload*.
An account with update but not upload can edit every metadata field and cannot change the cover.

Item and library scanning are **not** permission-gated at all — they are account-*type*-gated. A user with
every grant set is still refused if their type is `user`. That is why `Profile.role` is derived from the
account type and not from the grants (MGR-004: "Item scan appears only for roles/endpoints that allow it").

*Still to capture:* all of the above as observed refusals.

### `POST /api/items/{id}/match` is not a preview — it is the edit

This is the finding that changes a slice.

MGR-003 asks that the user see "provider, candidate title, author, year, cover, and fields that will
change" before committing. Quick match cannot provide that, because **it applies the change and then
tells you what it did**. It takes the first result the provider returns, downloads the cover, writes the
fields and saves — all before it responds.

The two shapes are:

| Outcome | Body |
| --- | --- |
| No candidate found | `{"warning": "No <provider> match found"}`, status `200` |
| A candidate was applied | `{"updated": true|false, "libraryItem": { … expanded item … }}` |

The captured miss is therefore the *whole* of what quick match can be used for as a preview: nothing.
`updated: false` means a candidate was found and changed nothing, not that nothing was found.

**So MGR-003's preview is built from search, not from match:**

- `GET /api/search/providers` lists the metadata providers this deployment has, including any custom ones
  the server administrator configured. Read-only, no side effects, no admin required.
- `GET /api/search/books?title=&author=&provider=&id=` returns the candidate list **without writing
  anything**. `provider` defaults to `google`, which needs no API key.
- The user picks a candidate, and the app applies the chosen fields with `PATCH /api/items/{id}/media` —
  the endpoint MGR-001 already uses, already captured, already returning the whole updated item.

That is the flow that satisfies "existing non-empty fields are not overwritten without an explicit
choice", and quick match structurally cannot: its `overrideCover` and `overrideDetails` flags are the only
control it offers, and they are all-or-nothing.

Candidate fields vary by provider. The union across the providers the server ships is:

`title`, `subtitle`, `author`, `narrator`, `publisher`, `publishedYear`, `description`, `cover`, `isbn`,
`asin`, `genres`, `tags`, `series` (name and sequence), `language`, `duration` in minutes, `abridged`.

Google — the default, and the only one needing no configuration — returns a strict subset: `id`, `title`,
`subtitle`, `author`, `publisher`, `publishedYear`, `description`, `cover`, `genres`, `isbn`. **Every
field must be modelled as optional**, including ones that look mandatory.

Two of those fields are hazards rather than data. `cover` is a URL on a third party's host — MGR-002's
"tokens are not appended to third-party cover URLs" is about exactly this value. `description` is
provider-supplied HTML, which is what MGR-003 means by "match results are treated as untrusted display
data and sanitized".

*Captured 2026-08-15, with one finding the capture itself produced:* **Google Books rate-limits GitHub
Actions.** Every candidate search from CI answers `429`, so `search-books-shape.json` records an empty
result set and will keep doing so. The endpoint works; the *shape* of a populated result cannot be captured
from a shared CI address, and would need a run against a real deployment.

That is a fact about where the capture runs rather than about the server.

### A run against a real deployment, 2026-08-16

`audiobooks.dev` — a public demo instance on 2.36.0, public-domain material only — settled the rest, and
produced two findings that changed the code.

**The default provider is not reliably the working one.** Google and Open Library returned *empty lists* for
every query there; Audible returned six populated results for the same title. Reachability is a property of
the server's own outbound network, exactly like the websocket, and a client that hardcodes one source turns
"this deployment cannot reach Google" into "this book has no metadata anywhere". MGR-003 now reads the
provider list and lets the user pick.

That deployment also lists **two custom providers**, with `custom-<uuid>` slugs — the case the provider list
exists for, and one no hardcoded list could have.

**The server sends a sanitised description.** Alongside the HTML `description` it sends `descriptionPlain`,
the same text stripped. That is the field this app reads, and the HTML one is never touched: MGR-003 wants
match results sanitized, and declining to handle markup at all is a stronger guarantee than stripping it.

The full key set of an Audible candidate, recorded in `search-books-shape.json`:

`abridged`, `asin`, `author`, `cover`, `description`, `descriptionPlain`, `duration`, `genres`, `isbn`,
`language`, `narrator`, `publishedYear`, `publisher`, `rating`, `region`, `series`, `subtitle`, `tags`,
`title`.

`cover` was observed pointing at `m.media-amazon.com`, which is exactly the third-party host MGR-002's
"tokens are not appended to third-party cover URLs" is about.

**The refusals reproduce on a second, independent server.** The `demo` account there is an ordinary `user`
with `download` and nothing else, and every management route refuses it with `403` and
`Content-Type: text/plain; charset=utf-8`:

| Request | Response |
| --- | --- |
| `PATCH /api/items/{id}/media` | `403 text/plain` |
| `POST /api/items/{id}/cover` | `403 text/plain` |
| `POST /api/items/{id}/scan` | `403 text/plain` |
| `GET /api/users` | `403 text/plain` |

That is the CI capture confirmed against a deployment nobody involved in this project configured, which is
the strongest form this evidence can take.

It is also why **no write contract can be captured there**. The demo account holds no update, delete or
upload grant, so the cover-upload shape stays source-derived until it is exercised against a server whose
account has the grants.

### Cover upload takes a file **or** a URL, and validates on the filename

`POST /api/items/{id}/cover` accepts either:

- a JSON body `{"url": "…"}`, and the server fetches it; or
- a multipart body with the file part named exactly **`cover`**.

The server decides whether an upload is an image **by the extension on the multipart part's filename** —
`png`, `jpg`, `jpeg` or `webp` — and not by the `Content-Type` or by sniffing the bytes.

That is a genuine trap for an Android client. Android's Photo Picker hands back a content URI whose
display name is frequently absent, extensionless, or `.jpeg` where the server would also have accepted
`.jpg`. **The app must synthesise the filename from the MIME type it validated**, rather than passing the
picker's name through. A perfectly valid PNG sent as `image` is refused; the same bytes sent as
`cover.png` are accepted.

Success is `{"success": true, "cover": "<absolute path on the server>"}`. Failures are `400` or `500`
with a plain-text body.

There is no server-side size or dimension limit on this route, so MGR-002's "configured size limit" is
entirely the app's own policy — the server will accept whatever it is sent.

Cache invalidation works by timestamp: a successful upload bumps the item's `updatedAt`, and
`GET /api/items/{id}/cover?ts=<updatedAt>` is what makes a client fetch the new bytes. The server sets a
24-hour private `Cache-Control` **only** when `ts` is present, so a request without it is not cached and a
request with it is cached under a key that changes on every update. That is MGR-002's "cover cache
invalidates after successful update", and it is a URL convention rather than an endpoint.

*Still to capture:* the upload itself, which needs a multipart body and an image.

### Source-file deletion exists, and the server cannot prove it happened

MGR-006 was written as though no such endpoint might exist. Two do:

- `DELETE /api/items/{id}?hard=1` — removes the database rows **and** recursively removes the item's
  directory from the server's filesystem. Without `hard`, the same route removes only the rows, which is
  the MGR-005 operation and is already captured.
- `DELETE /api/items/{id}/file/{ino}` — removes one file, identified by its inode number, and updates the
  item to no longer list it.

So the first half of MGR-006's gate is satisfiable: the capability is real, and `?hard=1` is the
difference between the two requirements. **The second half is not.**

On both routes the filesystem removal is attempted, and **if it fails the failure is logged on the server
and the request still succeeds.** The response to a hard delete is `200 OK`; the response to a file delete
is the updated item. Neither carries any indication of whether the bytes are gone. A read-only mount, a
permissions error, a file held open — all of them produce the same success the client sees when the delete
worked.

Nor can the app check afterwards. Once the file is removed from the item's file list, asking for it
returns `404` whether or not it still exists on disk, because the `404` comes from the item's list and not
from the filesystem.

MGR-006 requires: *"The server response must explicitly confirm deletion"*, and *"If the server cannot
prove deletion, the UI reports uncertain state and does not claim success."*

**The server cannot prove it.** `ServerCapability.SourceFileDelete` is therefore never confirmed by any
probe, and the reason is this paragraph rather than an absence of investigation. See ADR-0021.

### Scanning: one endpoint answers, the other only acknowledges

The captures found that an item scan is synchronous and a library scan is not. The source says why, and
adds the vocabulary.

`POST /api/items/{id}/scan` runs the scan and then answers with its conclusion, one of
`NOTHING`, `ADDED`, `UPDATED`, `REMOVED`, `UPTODATE`. It refuses file-based library items with a `500`
and the server log line "Re-scanning file library items not yet supported" — a client cannot tell that
case apart from a real server error, so a `500` here must be reported as a failed scan rather than a
crash.

`POST /api/libraries/{id}/scan` answers `200 OK` **before starting**, and `?force=1` is MGR-004's "force
rescan". The acknowledgement is not a result and must never be shown as one.

*Still to capture:* a scan of an item that actually changes, to see a `result` other than `UPTODATE`.

### `GET /api/search/providers`

The one management-adjacent endpoint that is a genuine, honest capability probe: read-only, no side
effects, available to any signed-in account, and answering a question that varies by *deployment* rather
than by version, since an administrator can configure custom providers.

The shape is `{"providers": {"books": [{"value": …, "text": …}], "booksCovers": […], "podcasts": […]}}`.

`value` is what `GET /api/search/books?provider=` takes; `text` is a display name. A server too old to
have this route answers `404`, which is the correct "not confirmed" signal and needs no version check.

**Captured 2026-08-15**, and the answer is more useful than expected: a server with nothing configured
still lists fourteen book providers — Google, iTunes, Open Library, FantLab and ten Audible regions. So the
probe confirms on every ordinary deployment rather than only on a configured one, and `google` being the
default and key-free is what makes MGR-003's candidate search work without setup.

`booksCovers` adds `best`, `audiobookcovers` and `all`; `podcasts` holds only `itunes`.

## Embedding metadata into source files, read from the server at 2.36.0

Source-derived, not captured. Starting an embed needs `isAdminOrUp` and the public demo account is an
ordinary `user`, so there is no run to record — the same position cover *upload* is in, and recorded as such
in `docs/gaps.md`. Read under ADR-0012's amended posture: **read it for API facts, never copy code.**

### The route

`POST /api/tools/item/{id}/embed-metadata`, with `backup` and `forceEmbedChapters` as `0`/`1` query
parameters. `isAdminOrUp` in the router's middleware, with **no reference to `permissions.update`** — so an
account holding update, delete and upload is refused unless its `type` is `admin` or `root`. The same gate
both scans use.

Answers:

| Status | Meaning |
| --- | --- |
| `200` | the task was **queued**. Nothing has been written yet. |
| `400` "Library item is already in queue or processing" | this item is already being embedded |
| `400` | not a book, or a book with no audio tracks |
| `403` `text/plain` "Forbidden" | the account is not an administrator |

`text/plain` throughout, like every other `sendStatus` route on this API.

### `backup=1` is narrower than the word

`AudioMetadataManager` copies each audio file to `Path.join(task.data.itemCachePath, af.filename)` before
rewriting it, and removes the copy afterwards unless `backupFiles` is set. That is a working copy inside the
server's cache directory — a safety net for the operation, not a backup a user could restore from. This app
always sends `1` (sending `0` gives up the net for nothing) and the confirmation dialog says the distinction
out loud, because MGR-007's *"advise the user to maintain server-side backups"* is not satisfied by a flag.

### The outcome arrives only on the websocket

`TaskManager` emits `task_started` and `task_finished`, both carrying `Task.toJSON()`:

```
{id, action, data, title, titleKey, titleSubs, description, descriptionKey, descriptionSubs,
 error, errorKey, errorSubs, showSuccess, isFailed, isFinished, startedAt, finishedAt}
```

- `action` is `embed-metadata`
- `data.libraryItemId` is the correlation key
- a failure calls `setFailed()`, which sets `error` and `isFailed` and then calls `setFinished()` — so a
  failure arrives as **one** `task_finished` with `isFailed: true`, not as a separate event
- `AudioMetadataManager` also emits `metadata_embed_queue_update`, `track_started`, `track_finished`,
  `task_progress` and `track_progress`. None is needed to answer "did it work", and none is modelled.

**`description` is `Embedding metadata in audiobook "<the book's title>"`**, and `descriptionSubs` carries
the title again, and `data.libraryItemDir` carries the path. A book title and a library path are private
self-hosted data (PRODUCT_SPEC 14.5), so `ServerTask` models none of the four fields and `TaskFrames` never
deserializes them — `TaskFramesTest` plants a title in all three places and asserts none comes out.

### There is no way to ask afterwards

The item's own fields do not change when an embed finishes: they are the *input* to the write. So a `GET` on
the item cannot distinguish running from finished from failed, and a client that missed `task_finished` has
no second source. That is why a dropped connection is reported as an unknown outcome rather than as either
answer.

## An excluded file never reaches `media.tracks` — settled 2026-08-20 (PLAY-003)

`docs/gaps.md` carried an open defect from Phase 2 onwards:

> **Excluded tracks and the timeline's coordinate space.** A book whose server-side track list excludes a
> file resolves positions against the wrong offsets.

The reasoning behind it was sound and its premise was wrong. The premise was that `media.tracks` carries
every audio file with a global `startOffset`, some of them flagged `exclude: true` — so a player that
concatenates only the playable ones produces a timeline shorter than the book's, and every position after
the hole is wrong by the excluded file's length.

`server/models/Book.js`, read at 2.36.0 under ADR-0012's amended posture, settles it:

```js
get includedAudioFiles() {
  return this.audioFiles.filter((af) => !af.exclude)
}

getTracklist(libraryItemId) {
  let startOffset = 0
  return this.includedAudioFiles.map((af) => {
    const track = structuredClone(af)
    track.title = af.metadata.filename
    track.startOffset = startOffset
    track.contentUrl = `/api/items/${libraryItemId}/file/${track.ino}`
    startOffset += track.duration
    return track
  })
}
```

**The filter runs before the accumulation.** An excluded file is gone before any offset is computed, so
`media.tracks` — and `session.audioTracks`, which is the same list — is always contiguous, always
exclusion-free, and always exactly the concatenation the player builds. There is no second coordinate
space, and there is nothing to convert between.

Two consequences worth stating, because both look like dead code and neither should be deleted:

- `AudioTrack.isExcluded` is always `false` in anything that came from a server. It is `exclude` copied
  off the `AudioFile` the track was cloned from, and the clone only ever happens for a file that is not
  excluded. `PlaybackSession.playableTracks` filters on it and the filter is always a no-op.
- `media.audioFiles` **does** contain excluded files, and they carry no `startOffset` at all — the field
  is added by `getTracklist`. Anything reading `audioFiles` for timeline purposes would be reading a list
  the server never intended as one. Nothing does; `LibraryMapper` reads `media.tracks`.

`CapturedShapesTest` pins the invariant against the committed fixtures, so a server that ever changed its
mind fails a test instead of moving somebody's bookmark. That is the whole mitigation, and it is enough:
the app already treats the player's position as the book's position (ADR-0016), which is correct precisely
because of the code above.

What is **not** settled is what the *web client* does when a user excludes a file from a book they are
part-way through. The server recomputes offsets, so a stored progress position taken before the exclusion
now points somewhere else in a shorter book. That is a server-side data question, it affects every client
equally, and this app has no way to detect it.

## The supported version range, and what is actually verified

**Floor: 2.26.0.** Enforced at sign-in by `ServerVersion.Minimum` (ADR-0024). Below it the server issues no
refresh token, so a session could not be renewed and AUTH-004's silent renewal would fail hours after a
successful sign-in.

**Verified: 2.36.0, and only 2.36.0.** Every fixture in `core/network/src/test/resources/contracts/` was
captured against that version.

So the range 2.26.0 to 2.35.x is **accepted and unverified**, and that is a decision rather than an
oversight: refusing it would turn an untested-but-probably-fine server away for the sake of a claim this app
cannot make either way, since 2.30 has been tested exactly as much as 2.26 has. If a report ever arrives
from a server in that range, this is the paragraph that explains why it was allowed to connect.
