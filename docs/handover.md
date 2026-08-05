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
| Login | **wired at the gateway; not reachable from the UI** | `AuthService`, `AuthDtos`, `AuthMapper`, `AbsAuthApi`, `AudiobookshelfServiceFactory`, `AuthApi` on the gateway, fake updated. No DI binding selects the real gateway, and no screen calls it. |
| Secure token storage | **done** | `TokenCipher` (AES-256/GCM, non-extractable `AndroidKeyStore` key), `SessionTokenStore` (per-profile ciphertext, atomic write), and `SessionTokenProvider` in `:app` bound as `TokenProvider`. OkHttp now sends `Authorization` whenever a token is loaded. |
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

The auth path now exists end to end *inside* `:core:network` — `AudiobookshelfServiceFactory` builds
`AuthService`, `AbsAuthApi` implements the gateway's `AuthApi`, and the fake implements it too, so the
module compiles.

What is still missing is everything outside that module. `AppModule` in `:app` binds
`FakeAudiobookshelfGateway`, so the running app still cannot sign in — by design: the fake's `signIn`
returns `ApiCompatibility` rather than a fabricated session, because a sign-in screen that appeared
to succeed against fixture data is the false confidence `PRODUCT_SPEC 22.4` exists to prevent.
There is no token storage, no session repository, and no sign-in screen.

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

1. ~~Commit the captured contract fixtures.~~ **Done** — eight fixtures under
   `core/network/src/test/resources/contracts/`; the workflow's compare step is now real drift detection.
2. ~~Build the Retrofit client.~~ **Done** — `AudiobookshelfServiceFactory`.
3. ~~Add `auth` to `AudiobookshelfGateway`.~~ **Done** — `AuthApi`, `AbsAuthApi`, fake updated.
4. ~~**Secure token storage (AUTH-003).**~~ **Done.** `SessionTokenProvider` lives in `:app` — the
   only module seeing both `:core:network` (which declares `TokenProvider`) and `:core:datastore`
   (which stores the token), so no core module gained a dependency for a wiring concern. The binding
   moved out of `NetworkModule` into `AppModule`; `NoTokenProvider` remains for any graph without a
   credential store.

   The token is cached in memory because `TokenProvider.currentToken()` is synchronous (an OkHttp
   interceptor is) while `SessionTokenStore.load()` suspends and does a Keystore decryption. Bridging
   those with `runBlocking` inside an interceptor would put a blocking decrypt on every request. The
   consequence is that **sign-out must call `SessionTokenProvider.clear()`** — clearing only the
   stored copy would leave the process authenticating after the user believes it stopped.

   Nothing calls `load`/`adopt` yet; task 5's session repository is what drives them.

   **Decided (2026-08-05, by the project owner): use the Android Keystore API directly. Do not add
   `androidx.security:security-crypto`.** Its only releases are alphas, and an alpha is not an
   acceptable dependency for the component holding user credentials.

   That means writing the crypto wiring by hand, so the shape matters:

   - Generate an `AES/GCM/NoPadding` key in the `AndroidKeyStore` provider, per profile or one
     app-wide key with the profile id as associated data. GCM, not CBC — an authenticated mode means
     a tampered ciphertext fails to decrypt instead of yielding garbage that looks like a token.
   - Store the IV alongside the ciphertext; GCM IVs must never be reused with the same key. Let the
     platform generate the IV rather than supplying one.
   - `setUserAuthenticationRequired(false)`: the app refreshes sessions in the background, and a key
     that needs device unlock would break `AUTH-004` renewal and, later, background downloads.
   - Persist ciphertext in DataStore. Plaintext must never reach DataStore, Room or a log.
   - Handle `KeyPermanentlyInvalidatedException` and `UnrecoverableKeyException`: a key can be
     destroyed by a lock-screen change or a restore to a new device. The correct response is to drop
     the stored session and require reauthentication — never to crash, and never to silently continue
     as signed out without marking the profile.
   - `TokenProvider`/`NoTokenProvider` in `core/network/http/Interceptors.kt` is the seam it plugs
     into. `NoTokenProvider` stays as the fake-gateway binding.

   Tests: Robolectric can exercise the round trip, but Keystore behaviour under key invalidation is
   only observable on a device — so unit-test the storage contract and the invalidation *handling*
   against a fake, and note the device gap rather than claiming it is covered.
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

- `dl.google.com` **was** blocked for most of this work, so Gradle could not resolve locally and
  `verifyDebug` ran only in CI. It is reachable as of 2026-08-05, which means `./gradlew verifyDebug`
  should now work locally — **verify that first**, because it turns a four-minute CI round trip into a
  local run and every remaining Phase 1 item benefits.
  The fallback toolchain that made the CI-only period workable (Kotlin 2.2.0 CLI, JUnit, Turbine,
  coroutines-test, Dagger, Hilt-core, Retrofit, OkHttp, all from Maven Central) compiles and runs the
  JVM-module suites without Gradle, and is still useful for fast single-file checks.
- Container registry blob hosts were blocked too; they are reachable now, so
  `scripts/capture-contracts.sh` can be run against a local container as well as in CI. The committed
  fixtures were captured that way.
- Dependency verification is `off` and no lockfiles are committed; both need one bootstrap run with
  unrestricted repository access (ADR-0006).

## Security note

A live API key and a password for a real Audiobookshelf instance were pasted into the session that
produced this work. **They should be rotated.** No credential was written to any file in this
repository; the committed fixtures come only from a throwaway CI container and are scrubbed, with the
workflow failing if anything credential-shaped survives.
