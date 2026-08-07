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
| Cover fetched with the auth header, never a token in the URL | ✅ | ⛔ P1-14 |

## Differences still worth taking

Ranked. Every one needs a capture unless marked otherwise.

| # | What | Why it is worth it | Capture needed? |
| --- | --- | --- | --- |
| **D1** | **Pagination** — `limit` / `page` on `…/items`, reading `total` from the envelope | A 5,000-item library is currently one enormous response. Both clients page; the fork reads `result.total` to drive it. | **No** — `total`, `page`, `limit`, `offset` are already in the committed `library-items.json` |
| **D2** | **Cover art** — `GET /api/items/{id}/cover`, auth header, Coil with the app's OkHttp client | The one visible hole in the browse surface | **Yes** — the seed is fixed, the capture has never returned 200 |
| **D3** | **Server search** — `GET /api/libraries/{id}/search?q=` | LIB-002's "server search may enrich results" | **Yes** |
| **D4** | **Collections** — `GET /api/libraries/{id}/collections` | The missing browse axis | **Yes** |
| **D5** | **Response TTL cache** — the fork attaches a short TTL (12 s) to library reads | Cheap protection against a screen that re-queries on every recomposition. We have Room in front of everything, so the win is smaller here than for them. | No — client-side |
| **D6** | **Custom request headers per server** — both clients support them | Reverse proxies with auth headers (Authelia, Cloudflare Access) are common in self-hosting. Neither PRODUCT_SPEC nor any acceptance case asks for it; worth raising as a Phase 2+ feature rather than smuggling into Phase 1. | No |
| **D7** | **OpenID sign-in** — the fork supports it | `SYNC-001` persists `authMethods` and we only ever use `local`. A server configured for OIDC-only cannot be signed into at all. | **Yes** |

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

### Wave A — one capture run unblocks four things

Everything here is blocked on the same action: **run the contract-capture workflow**. The seed now
places a `cover.jpg`, and `/search`, `/collections` and `/personalized` are already capture targets.

- **P1-14** cover art (D2)
- **P1-20** server search (D3)
- Collections axis (D4)
- Expect legitimate drift in `library-item.json` as `coverPath` fills in — that is the seed fix working

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
- **D1** pagination — not blocked, and the right time is alongside a 2,000-item fixture in Wave C,
  because that is what makes the difference measurable.

---

## Done since the original audit

P1-01 … P1-13, P1-15 … P1-19, P1-21, P1-22, P1-23, P1-31. **P1-12** was found already satisfied by
`SyncAccountUseCase`, which revalidates on every `onVisible()`.
