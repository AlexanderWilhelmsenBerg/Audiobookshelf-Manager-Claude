# Measuring PRODUCT_SPEC 17.3's numbers

`PRODUCT_SPEC 17.3` names four performance thresholds. This is how each is taken, what the answer was, and
which of them a committed fixture cannot answer on its own.

Nothing here runs in CI. Every command below needs a phone plugged in, and that is not a limitation of the
tooling — a start-up figure measured on a shared runner describes the runner's contention. `docs/risks.md`
R-25 is the entry this file closes.

---

## The four numbers, and where each comes from

| 17.3 threshold | How it is taken | Automated? |
| --- | --- | --- |
| Player startup from a cached local book under 1 s | The app's own PLAY-006 diagnostic, against a downloaded book | No — needs a real server and a real download |
| Cached library screen interactive under 1 s | `StartupBenchmark`, `timeToFullDisplayMs` | Yes |
| Scrolling maintains acceptable Compose performance on a 2,000-item library | `LibraryScaleBenchmark.scrollBooksList` | Yes |
| No ANR in download/playback stress tests | Manual stress pass against a real server | No — same reason as the first |

Two of them are marked "no", and it is worth being precise about why rather than leaving it as an
inconvenience. Both describe the app doing something **with a server**: playing a downloaded file and
downloading several while playing. The committed fixture writes library rows into the database; it cannot
manufacture audio files, and pointing a committed benchmark at somebody's private server would produce a
number nobody else could reproduce and would put a host name in the repository (PRODUCT_SPEC 14.5). So
those two are a written procedure, taken once against the owner's own server, and their results recorded
here like any other measurement.

ADR-0025 re-expressed the third threshold. 17.3 asks for a *grid*; a full sweep found this app has none —
Home is a `LazyColumn` of capped shelves and the flat view is a list. The benchmark scrolls `BooksView.List`
rather than a grid built to satisfy a measurement.

---

## Before the first run

```bash
./scripts/check-local-environment.sh   # what is missing, and what to do about it
adb devices                            # the phone must be listed and authorised
```

The device needs USB debugging on. It does **not** need root: everything below works on a retail phone.

A locked or sleeping screen makes UiAutomator fail to find anything, so leave the phone unlocked and set
its screen timeout longer than the run.

---

## Running them

```bash
# The three automated measurements. ~15 minutes for all of them.
./gradlew :benchmark:connectedBenchmarkAndroidTest

# Or one at a time.
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.shelfplayer.benchmark.StartupBenchmark
```

Results are printed to the console and written as JSON to:

```
benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/
```

### If the run fails before measuring anything

- **`The fixture receiver did not report the library it was asked for`** — the seeding broadcast did not
  land. Almost always the wrong variant is installed: the benchmark drives `org.homebord.bookwave`, not the
  `.debug` build. `adb shell pm list packages | grep bookwave` should show the unsuffixed one.
- **`No scrollable list on Home`** — the books-view toggle was not found or did not take effect. The
  fixture pins the app's language to English so the content description is stable; if the app was already
  running from a previous session with a different language, `adb shell pm clear org.homebord.bookwave` and
  run again.
- **`ERROR: Debuggable`** — the debug variant got installed. Macrobenchmark refuses it, correctly.

---

## The baseline profile

R-25 records that the *consuming* half is already free: `androidx.profileinstaller` is on the release
classpath transitively, so shipping a profile costs no new dependency. Only the file is missing, and a
profile cannot be hand-written — it is recorded by exercising the app.

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.shelfplayer.benchmark.BaselineProfileGenerator

# Then install what it recorded, where AGP looks for it:
cp benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/*/BaselineProfileGenerator_generate-baseline-prof.txt \
   app/src/main/baseline-prof.txt
```

Re-record it when the start-up path changes materially — a new start destination, a different first screen.
Not on every release: a stale profile makes start-up no worse than no profile, it just stops helping with
whatever changed.

The manual copy is deliberate. The `androidx.baselineprofile` Gradle plugin would automate it, at the cost
of wiring a device-dependent task into the build graph of a project whose CI has no device.

---

## The two that need a server

Both are taken against the owner's own Audiobookshelf instance, on the same phone, and their numbers are
recorded in the results table below.

### Player startup from a cached local book (under 1 s)

The app already measures this. PLAY-006 requires *"rebuffer count and startup latency are available in
local diagnostics"*, and `PlaybackMetricsRecorder` starts its stopwatch in `onMediaItemTransition` — when
the book the listener asked for begins loading, not when `prepare()` is called — so the figure it reports is
the wait to hear the book.

1. Sign in, download a book in full, and confirm it shows as downloaded.
2. Turn off Wi-Fi and mobile data. This is the point: the threshold says *cached local book*, and a device
   with a network cannot prove it did not use one.
3. Force-stop the app, open it, and press play on the downloaded book.
4. Read the startup latency from **Settings → About → diagnostics**.

Repeat five times and record the median. A single reading includes whatever the device was doing.

### No ANR in download/playback stress

1. Start playback of a streamed book.
2. Queue at least five downloads at once.
3. For two minutes: scroll the library, switch axes, open and close the player, switch profiles.
4. Watch for the system's "isn't responding" dialog, and afterwards check
   `adb shell dumpsys activity anrs` and `adb logcat -d | grep -i anr`.

An ANR is five seconds of a blocked main thread, so the test is whether the app keeps answering taps while
it is busy — the interactions in step 3 are the measurement, not decoration.

---

## Results

Taken on **(device, Android version, date)** — fill in when the run happens.

| Measurement | Target | Result | Notes |
| --- | --- | --- | --- |
| Cold start, no compilation — TTID | — | | The first launch after an install |
| Cold start, no compilation — TTFD | < 1 s | | **17.3's "library interactive"** |
| Cold start, baseline profile — TTFD | < 1 s | | What a user gets once the profile ships |
| Cold start, full compilation — TTFD | — | | The floor; not shippable |
| Scroll `BooksView.List`, P50 frame | — | | |
| Scroll `BooksView.List`, P95 frame | < 16.7 ms at 60 Hz | | Read the tail, not the median |
| Scroll `BooksView.List`, P99 frame | — | | |
| Home heap max, 2,000 books | — | | ADR-0025's second target |
| Home RSS anon max, 2,000 books | — | | |
| Player start from downloaded book | < 1 s | | Median of five, aeroplane mode |
| ANR under download/playback stress | none | | |

### Reading the scroll number honestly

**The fixture seeds no covers.** There is no server behind it, so `coverPath` is null on every book and the
list draws text only. Image decode is a substantial part of what scrolling a real library costs, so this
number is a **floor**: the app is at least this fast, and a real library with covers will be slower. What
the measurement is good for is regression — the same fixture, before and after a change — and for answering
ADR-0025's question about whether materialising 2,000 books without paging is affordable at all.

### What the memory number decides

ADR-0025 deliberately did not adopt paging: *"the measurement may show paging is unnecessary. A `Book` is a
small object and 2,000 of them is not obviously a problem on a 2026 device; the reason to measure is that
nobody knows, not that anybody suspects."* That is what the two memory rows are for. No threshold is
asserted in code, because a gate written from the first reading is a threshold chosen to pass.
