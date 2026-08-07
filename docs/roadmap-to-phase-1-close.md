# Closing Phase 1 — remaining work, in waves

Written after 0.1.7, with every line checked against the tree rather than carried forward from
`docs/phase-1-remaining.md`. That document is the original audit; this one is the plan.

---

## A note on the two reference clients

Both are **MPL-2.0**, and neither is ours to copy from.

- **AudioBooth** — `github.com/AudioBooth/AudioBooth`. Third-party, MPL-2.0. Read for API facts under
  ADR-0008.
- **`AlexanderWilhelmsenBerg/ShelfPlayer`** — a **fork of `rasmuslos/ShelfPlayer`**. Every internal
  link in its README points at the upstream repository, and its LICENSE is upstream's MPL-2.0.
  Owning a fork does not transfer copyright in the upstream code, so it is read on the same terms as
  AudioBooth: **API facts only, no copying**. It is also Swift, so "copying" into a Kotlin app would
  be a translation — which for copyright purposes is a derivative work, not an escape from one.

Neither restriction has cost anything. The value in both is what the *server* does, and that is not
copyrightable.

---

## What the two clients agree on, that we now match

| | Both clients | ShelfPlayer (Android) |
| --- | --- | --- |
| Populate the shelf from `…/items` | ✅ | ✅ **as of P1-31** |
| Expand `…/items/{id}?expanded=1` only when needed | ✅ | ✅ **as of P1-31** |
| Bearer token in the `Authorization` header | ✅ | ✅ |
| Cover fetched with the auth header, never a token in the URL | ✅ | ✅ **as of P1-14** |
| Ask for the catalogue a page at a time | ✅ | ✅ **as of D1** |

## D1 – D7, and where each one landed

Ranked when they were written; struck through as they shipped.

| # | What | Status | Detail |
| --- | --- | --- | --- |
| **D1** | **Pagination** — `limit` / `page` on `…/items`, reading `total` from the envelope | ✅ **Done** | `limit=100&page=N`, each page handed over as it lands. Three stopping conditions; the page cap is a *failure*, never a quiet end-of-list, so a truncated catalogue can never drive a deletion. |
| **D2** | **Cover art** — `GET /api/items/{id}/cover` | ✅ **Done** (P1-14) | Fetched through the app's authenticated OkHttp client even though the capture shows the endpoint answering `200` anonymously. 128 MB Coil disk cache. |
| **D3** | **Server search** — `GET /api/libraries/{id}/search?q=` | ✅ **Done, books only** (P1-20) | The capture settled two things worth more than the endpoint itself: a hit is the **expanded** item (tracks included, so no follow-up request), and it carries **no `userMediaProgress`** (so a hit cannot rewind a book). The other five result arrays came back `[]` and stay unmapped. |
| **D4** | **Collections** — `GET /api/libraries/{id}/collections` | ⛔ **Still blocked** | The capture returned `results: []`. The envelope is observed; a collection *object* has never been seen. PRODUCT_SPEC 22.4 forbids writing the mapper from the OpenAPI document alone. **Unblocking action: create one collection on the capture server and re-run the capture.** |
| **D5** | **Response TTL cache** — the fork attaches a 12 s TTL to library reads | ➖ **Declined, superseded** | It exists to stop *their* views re-fetching, because their views fetch. Ours read Room, and Room already de-duplicates. The equivalent win here was the unchanged-item skip in P1-31, which is strictly better: it eliminates the request rather than caching its answer. |
| **D6** | **Custom request headers per server** | ➖ **Out of Phase 1** | Real (Authelia, Cloudflare Access), but no requirement or acceptance case asks for it. Raise as a Phase 2+ feature rather than smuggling it in. |
| **D7** | **OpenID sign-in** | ⛔ **Blocked, and a real gap** | `SYNC-001` already persists `authMethods` and we only ever use `local`; an OIDC-only server cannot be signed into at all. Needs a capture against a server configured for it, which the current capture server is not. **Unblocking action: configure OIDC on the capture server, or accept it as a Phase 2 item.** |

### Deliberately declined

- **`minified=1`** — changes the item shape, and no fixture covers it. We already get what we need
  from the unminified list.
- **Server-side `sort` / `desc` / `filter`** — the sort keys are literal server field paths
  (`media.metadata.titleIgnorePrefix`, `progress.finishedAt`, …) and the filter values are
  **base64-encoded** (`filter=progress.aW4tcHJvZ3Jlc3M%3D` is base64 `in-progress`). Adopting them
  would move sorting and filtering off Room, which is the only reason either works offline
  (PRODUCT_SPEC 6.3). Recorded here so the next reader does not have to rediscover the encoding.
- **`/api/libraries/{id}/personalized`** — see ADR-0008. Our shelves are derived from Room on purpose.

---

## The waves

### Wave A — done, three of four

The capture ran on 2026-08-07 against Audiobookshelf 2.36.0. Four new fixtures, two expected drifts.

- **P1-14** cover art (D2) — ✅ shipped
- **P1-20** server search (D3) — ✅ shipped, books only
- **D1** pagination — ✅ shipped, it needed no capture and was done alongside
- Collections axis (D4) — ⛔ **still blocked**, and the only Wave A item that is. The capture server has
  no collections, so `results: []` told us the envelope and nothing about the element. One collection
  created on that server and one re-run closes it.

### Wave B — quality gates, all actionable now

- **P1-26** dependency verification. `gradle.properties:20` is `off` and `verification-metadata.xml`
  has **0** components. Needs one network-complete build to generate checksums, then flip to `strict`.
- **P1-25** coverage. No Kover, no JaCoCo. PRODUCT_SPEC 17.3 wants 80% domain/core, 90% security.
- **P1-24** UI test tier. No `androidTest` source set anywhere. PRODUCT_SPEC 17.1 lists login, profile
  switching, offline home, TalkBack semantics, large-font layouts.
- **P1-29** `values-nb`. Only `values` and `values-night` exist.

### Wave C — needs hardware

- **P1-27** performance: profile switch under 500 ms, cached library interactive under one second,
  a 2,000-item fixture for scroll performance, and TC-17's search cost.
- **P1-28** device matrix: API 26 / 31 / 34 / 36, portrait and landscape, tablet width.

### Wave D — housekeeping

- **P1-30** acceptance-plan maintenance: run TC-04, TC-06, TC-47, TC-52, TC-53, which never have been;
  strike TC-36, whose toggle no longer exists.

### Blocked on the capture server, not on us

- **D4** collections — needs one collection created on the capture server, then a re-run.
- **D7** OpenID — needs the capture server configured for OIDC, or a decision to defer it to Phase 2.

---

## Why the reference clients feel instant, and what we took from it

Measured against both clients' code rather than guessed. Four things, and only two of them are speed:

1. **Neither ever syncs a whole library.** Both page `…/items` at 100 on demand — no client-side mirror
   of the catalogue exists to be built. Ours does mirror it, on purpose: PRODUCT_SPEC 6.3 requires
   browse, filter and sort to work offline, and none of that is possible over a paged remote list.
2. **A home refresh costs one request.** `/personalized` returns every shelf server-side. ADR-0008
   records why we derive shelves from Room instead — same reason as above.
3. **Cached content is painted before the first byte.** We do this too, and it is what P1-31's
   catalogue-first pass and D1's per-page hand-over made real: the shelf now appears after the first
   response instead of after the last.
4. **Nothing is re-fetched that has not changed.** P1-31's `updatedAt` skip. A second refresh over an
   untouched library is now one request per page and no item fetches at all.

The honest summary: **their refresh is fast because it does less, and ours is now fast because it does
the same work in a different order.** The one thing we still pay that they do not is the expansion pass
— and it buys offline playback, which neither of them offers.

### And the home screen that "looks empty"

Worth recording, because the obvious answer was the wrong one: **neither reference client has anything
below its carousels either.** Their home screens are carousels to the bottom of the scroll and nothing
else. What ours was missing was not a section under the shelves — it was *shelves*, and covers.

So: five shelves instead of three (Discover and Listen again fill the two that are structurally empty
on a new account), and cover art on every card, which is most of what makes a row read as content
rather than as a list of strings.

---

## Measured on a device — 0.1.9

The two numbers the whole of P1-31 and D1 were for, reported from a real library:

| | Result |
| --- | --- |
| Time until the first book appeared, clean sign-in | **Before it could be counted** |
| Second refresh, over an unchanged library | **About one second** |

That is two of PRODUCT_SPEC 17.3's thresholds met in the field — "cached library screen interactive
under one second" and, by implication, the refresh cost the N+1 was blocking. It is **not** the whole of
**P1-27**: the 2,000-item scroll fixture and the profile-switch timing are still unmeasured, and both
need a fixture this project does not have yet.

---

## Can Phase 1 be closed? — audited 2026-08-07, against the tree

### The three contractual exit criteria are met

PRODUCT_SPEC's Phase 1 exit criteria are exactly three, and each has both automated and device evidence:

| Criterion | Evidence |
| --- | --- |
| Two accounts on one server can switch | `ProfileSwitcherViewModelTest`, `SwitchProfileUseCaseTest`, `DefaultLibraryRepositoryTest`'s per-profile progress and visibility tests; device cases TC-30…TC-35, G-25 |
| Offline cached browse works | `HomeViewModelTest`'s offline-versus-error cases, the Room-only read path in `DefaultLibraryRepository`; device cases E-25…E-29, G-16, G-21 |
| Unauthorized libraries never appear | Enforced in `AbsLibraryApi.accessible` *before* anything is written, plus the visibility join on every read; `AbsLibraryContractTest`, `DefaultLibraryRepositoryTest`, and the stored-versus-visible counts on the About tab that make it checkable on a device |

### Four cross-cutting requirements are not met

These are not Phase 1 exit criteria. They are requirements the whole repository is subject to, and
Phase 1's code is the code that would be covered by them, so closing the phase with them open is a
decision rather than an oversight.

| # | Requirement | State |
| --- | --- | --- |
| **P1-24** | 17.1 UI test tier — login, profile switching, offline home, TalkBack semantics, large-font and landscape/tablet layouts | **No `androidTest` source set exists anywhere.** `verifyDebug` does not compile one either, so this gap is invisible to the gate. The listed surfaces are all Phase 1 surfaces. |
| **P1-25** | 17.3 coverage — 80% domain/core, 90% for security and deletion policy | **No coverage tooling at all.** Neither number is measured, so neither can be claimed. 35 test classes exist; what fraction they cover is unknown. |
| **P1-26** | 16.1 dependency verification | `gradle.properties` is `off` and `verification-metadata.xml` has **0** components. The policy file and bootstrap script are written; the checksums need one network-complete build. |
| **P1-29** | Localisation — `values-nb` | Only `values` and `values-night` exist. |

### And two API gaps that are not ours to close

**D4** (collections) and **D7** (OpenID) are blocked on the capture server, not on the code. Both are
recorded above with the action that unblocks each.

### The recommendation

The phase's own criteria are met and the product works. The four gaps above are quality-gate debt, and
**P1-24 is the one with real risk in it**: TalkBack semantics and large-font layouts are correctness
properties of screens that have only ever been checked by hand, and hand-checking does not survive the
next change. Closing Phase 1 without it means Phase 2 inherits a UI with no regression net under it.

---

## Done since the original audit

P1-01 … P1-19, P1-21, P1-22, P1-23, P1-31, D1, D2, D3, D5. **P1-12** was found already satisfied by
`SyncAccountUseCase`, which revalidates on every `onVisible()`. **P1-30** is half done: TC-36 is struck,
and TC-04, TC-06, TC-47, TC-52 and TC-53 have still never been run.
