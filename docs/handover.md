# Handover

Status as verified against the repository on 2026-08-05, not from recollection. Every "done" below
is backed by a file that exists and a check that ran.

## Phase 0 — complete

Merged in PR #1. `./gradlew verifyDebug` green: ktlintCheck, detekt with type resolution, Android
Lint, unit tests (including Robolectric), Room schema export and equality check, `assembleDebug`.

## Phase 1 — **not complete**

`PRODUCT_SPEC 20` lists seven deliverables. One is partially done.

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Server profile | **not started** | No sign-in-driven profile creation exists. `ProfileRepository.setActiveProfile` is Phase 0 fixture plumbing. |
| Login | **contract layer only** | `AuthService`, `AuthDtos`, `AuthMapper`, `AuthMapperTest` (9 tests). Referenced by nothing but its own test. |
| Secure token storage | **not started** | No `EncryptedSharedPreferences`, `MasterKey` or Keystore use anywhere. |
| Capability handshake | **not started** | `CapabilityResolver` is declared and implemented only by the fake. |
| Libraries/items sync | **not started** | No Retrofit service for libraries; sync still reads the fixture. |
| Room-backed home/library/search/details | **done in Phase 0** | Against fixture data, not server data. |
| Profile switch | **not started** | Requires two real sessions, which requires login to be wired. |

### Exit criteria: 0 of 3 met

- Two accounts on one server can switch — **no**.
- Offline cached browse works — **not against real data**; works against the fixture.
- Unauthorized libraries never appear — **unproven**. The rule is encoded in `AuthSession.canAccess`
  and unit-tested, but nothing enforces it end to end because nothing consumes a real session.

### The gap that matters

`AuthService` is an **orphan**. There is no Retrofit instance in the project that builds it, no
`auth` property on `AudiobookshelfGateway`, and no DI binding. The contract is verified and encoded;
it is not connected to anything. Do not read the passing tests as a working login.

## What *is* verified, and how

Contracts were observed on a real Audiobookshelf **2.36.0** instance on 2026-08-05, then encoded.
`docs/api-compatibility.md` records them; ADR-0007 records why they come from a server rather than
the published specification, which documents **no authentication endpoint at all**.

Three findings that a documentation-derived client would have got wrong:

1. Tokens are nested under `user`, not at the top level.
2. `user.refreshToken` is `null` unless the request carries `x-return-tokens: true`; otherwise the
   server sets it as an `HttpOnly` cookie a native client cannot read. Verified both ways. A session
   without it cannot be renewed, which is why `AuthSession.isRenewable` exists.
3. `user.token` (pre-2.26) is returned *beside* `accessToken`. `/auth/refresh` does not accept the
   legacy value, so preferring it yields a session that works until expiry and then cannot renew —
   a failure that surfaces hours later on a real device.

An API-key token was confirmed to work as `Authorization: Bearer` on `/api/libraries`, which settles
the `PRODUCT_SPEC 23` open question: API-key auth is viable alongside interactive login, at least for
library reads.

## Remaining Phase 1 work

In dependency order. Each is a vertical slice with tests.

1. **Commit the captured contract fixtures.** `.github/workflows/contract-capture.yml` produces them
   as the `captured-contracts` artifact; `core/network/src/test/resources/contracts/` is still empty,
   so the workflow's compare step is inert. Until they land there is no drift detection.
2. **Build the Retrofit client.** Base URL from `ServerUrlNormalizer`, `AuthInterceptor` adding
   `Authorization: Bearer`, JSON with `ignoreUnknownKeys`. Nothing constructs a Retrofit instance today.
3. **Add `auth` to `AudiobookshelfGateway`**, implement it for real and in `FakeAudiobookshelfGateway`
   — the fake must be updated in the same change or `:core:network` will not compile.
4. **Secure token storage (AUTH-003).** Keystore-backed. Tokens must never reach DataStore or Room in
   plaintext, and never a log line.
5. **Server profile creation and session repository (AUTH-001, AUTH-002).**
6. **Session expiry and refresh (AUTH-004).** `/auth/refresh` with `x-refresh-token`; a non-renewable
   session must mark the profile as requiring reauthentication rather than silently signing out.
7. **Capability handshake against `GET /status`** (SYNC-001) — `serverVersion` and `authMethods` are
   already confirmed present.
8. **Libraries/items sync (LIB-001)** replacing the fixture bootstrapper, filtered by
   `AuthSession.canAccess` so an unauthorized library cannot be written to Room at all.
9. **Sign-in UI and profile switch**, then the exit criteria become testable.

## Phase 2 preparation

`PRODUCT_SPEC 20` Phase 2 is the streaming player: MediaLibraryService, ExoPlayer, global timeline,
progress sync, notification/lockscreen/headset controls, speed/skip, buffer presets, audio focus.

**Phase 2 cannot be completed in the current environment, and this is not a scheduling problem.**
Its exit criteria are a two-hour streaming soak, process and activity recreation, media-button
resume, and progress verified against a server. All four require a device or emulator. None exists
here, and `verifyDebug` does not launch the app — it compiles and unit-tests it.

What *can* be done here without a device:

- `MediaLibraryService` skeleton and media session wiring, compiled and unit-tested.
- The global audiobook timeline (`PRODUCT_SPEC 11.3`) — pure arithmetic over track offsets, and the
  highest-value thing to unit-test, since off-by-one errors here corrupt saved progress.
- Playback source selection policy (`11.4`) as a testable policy class.
- Progress persistence and the offline outbox, with fake transports.
- Buffer presets and speed/skip as policy, separate from the player.

What must be done on hardware, by someone with a device:

- The soak, process recreation, media-button resume, audio-focus and route changes.
- Anything involving `AudioManager`, `MediaSession` callbacks from real Bluetooth or headset events.

**Recommendation:** do not open Phase 2 until Phase 1's exit criteria pass, because progress sync
depends on a real session and a real library. Phase 2 built on fixture data would need reworking
once login lands, and `PRODUCT_SPEC 22.2` asks for one vertical slice at a time.

## Environment constraints that shaped the work

- `dl.google.com` is blocked, so Gradle cannot resolve locally and `verifyDebug` runs only in CI.
  A local toolchain (Kotlin 2.2.0 CLI, JUnit, Turbine, coroutines-test, Dagger, Hilt-core, all from
  Maven Central) compiles and runs the JVM-module suites before pushing.
- Container registry blob hosts are blocked, so contract capture runs in CI rather than locally.
- Dependency verification is `off` and no lockfiles are committed; both need one bootstrap run with
  unrestricted repository access (ADR-0006).

## Security note

A live API key and a password for a real Audiobookshelf instance were pasted into the session that
produced this work. **They should be rotated.** No credential was written to any file in this
repository; the committed fixtures come only from a throwaway CI container and are scrubbed, with the
workflow failing if anything credential-shaped survives.
