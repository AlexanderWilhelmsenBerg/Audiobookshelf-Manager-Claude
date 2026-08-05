# Handover

Status as verified against the repository on 2026-08-05, not from recollection. Every "done" below is
backed by a file that exists and a check that ran.

## Phase 0 — complete

Merged in PR #1. `./gradlew verifyDebug` green: ktlintCheck, detekt with type resolution, Android
Lint, unit tests (including Robolectric), Room schema export and equality check, `assembleDebug`.

## Phase 1 — **not complete**

`PRODUCT_SPEC 20` lists seven deliverables.

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Server profile | **done at the repository layer** | `:data:auth`, `DefaultAuthRepository.signIn` writes the server and profile rows, stores the token, selects the profile. No screen calls it. |
| Login | **done at the repository layer** | `AbsAuthApi` + `AbsAuthContractTest` against the committed fixtures. No screen calls it. |
| Secure token storage | **done** | `KeystoreTokenCipher`, `SessionTokenStore`, `SessionTokenProvider` in `:data:auth`. |
| Capability handshake | **done, not yet reachable** | `AbsCapabilityResolver`, `DefaultCapabilityRepository`. Reaches `/status` only once the real gateway is bound. |
| Libraries/items sync | **not started** | Contracts are captured; no adapter written. See below. |
| Room-backed home/library/search/details | **done in Phase 0** | Against fixture data, not server data. |
| Profile switch | **not started** | `ProfileRepository.setActiveProfile` exists; nothing calls it, and no switcher UI. |

### Exit criteria: 0 of 3 met

- Two accounts on one server can switch — **no**. The repository can create both profiles
  (`DefaultAuthRepositoryTest` proves two accounts on one server become two profiles sharing one
  server row), but nothing switches between them and no screen exists.
- Offline cached browse works — **not against real data**; works against the fixture.
- Unauthorized libraries never appear — **unproven**. `AuthSession.canAccess` is unit-tested, and the
  accessible-library grant is not yet persisted or applied to a sync.

### The gap that matters, restated

**The running app still cannot sign in.** `AppModule` binds `FakeAudiobookshelfGateway`, whose
`signIn` deliberately returns `AppError.ApiCompatibility` — a sign-in screen that appeared to succeed
against fixture data is the false confidence `PRODUCT_SPEC 22.4` exists to prevent. Two things are
missing before that binding can flip:

1. the library adapter, because the real gateway has to implement `LibraryApi`;
2. a sign-in screen, because with the fixture gateway gone there is no other way to get a profile.

Everything below the UI now exists and is tested.

## What was added in this session

Four commits, each with `verifyDebug` green. 171 unit tests pass; 84 of them are new.

### `:data:auth` (AUTH-001, AUTH-002)

The module `docs/architecture/module-boundaries.md` reserved. `DefaultAuthRepository` does, in this
order: normalize the address, probe it, authenticate, write the server and profile rows in one
transaction, store the token encrypted, select the profile. A rejected sign-in writes nothing.

`SessionIdentity` derives both ids rather than generating them: `ServerId` from the normalized base
URL, `ProfileId` from the server's own user id. Reauthenticating therefore returns to the same
profile and keeps the downloads and progress keyed to it. Hashing also keeps the host and the
username out of the token file name and out of log fields. The fallback when a server sends no user
id is the username, and the consequence — a rename then produces a second profile — is asserted in a
test rather than left implicit.

### Two boundary moves, both deliberate

- **`SessionTokenProvider` moved from `:app` to `:data:auth`.** The previous note said `:app` was the
  only module seeing both `:core:network` (which declares `TokenProvider`) and `:core:datastore`
  (which stores the token). `:data:auth` sees both too, and it also owns the sign-out that has to
  clear the in-memory copy. It is no longer nameable from the UI layer, and `AuthDataModule` binds
  `TokenProvider` instead of `AppModule`. The cached token is now tagged with its profile, so a
  failed switch cannot leave the previous account's credential attached.
- **`@UnauthenticatedClient` added.** The authentication endpoints used the shared authenticated
  client, which attaches the *active* profile's token — possibly for a different server — to a
  `GET /status` or `POST /login` aimed at a host the user just typed. That is a credential leak
  between servers. Auth calls now use a client with no `AuthorizationInterceptor` and pass their
  credential explicitly, and `AuthorizationInterceptor` no longer overwrites an explicit
  `Authorization` header, so a call can name the profile it acts for.

### `TokenCipher` is now an interface

`KeystoreTokenCipher` is the implementation. This is what made the AUTH-003 requirement testable at
all: a real Keystore key is invalidated by a device-level event Robolectric does not reproduce, so
"a lost key requires reauthentication rather than crashing" was previously unverifiable. It is now
covered against a fake in `SessionTokenStoreTest` and `DefaultAuthRepositoryTest`.

**Still unverified, and only verifiable on hardware:** the Keystore configuration itself — GCM, the
non-extractable key, `setUserAuthenticationRequired(false)` — and the real
`KeyPermanentlyInvalidatedException` path.

### Database version 2

`profiles.remoteUserId`, plus `servers.authMethodsJson`, `servers.capabilitiesJson` and
`servers.capabilitiesDetectedAt`. Every statement is additive; no table is recreated. `MigrationTo2Test`
builds a real version-1 database **from the committed exported `1.json`** rather than from a
transcribed `CREATE TABLE`, so it cannot pass against a schema that drifted from the export.

`PRODUCT_SPEC 13` names a conceptual `ServerCapabilityEntity`; the handshake is stored as two JSON
columns instead, which `PRODUCT_SPEC 13` permits ("exact normalization may vary"). Nothing queries a
single capability — a handshake is written and read as one set for one server.

### Session renewal (AUTH-004)

`AuthRepository.renewSession` exchanges the stored refresh token; `RefreshLibraryUseCase` calls it on
a `401` and retries the sync once. No refresh token, a refused refresh, and an unusable renewed
session all mean "sign in again", all mark the profile, and none of them removes it or touches
downloads or local progress. Exactly one renewal per failure and at most one retry — the tests assert
that by counting calls, because "never loops login requests" is a bound, not an outcome.

The renewal replaces **both** tokens: the server issues a new refresh token each time, so keeping the
old one works once and then fails at the following renewal, hours later, looking like a random
sign-out.

### Capability handshake (SYNC-001)

`AbsCapabilityResolver` reads `/status` and confirms **no** capability. That is the finding, not a
stub: `/status` reports `app`, `serverVersion`, `isInit` and `authMethods`, and none of those is a
`ServerCapability`. A version-derived capability map is rejected because a self-hosted server sits
behind reverse proxies that break websockets and filesystems that break range requests — version is
evidence about the software, not the deployment. Every row in `docs/api-compatibility.md`'s capability
table still reads "No", correctly.

`capabilitiesDetectedAt` stays null until a handshake runs, so "we have not asked" is distinguishable
from "the server does not support this" — SYNC-001 requires an explanation, and those are different
explanations.

## The contract fixtures now cover the library shapes

This is the most useful thing this session produced for the next one.

The previous `libraries.json` was `{"libraries": []}`: it proved the envelope key and nothing else.
A fresh container has no media, which is why. `scripts/seed-contract-media.sh` now generates one
eight-second audiobook — silence with metadata and two chapters — using the **server image's own
ffmpeg**, and `scripts/capture-contracts.sh` creates a library, waits for the scan to produce an item,
and records five library shapes. Twelve fixtures are committed and CI re-captures on every
`:core:network` change.

**The finding that decides how LIB-001 has to be built: the item list is minified.** Each result in
`GET /api/libraries/{id}/items` carries `media.numTracks`, `media.numChapters` and
`media.numAudioFiles` as counts, and `media.metadata.authorName` / `seriesName` as *strings*. There is
no `tracks`, `chapters`, `authors` or `series` array. Only
`GET /api/items/{id}?expanded=1&include=progress` has them — plus `media.tracks[].startOffset`, which
is exactly what `PRODUCT_SPEC 11.3`'s global timeline needs, so offsets do not have to be derived by
summing durations.

So a sync that stores *playable* books cannot be one request per library. The list gives the
catalogue; each item needs its own expanded fetch before it can become a `BookSnapshot`.
`PRODUCT_SPEC 2.3` makes that non-optional — a book stored without its track offsets cannot be
resumed. Budget for it: N+1 requests per library, which is what `LIB-001`'s "failed optional sections
do not fail the whole sync" and its partial-render requirement are there to absorb.

Two capture artefacts, so they are not mistaken for server behaviour: `size` and `ino` are scrubbed to
`0` and `<volatile>`, and the fixture library has no series, so `library-series.json` records an empty
`results`.

## Remaining Phase 1 work

In dependency order.

1. ~~Commit the captured contract fixtures.~~ **Done.**
2. ~~Build the Retrofit client.~~ **Done.**
3. ~~Add `auth` to `AudiobookshelfGateway`.~~ **Done.**
4. ~~Secure token storage (AUTH-003).~~ **Done.**
5. ~~Server profile creation and session repository (AUTH-001, AUTH-002).~~ **Done.**
6. ~~Session expiry and refresh (AUTH-004).~~ **Done.**
7. ~~Capability handshake against `GET /status` (SYNC-001).~~ **Done.**
8. **Libraries/items sync (LIB-001).** Contracts are captured, so nothing is blocked. What it needs:

   - DTOs for `libraries`, the minified item list, the expanded item and `authors`. Every field
     nullable or defaulted, as in `AuthDtos` — `ignoreUnknownKeys` protects against added fields, not
     removed ones.
   - `LibraryService` with `GET api/libraries`, `GET api/libraries/{id}/items` and
     `GET api/items/{id}?expanded=1&include=progress`.
   - A mapper to `Library` and `BookSnapshot`. `media.tracks[].startOffset` and `media.chapters` are
     in *seconds* in the fixture; the entities store milliseconds.
   - **A connection seam.** `LibraryApi.listLibraries(profileId)` has to resolve that profile to a base
     URL *and* a credential. `TokenProvider` cannot supply the URL, so an interface in `:core:network`
     implemented in `:data:auth` is the precedent to follow (`DatabaseTransactionRunner` and
     `SessionTokenProvider` are the two existing ones). Pass the credential as an explicit
     `Authorization` header — `AuthorizationInterceptor` already yields to one.
   - **`AuthSession.canAccess` filtering.** The requirement is that an unauthorized library is never
     written to Room at all, not hidden in the UI. That needs the accessible-library grant persisted
     on the profile — it currently lives only in the transient `AuthSession`. That is a database
     version 3 (`profiles.accessibleLibrariesJson`, `profiles.hasAllLibraryAccess`), with a migration
     and a migration test.
   - `AbsAudiobookshelfGateway` assembling `auth`, `capabilities` and `library`, then flipping the
     `AppModule` binding away from the fake.
   - `AccountApi` needs a decision. Its `currentServer()`/`currentProfile()` take no parameters, which
     suited one fixture profile and does not suit a multi-profile client. `POST /api/authorize` is
     captured and is the natural replacement (`currentProfile(profileId)`), which `PRODUCT_SPEC 5.2`
     needs anyway for the permission refresh after a `403`. **Note that `authorize.json` returns
     `user.token` only — no `accessToken`, no `refreshToken`** — so `AuthMapper.toSession` is the wrong
     mapping for it; it needs a permissions-only mapping, or it will store the legacy non-refreshable
     token as the access token.
   - Removing `FixtureLibraryBootstrapper` and the `fixtureLibrarySeeded` flag's use. The fixture
     gateway and `demo-library.json` can stay for tests.

9. **Sign-in UI and profile switch.** Then the exit criteria become testable. Screens needed:
   onboarding (server URL → probe result showing version and connection security → credentials) and a
   profile switcher. `ShelfPlayerNavHost` needs a start-destination decision based on whether any
   profile exists, and `ShelfPlayerApplication` should call `AuthRepository.restoreSession` for the
   active profile on start instead of seeding a fixture.

## Environment notes for the next session

The environment this session ran in came up **without** an Android SDK and with `~/.gradle` empty, so
"caches are warm" was not true. Recovering it took two steps and both are reliable:

```bash
# Android SDK (dl.google.com is reachable)
curl -fsSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p /opt/android-sdk/cmdline-tools && unzip -q clt.zip && mv cmdline-tools /opt/android-sdk/cmdline-tools/latest
export ANDROID_HOME=/opt/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

A cold `verifyDebug` took about four minutes; incremental runs are 10-90 seconds.

**Docker needs starting by hand**: the daemon is installed but not running. `dockerd &` works (the
session runs as root). Then `docker pull ghcr.io/advplyr/audiobookshelf:2.36.0` succeeds.

**`docker exec` and attached `docker run` hang in this sandbox.** `docker run -d` works. Any
container command that needs output has to be run detached and its result read from a bind mount or
`docker logs` — that is why `seed-contract-media.sh` uses `docker run --rm` (which works when the
caller is a script CI runs, but had to be run detached interactively here).

## Phase 2 preparation

`PRODUCT_SPEC 20` Phase 2 is the streaming player: MediaLibraryService, ExoPlayer, global timeline,
progress sync, notification/lockscreen/headset controls, speed/skip, buffer presets, audio focus.

**Phase 2 cannot be completed in this environment, and that is not a scheduling problem.** Its exit
criteria are a two-hour streaming soak, process and activity recreation, media-button resume, and
progress verified against a server. All four need a device or emulator. `verifyDebug` compiles and
unit-tests; it does not launch the app.

What *can* be done here without a device: the `MediaLibraryService` skeleton, the global audiobook
timeline (`PRODUCT_SPEC 11.3` — pure arithmetic over the `startOffset` values the fixtures now
confirm, and the highest-value thing to unit-test because errors there corrupt saved progress),
playback source selection as a policy class, progress persistence with fake transports, and buffer and
speed policy.

**Recommendation unchanged:** do not open Phase 2 until Phase 1's exit criteria pass. Progress sync
depends on a real session and a real library, and Phase 2 built on fixture data would need reworking.

## What has never been verified

Stated plainly because several of these look done from the code.

- **No screen in this app has ever been rendered**, on a device or an emulator. The Compose code
  compiles and the ViewModels are unit-tested; nothing more.
- **No sign-in has ever completed end to end from the app.** The gateway is contract-tested against
  MockWebServer serving captured fixtures, and the repository is tested against a fake gateway. The
  two have never been connected to a real server through the app.
- **`KeyPermanentlyInvalidatedException` handling is covered only against a fake cipher.** Robolectric
  does not reproduce key invalidation.
- **The `servers`/`profiles` migration has never run on a device**, only on Robolectric's SQLite.
- **Every capability in `docs/api-compatibility.md` reads "No"** and that is accurate — the handshake
  confirms none.
- **Websocket, playback, progress, downloads, management and users are entirely unimplemented** and
  their endpoints uncaptured.

## Security note

A live API key and a password for a real Audiobookshelf instance were pasted into the session that
produced the Phase 1 contract work. **They should be rotated.** No credential is in this repository:
the committed fixtures come from throwaway containers with the fixed fake credentials in
`scripts/capture-contracts.sh`, and the workflow fails if anything credential-shaped survives
scrubbing.
