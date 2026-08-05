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
| Capability handshake | **done** | `AbsCapabilityResolver`, `DefaultCapabilityRepository`. Runs against the bound real gateway; confirms no capability, correctly. |
| Libraries/items sync | **done** | `AbsLibraryApi`, `LibraryMapper`, `AbsLibraryContractTest`. The real gateway is bound; the demo bootstrapper is gone. |
| Room-backed home/library/search/details | **done** | Now reads server data. Never rendered on a device. |
| Profile switch | **policy done, no UI** | `SwitchProfileUseCase` is written and tested. Nothing calls it. |

### Exit criteria: 0 of 3 met, and all three now blocked on the same thing

- Two accounts on one server can switch — **no UI**. `DefaultAuthRepositoryTest` proves two accounts on
  one server become two profiles sharing one server row, and `SwitchProfileUseCaseTest` proves the switch
  behaves. Nothing calls either.
- Offline cached browse works — **untested against real data**. The sync writes server data into Room and
  the UI reads Room, so the pieces are in place, but no one has signed in and pulled the network.
- Unauthorized libraries never appear — **enforced and unit-tested, not demonstrated**. `AbsLibraryApi`
  drops an ungranted library before it can reach Room, and the grant is persisted on the profile
  (database version 3). `AbsLibraryContractTest` covers it against a MockWebServer. What has not happened
  is a real account with a restricted grant signing in.

### The gap that matters, restated

**Everything below the UI is done. There is no UI.**

The real gateway is bound, the demo library is gone, and `ShelfPlayerApplication` restores the active
profile's session on start. On a device that means: a fresh install shows an empty home and has no way to
add a profile, because the only path to one is a screen that does not exist. That is a worse *user*
state than the demo library it replaced, and a better *project* state — nothing in the app now pretends
to have data it did not get from a server.

Step 9 is the whole remaining gap.

## What was added in this session

Six commits, each with `verifyDebug` green. **196 unit tests pass, 0 failures**; 109 of them are new.

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
`servers.capabilitiesDetectedAt`. Every statement is additive; no table is recreated.

### Database version 3

`profiles.accessibleLibrariesJson` and `profiles.hasAllLibraryAccess` — the server's library grant, which
previously lived only in the transient `AuthSession` and so was unavailable to the sync that has to honour
it. The migration grants **existing** rows everything and defaults **new** rows to nothing: a profile
created before grants were recorded already has a library cached and browsable offline, and applying the
restrictive default retroactively would blank content the user is reading.

`MigrationTest` builds each starting version **from its committed exported schema** rather than from a
transcribed `CREATE TABLE`, so it cannot pass against a schema that drifted from the export, and it
migrates every version all the way to the current one — which is what a device two versions behind does.

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
8. ~~**Libraries/items sync (LIB-001).**~~ **Done.** `AbsLibraryApi` filters by the persisted grant
   before anything reaches Room, fetches the catalogue and then one expanded item each, and fails a
   library's sync on any per-item error that is not a `404`. `AccountApi` was removed rather than
   reimplemented — its parameterless shape cannot serve a multi-profile client — and the note about
   `authorize.json` returning `user.token` only now lives on the gateway interface, where the next person
   to add a permission refresh will read it.

9. **Sign-in UI and profile switch.** The only remaining Phase 1 work, and the whole gap.

   Ready for it:

   - `SignInUseCase` — probe-free entry point taking URL, username and password; runs the handshake and
     the first sync; returns the profile plus an optional warning. Tested.
   - `SwitchProfileUseCase` — selection then credential, in that order, for the reason recorded on it.
     Tested.
   - `AuthRepository.probeServer` returns a `ServerCandidate` carrying the normalized URL, the detected
     version, whether HTTPS was assumed and whether the connection is cleartext — which is exactly the
     four things PRODUCT_SPEC 6.1 steps 3-4 want on screen before the password field.
   - `SessionRestorer.restoreActiveSession()` returns `null` when no profile is selected, which is the
     signal for "show onboarding rather than an empty library".

   What step 9 has to build:

   - `feature/onboarding`: a server-address screen (submit → `probeServer`, show version and a cleartext
     warning), then a credentials screen (submit → `SignInUseCase`). PRODUCT_SPEC AUTH-001 wants the
     certificate error distinguishable from a wrong password — `AppError.Security` versus
     `AppError.Authentication`, both already produced by `NetworkErrorMapper`.
   - `feature/profiles`: a switcher listing `ProfileRepository.observeProfiles()` with server name,
     username and role (AUTH-002 wants all three), calling `SwitchProfileUseCase`, plus sign-out and
     remove-profile actions calling `AuthRepository`. Removing a profile is destructive and
     PRODUCT_SPEC 21 requires its wording reviewed: it deletes that profile's progress and downloads and
     nothing else, and the confirmation should say so.
   - `ShelfPlayerNavHost`: a start-destination decision. There is no profile on first launch, so the
     graph cannot start at `home`. The decision needs to be made from state, not from a one-shot check,
     because removing the last profile has to return the user to onboarding.
   - A `requiresReauthentication` banner. AUTH-004's "pauses new network actions and marks the profile"
     is enforced in the data layer already; the profile carries the flag and nothing displays it.
   - ViewModel tests for each, and `PRODUCT_SPEC 21`'s full state list per screen: error, loading, empty,
     offline and permission.

   Only after that do the exit criteria become demonstrable, and demonstrating them needs a device or an
   emulator — neither exists in this environment. Building an APK and having a human sign in to a real
   server is the honest way to close them.

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

**Two captures against one server should be byte-identical.** That is what CI's drift check asserts, and
the first version of the library fixtures failed it: `lastScan` and the file id inside `contentUrl` vary
per capture and were not scrubbed. Both are now in the scrubber. Before committing a re-captured fixture,
capture twice against the same container and `diff -ru` the two output directories — a false drift report
is worse than none, because it trains a reader to ignore the check. Note that a *second* capture against
an already-initialized server legitimately differs in `init.json`, `status-uninitialized.json` and
`userDefaultLibraryId`: those come from the fresh-server sequence and CI always starts a new container.

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
- **No library has ever been synced from a server by the app.** `AbsLibraryContractTest` drives the real
  adapter against MockWebServer serving the captured fixtures, and `DefaultLibraryRepositoryTest` drives
  the repository against the fake gateway. The two have never met a live server through the app.
- **The grant filter has never been exercised by a genuinely restricted account.** It is enforced at the
  gateway and covered by tests that fabricate the grant.
- **Websocket, playback, progress, downloads, management and users are entirely unimplemented** and
  their endpoints uncaptured.

## Security note

A live API key and a password for a real Audiobookshelf instance were pasted into the session that
produced the Phase 1 contract work. **They should be rotated.** No credential is in this repository:
the committed fixtures come from throwaway containers with the fixed fake credentials in
`scripts/capture-contracts.sh`, and the workflow fails if anything credential-shaped survives
scrubbing.
