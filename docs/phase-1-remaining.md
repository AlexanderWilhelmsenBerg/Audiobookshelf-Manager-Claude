# What is left to complete Phase 1

A gap audit of Phase 0 and Phase 1 against `PRODUCT_SPEC.md`, run after the websocket requirement
(`LIB-001`, last acceptance criterion) was found to have been missed by the phase plan entirely.

The point of this document is that the plan in `docs/handover.md` tracked *deliverables* ("libraries/items
sync", "sign-in UI") and marked them done when the deliverable existed. `PRODUCT_SPEC 21` says a
requirement is complete only when its **acceptance criteria** are met. Every gap below is a criterion of a
Phase 0 or Phase 1 requirement that no code satisfies.

Sources read: `PRODUCT_SPEC.md` sections 3, 5, 6, 7 (AUTH/LIB/SYNC/SET), 13, 14, 15, 17, 18, 19, 20, 21,
25; `docs/handover.md`; `docs/api-compatibility.md`; ADRs 0001–0007; the acceptance results filled in
during the sixth device run.

Verified against the tree at `572979d`.

---

## 1. Phase 0 residue

Phase 0's three exit criteria are met: `verifyDebug` passes, the app opens a fake library, no real
credentials are needed. But six Phase 0 deliverables shipped as declarations rather than working
foundations, and each one is now blocking a Phase 1 criterion.

| # | Finding | Evidence | Resolves as |
| --- | --- | --- | --- |
| P0-a | No image-loading pipeline exists. | `coil-compose` is declared in `app/build.gradle.kts:44` and has **zero** usages in the tree. | P1-14 |
| P0-b | No connectivity awareness. | `ACCESS_NETWORK_STATE` is declared in the manifest; `ConnectivityManager` and `NetworkCallback` appear nowhere. The permission is requested and unused. | P1-04 |
| P0-c | The network security config does not differ by build type. | `PRODUCT_SPEC 15` requires it to. One `base-config cleartextTrafficPermitted="false"` covers debug and release alike, so a LAN-only `http://` server cannot be reached by any build — while the sign-in screen politely *warns* about cleartext for an address the HTTP stack will then refuse outright. | P1-23 |
| P0-d | There is no UI test tier. | No `androidTest` source set exists in any module. `PRODUCT_SPEC 17.1` lists login, profile switching, offline home, TalkBack semantics and large-font layouts; `PRODUCT_SPEC 21` requires accessibility semantics and large text to be checked per requirement. | P1-24 |
| P0-e | Coverage is unmeasured. | No Kover, no JaCoCo. `PRODUCT_SPEC 17.3` sets 80% for domain/core and 90% for security policies. Neither number can currently be stated. | P1-25 |
| P0-f | Dependency verification is **off**. | `gradle.properties:20` — `org.gradle.dependency.verification=off`, and `gradle/verification-metadata.xml` carries the policy but no generated `<components>` checksums. `PRODUCT_SPEC 18` lists it as PR check #2 and `PRODUCT_SPEC 25` as a release gate. The bootstrap script exists and has never been run. | P1-26 |

---

## 2. Phase 1 gaps by requirement

### AUTH-001 Add server profile — met, with one open item

Normalization, HTTPS proposal, discarded password, TLS rejection and the absent trust-all mode are all
implemented and tested. The per-server cleartext exception `PRODUCT_SPEC 15` allows has no screen
(open decision 24.13) → **P1-23**.

### AUTH-002 Multiple profiles — met, two criteria unproven

Stable local IDs, per-profile namespacing of tokens/permissions/progress, isolated removal, the switcher
with server address/username/role, and persistence across restart are all implemented.

- "Switching profile takes no more than 500 ms for locally cached screens" has never been measured → **P1-27**.
- "Settings overrides … namespaced by profile" has nothing to namespace: only a single global
  `AppSettings` exists. This is what blocks LIB-002's per-profile sort persistence → **P1-17**.

### AUTH-003 Secure token storage — met

Keystore AES/GCM, no tokens in logs or URLs, no persisted passwords, `allowBackup="false"` (which
satisfies the backup criterion by not backing up). Biometric locking is explicitly optional and absent.

### AUTH-004 Session expiry — met, with a hole

401 → mark, no login loop, refresh via `x-refresh-token`, re-auth without data loss: all implemented.

- Nothing re-validates a session while the app is open. An account disabled or password-changed
  server-side is only noticed on the next network call the user happens to trigger (acceptance case
  TC-45) → **P1-12**.

### LIB-001 Initial synchronization — **incomplete**

Room-as-truth, partial cached content, per-section failure tolerance, non-blocking sync status and
pull-to-refresh are all done and were confirmed on device.

- **Covers are never fetched.** The sync stores `coverPath` and no build has ever rendered an image.
  "Initial sync stores … covers" is unmet, and so is offline cover availability → **P1-14**.
- **"Websocket events update Room; REST refresh is used when websocket is unavailable" is unbuilt.**
  This is the criterion the plan lost → **P1-05 … P1-09** (step 10).

### LIB-002 Browse and search — **incomplete**

300 ms debounce, immediate local results, and distinct empty/loading/error states are done.

- **Browse axes.** Only "all accessible books" and "by library" exist. Recently added, continue
  listening, downloaded, author, genre and collection are absent → **P1-16**. Series is absent too and
  is large enough to be its own task → **P1-15**.
- **ISBN and ASIN** are matched by no predicate because the sync stores neither → **P1-19**.
- **Server search enrichment** ("server search may enrich results") — no search endpoint is called → **P1-20**.
- **Filters do not exist**, and sort order lives in `HomeViewModel`'s in-memory `ShelfControls`, so it
  does not persist per profile and library → **P1-17**.
- **Offline is not a distinct state.** With no connectivity signal, an offline refresh renders the same
  error surface as a server fault → **P1-04**.

### LIB-003 Series ordering — **incomplete**

Numeric and decimal sequences sort numerically, non-numeric values sort after and stay stable, and the
detail screen shows every membership. All tested.

- There is **no series browse or grouping** anywhere: "sort by series" produces a flat title-ordered
  list, which is acceptance case TC-16 → **P1-15**.

### LIB-004 Book details — **incomplete**

Title, subtitle, authors, narrators, series and sequence, duration, track count, progress and a
markup-stripped description are shown. Local availability is shown as a chip.

- Missing from the screen although present in Room: **cover** (P1-14), **genres**, **tags**,
  **publisher and published year**, **language**, **download size**. Remote availability is not shown
  independently of local → **P1-18**.
- HTML is stripped rather than allow-listed. Documented as interim in `BookScreen.kt`; the real
  sanitizer is scheduled with the metadata editor in Phase 5. Acceptable as recorded.

### SYNC-001 Capability handshake — **incomplete**

Version and auth methods are persisted; the supported set is deliberately empty, which is the correct
reading of "unknown is unsupported" and is documented at length in `AbsCapabilityResolver`.

- **"The compatibility result is visible in diagnostics" is unmet.** Settings shows storage counts and
  nothing about the server → **P1-22**.
- Websocket availability is one of the fourteen things the handshake must persist and is the one
  step 10 actually needs → **P1-06**.

### SYNC-002 Websocket resilience — **entirely absent**

No transport, no reconnect policy, no dedup, no diagnosability. → **P1-05 … P1-09**.

### SYNC-003 Sync scheduling — **largely absent**

- **WorkManager is never used.** It is in the version catalog with zero usages. No persistent background
  refresh, no per-profile unique work names, no cancellation on profile removal → **P1-13**.
- **Foreground refresh is not cancellable**, and auto-refresh only fires when the profile's status is
  `NeverSynced` or `Syncing` — so a returning user with a successful past sync never refreshes without
  asking (TC-08b, TC-10) → **P1-03**, **P1-12**.

### Section 5.2 Permission model — **the largest missed gap**

- **Permissions are never refreshed.** `AudiobookshelfGateway` has no current-user operation;
  `accessibleLibrariesJson`, `hasAllLibraryAccess` and `hasAllTagAccess` are written once at sign-in and
  never updated. A grant changed on the server is invisible until the user signs out and back in. This
  is the root cause of acceptance case TC-37, where a library revoked from account B stayed on screen →
  **P1-02**.
- **"A 403 invalidates the permission cache and refreshes the current user"** cannot be satisfied
  without the above. `NetworkErrorMapper` documents the intent and nothing acts on it → **P1-02**.
- The five-minute pre-destructive permission reload has no destructive requests in Phase 1. Not applicable yet.

### Section 13 Data model / 13.2 Freshness

- **No per-profile item visibility.** Nothing records which items a given profile's own sync returned.
  `LibraryAccess.allows()` filters by library only, so an account restricted by *tag* inside a shared
  library sees every book another account cached. Acceptance cases TC-09, TC-27, TC-34, TC-37c and
  TC-43 are all this one defect → **P1-01**.
- **`EventDedupEntity` is absent.** SYNC-002 requires duplicate events to be idempotent → **P1-08**.
- **No `syncVersion`/ETag column** on any entity, although `PRODUCT_SPEC 13.2` lists it. Deferred with
  reason: `ChecksumOrETag` is an unprobed capability, and adding a column for a header no captured
  response carries would be the guess `PRODUCT_SPEC 22.4` forbids. Revisit with P1-06.

### Section 14.3 Retry policy — **absent**

`AppError` classifies retryability and carries `retryAfterSeconds`; **nothing retries**. There is no
backoff, no jitter, and no code path that honours `Retry-After`. A 490-item library sync is 491 requests
with zero resilience, which is why a single transient failure showed up on device as a partially
synced library → **P1-10**.

### Section 6.1 First launch — one step missing

Step 9, "user selects a default library or accepts the server default", has no equivalent: there is no
default-library concept → **P1-21**.

### Section 6.5 User switching — the Phase 1 half is missing

Steps 5's "libraries, permissions … update" does not happen on switch → **P1-03**, **P1-02**. The
playback steps belong to Phase 2.

### Sections 17, 18, 25 Quality gates

- No UI tests (P0-d) → **P1-24**; no coverage (P0-e) → **P1-25**; dependency verification off (P0-f) → **P1-26**.
- `PRODUCT_SPEC 17.3` performance targets — cached library interactive under one second, and acceptable
  scrolling on a 2,000-item fixture — are unmeasured. Acceptance case TC-17 reports search still feels
  slow at 490 books with the debounce in place → **P1-27**.
- `PRODUCT_SPEC 17.2` device matrix: only one device, one API level, portrait only → **P1-28**.
- `PRODUCT_SPEC 3.1` asks for a Norwegian-ready localization structure. All 106 strings are externalized,
  so the structure is there; there is no `values-nb` → **P1-29**.
- Main-branch checks in `PRODUCT_SPEC 18` that do not exist: managed-device tests, integration server
  tests, SBOM, vulnerability scan, changelog from labels. These are release-pipeline items and belong to
  Phase 6; recorded here so they are not lost a second time.

---

## 3. The three Phase 1 exit criteria

| Exit criterion | State | Blocked by |
| --- | --- | --- |
| Two accounts on one server can switch | Switching works; the new profile's view does not refresh | P1-03 |
| Offline cached browse works | Cached browse works; offline is indistinguishable from a server fault, and covers are absent | P1-04, P1-14 |
| Unauthorized libraries never appear | **Fails.** Item-level restriction is not enforced, and revocation is never learned | P1-01, P1-02 |

---

## 4. The task list, in dependency order

> **Wave 1 is done** (commit history on this branch): P1-01, P1-02, P1-03 and P1-05 below are
> implemented, with `verifyDebug` green and 358 unit tests passing. P1-05 ships the *harness*; the
> fixtures it produces need a run against a real server before anything may be mapped from them.

### Blocking the exit criteria

- **P1-01 — Per-profile item visibility.** ✅ Done. Record which items each profile's own sync returned; filter
  every read through it; default-deny for a profile that has not synced. Room migration 5. Closes
  TC-09, TC-27, TC-34, TC-37c, TC-43 and exit criterion 3.
- **P1-02 — Permission refresh.** ✅ Done. Add a current-user operation to the gateway over the already-captured
  `POST /api/authorize`; refresh the stored grant on sign-in, on profile switch, on app resume and after
  any 403. Closes TC-37 and `PRODUCT_SPEC 5.2`.
- **P1-03 — Refresh on profile switch.** ✅ Done. Closes TC-08b and exit criterion 1.
- **P1-04 — Connectivity observation and a distinct offline state.** ✅ Done. `ConnectivityManager` behind a
  domain-level seam; offline rendered as its own state; refresh on regained connectivity. Closes exit
  criterion 2's second half and LIB-002's state criterion.

### Step 10 — websocket (LIB-001, SYNC-002)

- **P1-05 — Capture the contract.** ✅ **Done and committed.** Six fixtures from Audiobookshelf 2.36.0,
  pinned by `RealtimeContractTest`. See `docs/api-compatibility.md` for the sequence. Three things it
  settled, one of which changes the tasks below:
  - The handshake offers `upgrades: ["websocket"]` with a 25 s ping interval and a 20 s timeout.
  - `42["auth","<token>"]` was a guess and the server accepted it, answering `user_online` then `init`.
  - **`user_updated` carries the entire user object**, not a progress delta — `mediaProgress`,
    `permissions`, `librariesAccessible`, `itemTagsSelected`, `isActive`, `isLocked`. So TC-10, TC-37
    and TC-45 are served by one handler rather than three, and the permission refresh built in P1-02
    becomes event-driven rather than only firing on a profile switch.

  Still unobserved, and therefore still unassumable: every other event name (item changes, library
  scans, session events), the behaviour of the real websocket upgrade as opposed to polling, and what
  the server does with an invalid token in the `auth` frame.
- **P1-06 — Probe websocket availability** into the capability set (SYNC-001), from the handshake's
  `upgrades` rather than from the server version — it is a property of the deployment, and a reverse
  proxy that strips the upgrade will not list it.
- **P1-07 — `RealtimeConnection`:** the engine.io sequence (handshake → `40` → `42["auth", token]`),
  bounded exponential backoff with jitter, reconnect on lifecycle and network change, foreground-scoped,
  the 25 s / 20 s heartbeat the server states, the token inside the frame and never in a query string or
  a log, reverse-proxy failures diagnosable (SYNC-002).
- **P1-08 — Apply `user_updated` to Room** through the existing repositories, with an `EventDedupEntity`
  for idempotency. One handler, three outcomes, because the frame carries all three: progress (TC-10),
  the grant (TC-37 — reuses P1-02's write path), and account state (TC-45 — reuses the reauthentication
  mark). Bound to the profile whose socket received it: the frame carries no server identity, so the
  binding comes from the connection, never from the payload.
- **P1-09 — REST fallback:** a progress-only sync reading `user.mediaProgress` from the cold-start
  `POST /api/authorize` the app already performs, so TC-10 is fixed even where a proxy breaks the socket
  entirely. The element shape is now captured, so this is unblocked and is the cheaper half of step 10 —
  worth building first, since it works everywhere the socket does not.

### Connectivity and sync completeness

- **P1-10 — Retry executor.** Three retries for transient GET failures, exponential backoff with jitter,
  `Retry-After` honoured, no blind retry on auth (`PRODUCT_SPEC 14.3`).
- **P1-11 — Reachability indicator.** Green/red on the shelf top bar and on each known-server row at
  sign-in (TC-05b).
- **P1-12 — Foreground refresh policy.** Staleness-driven refresh on resume, a cancellable in-flight
  refresh, a single-flight guard so pull-to-refresh, reconnect, profile switch and socket events cannot
  stampede the same N+1 sweep, and a session probe on resume (TC-45).
- **P1-13 — WorkManager background refresh.** Uniquely named per profile, cancelled on profile removal,
  never waking the device for cover art (SYNC-003).

### Browsing completeness

- **P1-14 — Cover art.** Wire Coil, authenticate image requests without putting a token in a URL, cache
  for offline (LIB-001, LIB-004).
- **P1-15 — Series browse.** Group by series, open a series into its ordered books (LIB-003, TC-16).
- **P1-16 — Remaining browse axes:** recently added, continue listening, downloaded, author, genre;
  collections only if the capability probe confirms them (LIB-002).
- **P1-17 — Per-profile settings layer,** then filter and sort persistence per profile and library
  (LIB-002, AUTH-002, SET-001).
- **P1-18 — Book detail completeness:** genres, tags, publisher, year, language, download size, and
  remote availability shown independently of local (LIB-004).
- **P1-19 — Sync and match ISBN/ASIN** (LIB-002).
- **P1-20 — Server-side search enrichment** (LIB-002). Needs a contract capture.
- **P1-21 — Default library selection** (`PRODUCT_SPEC 6.1` step 9).

### Diagnostics and settings

- **P1-22 — Server compatibility section in diagnostics:** version, auth mode, each capability and
  whether it was confirmed (SYNC-001).
- **P1-23 — Per-server cleartext exception** in an advanced screen with a warning, and a debug/release
  split of the network security config (`PRODUCT_SPEC 15`, open decision 24.13).

### Quality gates

- **P1-24 — UI test tier:** login, profile switching, offline home, TalkBack semantics, large font,
  landscape and tablet width (`PRODUCT_SPEC 17.1`, `21`).
- **P1-25 — Coverage measurement** and the 80% / 90% thresholds (`PRODUCT_SPEC 17.3`).
- **P1-26 — Turn dependency verification on:** run the bootstrap script, commit the checksums, flip
  `gradle.properties` to `strict` (`PRODUCT_SPEC 18`, `25`).
- **P1-27 — Performance:** profile switch under 500 ms, cached library interactive under one second,
  a 2,000-item fixture for scroll performance, and the search cost behind TC-17.
- **P1-28 — Device matrix:** API 26 / 31 / 34 / 36, portrait and landscape, tablet width.
- **P1-29 — `values-nb` scaffold** (`PRODUCT_SPEC 3.1`).
- **P1-30 — Acceptance plan maintenance:** run TC-04, TC-06, TC-47, TC-52 and TC-53, which were never
  run; strike TC-36, whose toggle no longer exists.

---

## 5. Deferred, with the reason recorded

- **`syncVersion` / ETag columns** (`PRODUCT_SPEC 13.2`) — no captured response carries the header.
  Revisit with P1-06.
- **A real HTML sanitizer** (LIB-004) — stripping is safe today; the allow-listing renderer arrives with
  Phase 5's metadata editor, where untrusted provider content also lands.
- **Biometric profile lock** (AUTH-003) and **avatar/colour** (AUTH-002) — both marked optional by the spec.
- **Release-pipeline checks** (`PRODUCT_SPEC 18` main branch) — SBOM, vulnerability scan, managed-device
  and integration-server tests, changelog from labels. Phase 6.
- **Collections** — `PRODUCT_SPEC 3.2` makes them conditional on consistent server support, so they wait
  for a capability probe.
</content>
</invoke>
