# Risk register

**As of:** 2026-08-20, opening Phase 6.

A companion to `docs/gaps.md`, and not the same document. A **gap** is a requirement the build does not
meet. A **risk** is something the build *does* — or does not know about itself — that could cost a user
their data, their playback, or this project its release.

Every entry below was verified against the tree on the date above, not inferred from a phase heading. Where
a claim is "never run", that means no artifact in this repository shows it running.

Each row carries the three things that make a risk actionable: what goes wrong, how bad it is if it does,
and the cheapest thing that would retire it. Ordered by blast radius within each group.

---

## 1. Release blockers

These stop a public build. None is a bug; each is a decision nobody has made.

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-01 | **`applicationId` is `com.example.shelfplayer`.** Play rejects the `com.example.` prefix outright. | No release is possible. Changing it after anybody installs produces a *second, empty* app — a fresh sign-in and every download lost. | Changing it in the same commit as the first release, before there is an install to lose. ADR-0019 holds the reasoning; the decision is still open. |
| R-02 | **No licence.** `LICENSE` says "not yet chosen", so all rights are reserved and nothing may be distributed. | Distribution is unlawful, not merely awkward. Also blocks the SBOM in PRODUCT_SPEC 18. | One ADR. It interacts with R-03: the GPL posture in ADR-0012 constrains which licences are honest here. |
| R-03 | **No distribution channel chosen** (Play, F-Droid, GitHub Releases, private). | Decides the signing story, the version-code rule and whether an app bundle or an APK ships — so several smaller decisions are blocked behind it. | An ADR. F-Droid additionally requires a reproducible build, which is a constraint on R-04 rather than a consequence of it. |
| R-04 | **`versionName` is `0.9.6-auto-shelves` at `versionCode` 35.** The name has not moved in nine builds. | Every field report is untraceable. The debug console prints this string, so a user pasting diagnostics reports a version that does not identify their APK — which is the one job that field has. | A version rule tied to something real. Cheap and worth doing before the next APK, independent of R-03. |
| R-05 | **No signing configuration, deliberately** (PRODUCT_SPEC 15). Release builds from CI are unsigned. | Correct today. It becomes a risk the moment somebody needs a signed build in a hurry and puts a keystore where the repository can see it. | Key material in a protected environment with its own workflow, never in a push-triggered one. Write it down before it is needed. |
| R-06 | **No minimum Audiobookshelf server version is declared or enforced.** `docs/api-compatibility.md` pins 2.36.0 as the version *read*, not as a floor. | A user on an older server gets failures the app describes as its own. The capability probe softens this but does not answer it: it confirms features, not versions. | Deciding the floor, then refusing to sign in below it with a message that names the version. The probe already collects what it needs. |

---

## 2. Things never verified on hardware

The largest cluster, and the one a device run keeps proving. Three separate device runs have each found
defects that the whole test suite passed through — the audit of 2026-08-16 found eight, the run of
2026-08-20 found four. **The suite is 98 test files and no instrumented test.**

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-07 | **No instrumented test exists.** There is no `androidTest` source set anywhere in the repository. Every UI assertion is Robolectric on the JVM, at `sdk = 34` only. | Robolectric asserts the semantics tree, which is what a screen reader consumes. It cannot assert what a real device does with it: no audio focus, no real `MediaSession`, no car, no route change. Everything in PRODUCT_SPEC 17.2 is untested. | A managed-device suite for the playback and download paths. This is the single highest-value item in Phase 6 and the most expensive: CI has no emulator today. |
| R-08 | **The API matrix is mostly untested.** PRODUCT_SPEC 17.2 asks for API 26, 31, 34 and 36. Two files now run at more than one level, chosen because the level changes the mechanism rather than for coverage: `AppLocaleScreenTest` at 26, 33 and 34 — the per-app-language backport either side of `LocaleManager`'s arrival — and `WindowSizeScreenTest` at 26 and 34, where Lint warned that inset behaviour is target-SDK dependent. Everything else runs at 34. | API 26 is eight releases below anything else exercised. The storage-volume code remains written against documentation rather than a device, and no JVM run can tell you what a real API 26 device does with a media session. | Widening the Robolectric matrix further is cheap and has diminishing returns; the part that matters needs R-07. |
| R-09 | **The two-hour playback soak has never run** (PRODUCT_SPEC 17.3, 25). | The requirement is "no crash in 2-hour continuous playback". A leak in the session coordinator or the metrics recorder would appear at minute 90 and never in a unit test. | It needs a device and patience, not new infrastructure. Worth doing manually once and recording the result before any release. |
| R-10 | **Android Auto has never been run**, in a car or in the Desktop Head Unit. The browse tree, its search, the recent root behind the resume tile, the per-chapter completion badges and `onSetMediaItems`' spoken-query path are fully implemented and entirely unexercised. Every one of those is a *rendering* contract — whether a head unit draws a completion bar from `EXTRAS_KEY_COMPLETION_PERCENTAGE` at all is up to the head unit, and no JVM test can answer it. | A driver is the user least able to work around a broken screen. A tree that throws leaves the app listed and dead. | The DHU, which is free and runs on a workstation. PRODUCT_SPEC 17.2 asks for it "where practical". |
| R-11 | **Process death and low storage are untested.** PRODUCT_SPEC 17.3 allows at most 10 s of progress loss after a forced kill; nothing measures it. | Product priority 2 is "do not lose progress", and its acceptance number has never been checked. | `adb shell am kill` plus a stopwatch, once, on a device. Cheap to do and impossible to claim without doing. |
| R-12 | **The release build is assembled but never executed.** `main.yml` runs `assembleRelease`, so R8 and resource shrinking run — and nothing installs or launches the result. | R8 breaks reflection-shaped code. Room, kotlinx.serialization and protobuf-lite have keep rules; Hilt, Media3, WorkManager and Coil rely on their libraries' own consumer rules. A missing rule is a release-only crash with a minified stack trace. | Launching the release APK once by hand, and ideally a smoke test on it in CI after R-07. |

---

## 3. Contracts taken from source rather than from a server

PRODUCT_SPEC 22.4 and 22.5 forbid guessing server behaviour and require a captured fixture before relying
on a shape. Three places rely on a shape read from the server's *source code* instead, which is a weaker
guarantee honestly labelled — and ADR-0012's posture makes reading it legitimate. This section exists so
nobody later mistakes a source-derived fixture for a captured one.

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-13 | **Cover upload's contract is source-derived.** Multipart, part named `cover`, validated on the filename extension. Never captured, because it needs an account with the upload grant and an image the capture script should not invent. | An upload that fails on a real server fails at the one moment a user is watching, and the app cannot tell a rejected part name from a rejected image. | One capture against a server whose account holds `upload`. The demo account does not. |
| R-14 | **Embed metadata's contract is source-derived**, including the `task_finished` frame that carries the outcome. Starting one needs an administrator; the public demo account is refused. `TaskFramesTest`'s fixtures are hand-written from the 2.36.0 source and say so. | The feature rewrites the user's source audio files. A frame shape that differs means the app reports the wrong outcome for the most destructive operation it offers. | One capture from an admin account on a reachable server. Until then the confirmation wording carries the weight. |
| R-15 | **Closed, and the first version of this entry was wrong to call it unfixable.** Google Books answers `429` to GitHub Actions' address ranges, so the CI capture recorded an empty candidate list while the committed shape came from a real deployment — and the drift check compared whole directories, so the contract-capture job failed on **every run**. An empty key set is not an answer, so the capture script no longer writes one, and the compare step now works file by file: drift fails, a new target is a notice, and a target this run could not capture is skipped. | A permanently red check teaches people to ignore a red check, which is worse than no check. It had been red for at least the last three commits. | Done. The disagreement itself remains — the committed shape is from `audiobooks.dev` and CI cannot reproduce it — but it no longer masquerades as drift. |
| R-36 | **The socket fixtures recorded a race, and the drift check called it drift.** The server answers `auth` asynchronously, so a long-poll returns whatever has queued when it is answered: the `init` frame landed in `socket-auth.json` on one run and in `socket-event-after-progress.json` on the next — same frame, same keys, different file. Found only because fixing R-15 stopped the candidate-search fixture failing every run and masking it. | A byte-for-byte comparison cannot tell a race from the server changing its mind, so a green check would have been the *lucky* outcome rather than the correct one. | **Fixed on the second attempt.** The first fix polled until `init` appeared and *then* captured — but long-polling is destructive, so the probe drained the queue and the capture recorded an engine.io ping against nothing. The fixture went from carrying a race to carrying no frames at all. The poll that finds the frame has to be the poll that is recorded, so the capture itself now retries. **No fixture needed re-baselining.** Both committed files were always correct; it was the capture that was non-deterministic, and the retry makes it produce what they already said. The job is green. |
| R-16 | **A fixture reconstructed from a log is not a fixture.** `search-providers.json` was hand-transcribed from a CI log and was missing an entry; the drift check against a real capture caught it. | The lesson generalises: any fixture whose provenance is a log rather than a capture is a guess with a filename. | Already policy. Kept here because the failure was real and cost a debugging session. |

---

## 4. Data and account safety

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-17 | **`accountType` defaults to `''` for any install that upgraded through migration 18**, until a sign-in or a permission refresh rewrites it — and `ProfileRole.ofAccountType("")` is `Listener`. | Nothing today: the UI gates on the `role` column, which sign-in writes. It stays a loaded gun for the next reader, because gating on `accountType` instead would silently demote every upgraded admin until their next refresh — the exact shape of the defect that hid the account-management row on a device. | **Named in a test rather than left in prose:** `version 18 leaves the account type empty, which is the least privileged role` asserts the default, the mapping, and that `role` is the column a permission check should read. A backfill on first launch would retire it entirely. |
| R-18 | **Closed.** Migrations 18 and 19 had no test of their own — 15, 16 and 17 always did, and the first version of this entry overstated it. The chain was covered either way, since every test migrates from its starting version all the way to current, but nothing asserted what 18 *defaults its new columns to*. Three tests now do, including one that pins R-17 by name. | A wrong default is the failure mode that survives a migration test: the step succeeds, the column exists, and its value is a lie. | Done. |
| R-19 | **No profile lock** (AUTH-005). Anyone holding the unlocked phone can switch profiles and read another household member's library and progress. | On a personal device this is the same exposure as the home screen. On a shared one it is not, and it also blocks ROUTE-002's "auto-play never starts when the profile is locked". | AUTH-005 itself. It is a Phase 1 gap that has stayed open through five phases. |
| R-20 | **`allowBackup="false"`**, so a device-to-device transfer carries nothing — not the sign-in, not the settings, not the downloads. | Deliberate, and the right default for a store of server credentials. It is a risk only in that a user replacing a phone will read it as data loss unless something says so. | A line in the release notes. Not a code change. |
| R-21 | **Progress is the only thing this app can lose that the user cannot recreate.** Everything else re-syncs. | Product priority 2. The outbox and the session coordinator exist for this, and R-11 is the reason the guarantee is unmeasured. | R-11. |

---

## 5. Playback correctness

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-22 | **Excluded tracks resolve against the wrong offsets** (PLAY-003). A book whose server-side track list excludes a file maps player positions to book positions incorrectly. | A resume lands at the wrong point, in a book the user cannot tell is affected. Rare — nearly no book excludes a track — and silent when it happens. | `startOffset` carried into the media item's extras so `PlayerPositions` and `BookMediaSourceFactory` share one coordinate space, as ADR-0016 describes. Open since Phase 2. |
| R-23 | **A dropped websocket leaves an embed's outcome unknown.** Nothing replays a missed `task_finished`. | The app says "outcome unknown — verify on the server", which is honest and is also the worst possible answer about an operation that rewrote audio files. | Polling the task endpoint after a reconnect, if one exists. Not investigated. |
| R-24 | **Auto-play from a cold start is unreliable** — Android's background-start rules, not this app's. The setting says so. | A user who configured auto-play on a car connection sees it work sometimes. | Nothing. Correctly disclosed rather than fixed. |

---

## 6. Performance, never measured

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-25 | **No baseline profile and no macrobenchmark module.** PRODUCT_SPEC 17.3's four numbers — player start under 1 s, library interactive under 1 s, a 2,000-item grid scrolling acceptably, no ANR under download/playback stress — are unmeasured. | Startup and scroll are the two things a user judges in the first ten seconds. A baseline profile is typically worth 20–30% of cold-start time and costs one module. | A `:benchmark` module. It needs a device, so it lands with R-07. |
| R-26 | **The 2,000-item fixture library does not exist.** There is nothing to run R-25's grid target against, and the shelves are `LazyRow`s of a paged query rather than one long grid. | The target may be measuring a screen this app does not have. Worth resolving before building a benchmark for it. | Deciding whether the requirement describes the shelf or a library grid this build does not offer. |
| R-27 | **Coil's memory cache is at its defaults** with covers loaded at shelf density. | Untested on a low-memory device, which is where an audiobook app spends its life — long sessions, screen off, background. | A measurement, not a change. Guessing at a cache size is how a cache gets worse. |

---

## 7. Accessibility and adaptive layout

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-28 | **`material3-window-size-class` is a declared dependency and is never used.** Every screen is one phone-width column; a tablet, a foldable and a split-screen window all get the phone layout stretched. | PRODUCT_SPEC 4 and §129 make four form factors a target, and §51 makes adaptive layout a release requirement. | Phase 6 work, now in progress. |
| R-29 | **Accessibility is enforced on the JVM and never checked on a device.** `assertEveryControlIsLabelled` and `assertEveryControlIsBigEnough` walk the whole semantics tree on Settings, the book screen, the downloads queue, the profile switcher, the full player, the mini player and the shelf, several of them at a doubled font scale. The net has now found two real defects — chips announced as dead buttons, and the player's secondary controls laid out 4dp tall under large text — so it is worth what it costs. Three things stay out of reach: whether a label is *useful*, whether contrast passes, and what TalkBack does with the reading order. | TalkBack, large text, touch targets and contrast are release requirements (§51, 2.10). The tree being correct is necessary and not sufficient. | One TalkBack pass on a device, and extending the two assertions to the remaining screens — the player, the shelf, the downloads queue and the metadata editor. |
| R-35 | **The 48dp touch-target figure is asserted at 40dp.** `touchBoundsInRoot` — the rectangle the platform actually dispatches to, and the one that makes a Material `IconButton`'s 40dp state layer a 48dp target — is internal to Compose, and the public test API offers only equality assertions. So the check uses Material's smallest component size instead. | It still fails a 24dp icon used as a row's only control, which is the real failure mode. What it cannot prove is the last 8dp, which is a claim about Compose rather than about this app. | The device pass in R-29, or a public minimum-touch-target assertion in a future Compose release. |
| R-30 | **Large-text and RTL layouts are unexercised.** `supportsRtl="true"` is declared and no test renders anything at a large font scale. | Long German or Norwegian labels at 200% font scale are where a fixed-height row clips its text. | Robolectric renders at a configured font scale; a handful of screens at 2.0 would find most of it. |

---

## 8. Process

| # | Risk | If it bites | Retired by |
| --- | --- | --- | --- |
| R-31 | **Local verification and CI can disagree.** Gradle considered test-compile tasks up to date when only the classpath had changed, so two stale test doubles compiled locally and failed in CI. | A green local build is not evidence. This cost a full debugging cycle once. | Already policy: verify with `--rerun-tasks`, and prefer a throwing property over an anonymous implementation of an interface that can grow. |
| R-32 | **Documentation drift has been the most frequent defect in this project.** The About tab described a build three phases old; `PRIVACY.md` and `SECURITY.md` still say "Phase 0: nothing leaves your device"; `docs/release.md` lists blockers that Phases 1–5 resolved. | Every one of these was user-visible or would have been read by somebody deciding whether to trust the app. | Treating user-facing prose as a deliverable of the change that invalidates it, not as a follow-up. |
| R-33 | **Twenty merged branches cannot be deleted from this session** (HTTP 403, and no MCP delete-branch tool). | Cosmetic. Recorded so it is not rediscovered. | A maintainer with push rights, or the GitHub UI. |
| R-34 | **No changelog and no label convention**, both required by PRODUCT_SPEC 18 for a generated release note. | Blocks the release pipeline, cheaply. | A label convention and a generator step. |
| R-37 | **A test double that does not reproduce the shape of the real thing hides defects behind a passing test.** PR #28 found that catalogue reconciliation deleted an entire library on any unchanged refresh: `LibrarySnapshot.books` carries only *expanded* items, an item the server reports unchanged is deliberately skipped, so a second refresh produced an empty list and called `markAllBooksDeleted` — and every read filters `isDeleted = 0`. A test named `refresh is idempotent and does not duplicate rows` had existed for months, refreshed twice, and passed. | It passed because `FakeAudiobookshelfGateway.listBooks` **ignored its `cached` argument** and returned every book on every call, so the fake's `books` was never empty and the production shape never occurred. The fake was not a double; it was a second implementation that agreed with nothing. A 60-second OkHttp `callTimeout` bounding the Media3 stream — PR #28's other serious find — escaped for the sibling reason: nothing in the suite plays for sixty-one seconds. | **The sync half is closed.** The fake now honours `cached.isUpToDate`, and the fixture books carry a server stamp — without one `isUpToDate` can never return true and the skip path is unreachable, which is why the blind spot existed. Reverting the production fix now fails `refresh is idempotent` through the full repository path. The playback half still needs R-07. |
| R-38 | **A defaulted parameter can preserve the bug it was added to fix.** `LibraryApi.listBooks` gained `onCatalogueBatch`, defaulting to `onBatch` so no implementer had to change. Any future persistence caller that does not pass a non-destructive sink silently gets the destructive behaviour the parameter exists to avoid. | Today there is exactly one persistence caller and it passes the sink. The trap is dormant, not absent, and it is the kind that reappears when a second caller is added by somebody reading the signature rather than the KDoc. | Removing the default once every caller is explicit, so the compiler asks the question instead of the reviewer. |

---

## What this register is not

It does not list requirements this build never claimed — those are in `docs/gaps.md`. It does not list
hypotheticals nobody has evidence for. And it does not list the things that look like risks and are not:
the empty capability set on a fresh server, the fake gateway's deliberate sign-in refusal, and the download
volume setting that moves no files are all in `gaps.md`'s final section with the reasoning.
