# ADR-0008: AudioBooth is read as an API reference, and none of its code is used

- Status: accepted
- Date: 2026-08-07
- Related: ADR-0007 (contract capture and licensing), `docs/api-compatibility.md`

## Context

[AudioBooth](https://github.com/AudioBooth/AudioBooth) is an open-source iOS Audiobookshelf client.
The project owner asked for it to be used as a reference for the endpoints and interaction patterns
Phase 1 still lacks — covers (`P1-14`), server-side search (`P1-20`), collections, and the home
shelves.

Two rules meet here. `CLAUDE.md` forbids copied GPL code "without a recorded licensing decision", and
`PRODUCT_SPEC 22.5` forbids relying on a response shape that no captured fixture covers.

AudioBooth is **MPL-2.0**, not GPL. MPL-2.0 is *file-level* copyleft: a file derived from a covered
file stays MPL-2.0 and its source must be made available. It does not infect a project that merely
links to it, and it does not reach code that was written independently.

## Decision

**No AudioBooth source is copied, adapted, translated, or transliterated into this repository.** Not
into Kotlin, not into a comment, not as a renamed structure. Nothing in this tree is a derivative work
of any AudioBooth file, so no file here falls under MPL-2.0 and the project's licence is unaffected.

**What is taken is the set of URL paths an Audiobookshelf server answers.** A path such as
`GET /api/libraries/{id}/search` is a fact about a third-party server's HTTP interface, not expressive
authorship of the client that calls it. AudioBooth is being used the way `docs/openapi.json` is used:
as evidence that an endpoint exists, gathered from a client observed to work against real servers.

**Reading it does not discharge `PRODUCT_SPEC 22.5`.** Knowing that an endpoint exists is not knowing
what it returns. Every endpoint learned this way is added to `scripts/capture-contracts.sh` as a
capture target and stays unmapped until a fixture from a real server is committed. AudioBooth's own
model structs are *not* treated as a schema — they are one client's reading of one server version, and
transcribing them would be both a licence problem and exactly the guess 22.5 exists to prevent.

## What was learned, and what it changed

| Fact | Where it lands |
| --- | --- |
| `GET /api/items/{id}/cover`, optionally `?raw=1` or `?format=jpg` | Confirms the path the capture already probes. The 404 was a bookless cover, not a wrong URL. |
| Covers are fetched with the **`Authorization` header**, by configuring the image pipeline's own HTTP loader — not by putting a token in the URL | Settles the P1-14 design question, and agrees with the standing rule that no token may appear in a URL. |
| `GET /api/libraries/{id}/search?q=` | New capture target (P1-20). |
| `GET /api/libraries/{id}/collections` | New capture target. |
| `GET /api/libraries/{id}/personalized` | New capture target — but see below. |

### Where this project deliberately diverges

AudioBooth builds its home shelves from `/api/libraries/{id}/personalized`, letting the server decide
what "continue listening" and "recently added" contain.

ShelfPlayer derives the same shelves from Room instead. That is not a rejection of the endpoint; it
follows from an architecture rule that predates it — Room is the UI source of truth (`CLAUDE.md`), and
`PRODUCT_SPEC 6.3` requires cached browsing to work offline. A home screen whose shelves come from a
network call is a home screen that is empty on a train, which is the first thing a user of a
self-hosted audiobook app does with it.

The endpoint stays a capture target because it is the right source for anything the client cannot
compute — a server-side recommendation has no local equivalent — and because a captured fixture costs
nothing to hold.

## Addendum, 2026-08-07 — `rasmuslos/ShelfPlayer` is archived

The upstream of `AlexanderWilhelmsenBerg/ShelfPlayer` was **archived on 2026-07-23** and its author
announced the project had been sold. The repository is read-only.

Nothing about this ADR changes: the licence is still MPL-2.0, and reading it for API facts is still
permitted on the same terms. Two practical consequences worth recording:

- **It is frozen.** As a reference for *current* server behaviour it decays from here, so where it and a
  captured fixture disagree, the fixture was already the authority and now obviously so.
- **The fork is the surviving copy.** Anyone following the upstream link in that README lands on an
  archive.
