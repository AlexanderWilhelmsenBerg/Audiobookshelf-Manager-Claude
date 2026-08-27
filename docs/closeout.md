# Closeout — everything that is left

Written 2026-08-24, after `main` reached `26d4def` with no open pull requests. Phases 0 through 6 are
delivered against `PRODUCT_SPEC.md`; this is the accounting of what is not.

It exists because three documents each answer part of the question and none answers all of it:
`docs/gaps.md` says what the build does not do, `docs/risks.md` says what it does that could go wrong, and
`docs/release.md` says what a public release needs. This file is the single ordered list, and it is
deliberately blunt about which items are *work*, which are *waiting for hardware*, and which are decisions
already taken that a later reader will mistake for omissions.

**Nothing here is a surprise.** Every item below is already recorded in one of those three files with its
reasoning; the value of this page is that it is one page.

---

## 1. Blocks a public release

Five items, in the order they should be done.

| # | Item | Why it blocks | Effort |
| --- | --- | --- | --- |
| ~~1.1~~ | **Done 2026-08-24 — ADR-0026.** Library browsing now goes to trusted/system controllers, Android Auto/Automotive and BookWave itself; everything else keeps `DEFAULT_SESSION_COMMANDS` — transport only. Decided on Media3's own predicates rather than a package allowlist, and never on the caller's claimed package name. R-59 closed; R-60 opened for the residual (the adapter that reads Media3's six facts cannot be reached from a JVM test). | Remaining: confirm on a device that Android Auto still browses. |
| 1.2 | **Launch the release APK once** | R8 runs on every build and its output has never been executed. Minification failures are exactly the class that only appear at runtime. | Minutes, on a device. |
| 1.3 | **The four 17.3 numbers, and the baseline profile** | The thresholds are contractual and unmeasured. The harness exists (`:benchmark`); nothing has run it. | One command plus two manual passes — `docs/benchmark.md`. |
| 1.4 | **Android Auto in the Desktop Head Unit** | An older build passed discovery and media-button resume in a real car on 2026-08-14. The current browse tree, the resume tile and the rendered host surface have never run in one. Phone screenshots cannot substitute. | An evening with DHU. |
| 1.5 | **Process-death progress budget, and the two-hour soak** | 17.3 names both. Progress is the only thing this app can lose that a user cannot recreate (R-21), and the guarantee is unmeasured. | A device and patience; no new infrastructure. |

---

## 2. Waiting on hardware, and on nothing else

Everything here is built and unverified. None of it needs a design decision, and none of it can run in CI —
this project's CI has no emulator, which is the constraint that shaped six phases.

- **The macrobenchmarks** (1.3 above). `./gradlew :benchmark:connectedBenchmarkAndroidTest`, then fill in
  `docs/benchmark.md`'s results table and commit the recorded `app/src/main/baseline-prof.txt`. Expect the
  first run to need a fix or two: the harness compiles and packages but has never been executed.
- **The rest of the instrumented tier (R-07).** One module has an instrumented suite —
  `:core:datastore`, 27 tests green on an SM-S928B on 2026-08-23. `:app`, `:playback` and the download
  path have none. Service binding, Binder boundaries, process recreation, runtime permissions and Room
  migrations are all JVM-tested only.
- **The API matrix (R-08).** 17.2 asks for API 26/31/34/36. One API-36 device has been used.
- **TalkBack, contrast, 2.0× text, RTL, landscape (R-29, R-30, R-35).** The semantics tree is enforced by
  test on every screen and has caught two real defects. What a screen reader *says*, whether contrast
  passes, and whether a mirrored layout inverts action order are all unexercised. The 48 dp touch-target
  figure is asserted at 40 dp because the rectangle the platform dispatches to is internal to Compose.
- **Wide hardware (R-28).** Adaptive layouts exist and were reviewed at phone width. Tablet, foldable and
  split-screen are unreviewed on real hardware.
- **Low storage and process death (R-11).**

---

## 3. Open defects and known-wrong behaviour

| # | Item | State |
| --- | --- | --- |
| ~~3.1~~ | **Withdrawn 2026-08-24 — the defect does not exist.** Investigated before it was fixed, which is the only reason it was caught: Audiobookshelf filters excluded files *before* it accumulates `startOffset`, so `media.tracks` never contains one, and `isExcluded` appears nowhere in the playback module. `docs/gaps.md` had closed this on 2026-08-20; this row inherited a stale `risks.md` entry. The mitigation it recommended would have reintroduced the very conversion ADR-0016 deleted. See R-22. | **Replaced by 3.8.** |
| ~~3.2~~ | **Done 2026-08-24 — ADR-0026 decision 2.** `SignInUseCase` now takes an explicit `SignInIntent` with no default; only the curtain's recovery field clears a passcode, and it is the only screen that warns first. The sharper half of the old behaviour is also gone: `mayActivate` fails closed, so a transient disk error used to reach `forget`. R-44 closed. | — |
| 3.3 | **`accountType` is `''` for any install upgraded through migration 18** (R-17) until a sign-in or permission refresh rewrites it, and `ProfileRole.ofAccountType("")` is `Listener`. Harmless today because the UI gates on `role`; a loaded gun for the next reader. | Named in a test rather than left in prose. |
| 3.4 | **A dropped websocket leaves an embed's outcome unknown** (R-23). Nothing replays a missed `task_finished`. The app says so rather than guessing, which is the honest answer and still a limitation. | Would need polling or a task-status read. |
| 3.5 | **A name containing a comma cannot be typed** into authors, narrators, genres or tags — they are edited as comma-separated text. | Small, real, cosmetic-adjacent. |
| ~~3.6~~ | **Done 2026-08-24 — ADR-0026 decision 3.** `setRecentsScreenshotEnabled(false)` from API 33; not `FLAG_SECURE`, which was declined. API 26–32 keep the thumbnail as an accepted, tested residual. R-62. | — |
| 3.7 | **The embed's failure path is still source-derived.** `isFailed` and `error` are read by `TaskFrames` and no capture provokes them; provoking one needs a file the server cannot write. | Everything else about the embed is now captured. |
| 3.8 | ~~**The zero-duration fallback overwrites stored progress** (R-61).~~ **Done 2026-08-27.** Two halves: `TrackDurations.recovered` computes a single unknown track's length from the server's own total (licensed by `PlaybackSessionDurationTest`, and refusing rather than guessing where a capture has not settled the arithmetic), and `MediaItems.isSingleFileFallback` makes what remains safe — such a book starts at zero and neither progress writer records. | The obvious fix — always concatenate with `C.TIME_UNSET` — was tried and **fails at runtime**; the builder requires a placeholder for progressive sources. Residual: the two writer gates are not reachable from a JVM test (R-43's shape), and the path needs a server that reports a zero duration, which none has. |
| 3.9 | ~~**The history pane showed only this device's events.**~~ **Done 2026-08-27.** The owner asked for it to be *"populated from events from audiobookshelf itself"*. The remote rows that existed were a reconstruction — `LibrarySnapshotWriter.recordRemoteChange` diffs stored progress against a sync — which needs a previous local row (so a book played only elsewhere produced nothing) and sees only the endpoints (so two sessions between syncs became one). `GET /api/me/listening-sessions` is the server's own record; `refreshServerSessions` imports it when the pane opens, persisting into the same table so the rows survive going offline. | Idempotent with **no Room migration**: the row key is the session's own id. Three filters — this book, other devices only, something actually listened. Note the sleep-timer half of the same request already worked: `SleepTimerController` records the start and the stop it causes, and `HistorySheet` already had labels and icons. |

---

## 4. Product and UX work the device pass surfaced

From the signed-in Android 16 session on 2026-08-23. None of these are defects in the sense of "wrong
output"; they are places the app is harder to use than it should be.

- **Settings information architecture** (SET-001/SET-002). Server, Playback and About are long fixed-tab
  pages whose navigation disappears deep in the content. The missing Downloads, Devices, Appearance and
  Privacy categories will make it worse, not better, when added.
- **Notification permission has no onboarding path** (PLAY-001). The debug console reported notifications
  *Blocked* on a fresh install, and nothing asks.
- **Book detail duplicates its title**, and a destructive database-removal action sits at the same visual
  hierarchy as benign overflow actions (LIB-004 / MGR-001 / MGR-005). Product priority 5 says a destructive
  action must describe its actual effect; it should also not look like the others.
- **Metadata editing is a long flat form** with no current-cover preview.
- **Series detail is a sparse list** with no cover, summary, progress header, or per-book play/resume
  (LIB-003).
- **The car's now-playing screen names the book, not the chapter** (PLAY-001 / 11.1). The browse tree
  already answers per-chapter progress; the now-playing surface does not.
- **The car browse root has no Downloads, Series, Authors or Genres** — Continue, Chapters and History are
  useful playback views but are not the phone's principal browse axes.

---

## 5. Scope deliberately not built

These look like gaps and are decisions. Each has a record; none should be reopened without reading it
first.

| Item | Settled by |
| --- | --- |
| **True source-file deletion is not exposed** (MGR-006) | ADR-0021. Both endpoints exist and neither can prove the deletion happened, so the app must never claim it did. |
| **No grid was built to satisfy 17.3's scroll clause** | ADR-0025. The requirement describes a screen this app does not have; the target follows the screen, not the reverse. |
| **Paging is not adopted** | ADR-0025, deliberately left to the measurement in 1.3. The measurement may well show it is unnecessary. |
| **Deleting a user is not offered**; disabling is | USER-003 puts deletion in later scope unless thoroughly contract-tested. |
| **Batch embedding is not offered** | One item at a time, for an operation that rewrites files and cannot be undone. |
| **"Metadata only, cover only, or both" is not offered** | The endpoint has no such parameter; the cover is written with the metadata or not at all. |
| **Only five buffer presets, no raw values** (PLAY-006) | Presets are the useful surface; raw values invite unbootable combinations. |
| **SAF folder choice is a volume, not an arbitrary directory** (DL-003) | Volume selection covers the real need — internal or SD card. |
| **Four job states, not twelve** (§12) | By design. |
| **`allowBackup="false"`** (R-20) | The right default for a store of server credentials. Needs a line in the release notes so a user replacing a phone does not read it as data loss. |
| **No key material in the repository** (R-05) | Play App Signing; no key material here, ever. Since 2026-08-27 the build *accepts* a key supplied from outside the checkout, so a release APK can be signed and installed — and refuses a keystore inside it. `docs/release.md` § Signing. |
| **Auto-play from a cold start is unreliable** (R-24) | Android's background-start rules, not this app's. Correctly disclosed rather than "fixed". |

---

## 6. Process conditions, not work items

Worth keeping in view; not things to close.

- **R-31 — local verification and CI can disagree.** Gradle has considered test-compile tasks up to date
  when only the classpath moved. Verify with `--rerun-tasks` when a classpath changes.
- **R-56 — a green `verifyDebug` in a long session is not always evidence.** Accumulated daemons once made
  a correct tree fail with a missing-type error twice and pass on the third identical run. `./gradlew
  --stop` first.
- **R-32 — documentation drift is this project's most frequent defect.** Four separate occasions, each
  user-visible. Treat user-facing prose as a deliverable of the change that invalidates it.
- **R-37 / R-43 — a test double that does not reproduce the real shape, and a unit test that cannot notice
  nothing calls it.** Both have hidden serious defects behind full green suites here. R-43's case had ten
  passing tests over a feature that was inert on a device.
- **R-46 — the vulnerability scan reports coordinates, not reachability**, and has no exemption mechanism.
  The only ways past a finding are to upgrade or to edit the script.

---

## Suggested order

1. ~~**1.1**, the browse-tree decision.~~ **Done — ADR-0026.** The owner's answers to all five closeout
   questions are recorded there; the order below is theirs.
2. **3.1**, the excluded-track offsets — open since Phase 2, silent when it bites, and it costs a user
   their place in a book.
3. **One device session** covering 1.2, 1.3 and 1.4 together: install the release APK, run the
   benchmarks, record the profile, then plug into DHU. They need the same setup and the same hour.
4. **3.2**, the passcode-destroyed-by-sign-in sentence — cheap, and it is a security setting silently
   disappearing.
5. **Section 4**, in whatever order the owner finds most annoying in daily use. It is the section most
   likely to be reprioritised by using the app, which is the right way to prioritise it.
6. **Section 2's long tail** — the API matrix, TalkBack, wide hardware — as hardware becomes available.

Sections 5 and 6 need nothing.
