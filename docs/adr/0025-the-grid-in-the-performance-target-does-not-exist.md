# ADR-0025 — The grid in the performance target does not exist

**Status:** Accepted, 2026-08-21. Resolves `docs/risks.md` R-26 and re-scopes R-25.
**Requirements:** PRODUCT_SPEC 17.3, 6.3, 9.1.
**Related:** ADR-0016 (a "known defect" that came from one unchecked premise), R-27 (measure before changing).

## Context

PRODUCT_SPEC 17.3 lists four performance targets. Three name something this app plainly has. The fourth
does not:

> scrolling grid maintains acceptable Compose performance on 2,000-item fixture library.

R-26 has stood since the register was written, saying the target "may be measuring a screen this app does
not have" and that this was worth resolving **before** building a benchmark for it. Building the benchmark
first would have meant writing a macrobenchmark that scrolls a grid, discovering there is no grid, and
either inventing one to satisfy the measurement or quietly measuring something else and calling it the
target.

That failure has already happened once in this project. ADR-0016's predecessor entry claimed for four
phases that excluded tracks broke position resolution, because the entry was written from the shape of
*this app's* model rather than from the server's behaviour. One unchecked premise, four phases of a
"known defect" that was not one.

## What the sweep found

A full pass over `app/src/main/kotlin/com/example/shelfplayer/feature/**` and the navigation graph:

- **There is no grid anywhere in the repository.** `LazyVerticalGrid`, `LazyHorizontalGrid`,
  `LazyVerticalStaggeredGrid` and `GridCells` appear zero times.
- **Home is a `LazyColumn`** (`HomeScreen.kt:427`) which, in shelves mode, hosts up to five `LazyRow`
  shelves (`HomeShelvesUi.kt:42`). Each shelf is a **capped preview** — `SHELF_LIMIT = 20` in `HomeShelves.kt` — so
  no shelf ever holds a library's worth of anything.
- **The flat "all books" view exists but is a list, not a grid.** `BooksView.List`
  (`HomeScreen.kt:440-448`) renders one full-width `BookCard` per row in the same `LazyColumn`. It is
  reached by a toolbar toggle and is forced on whenever a query, filter or focus is active
  (`HomeControls.kt:42-43`).
- **There is no library-browse destination.** The nine routes in `ShelfDestinations.kt` do not include one.
  A library screen existed and was deliberately removed; `ShelfPlayerNavHost.kt:87-91` records why.
- **There is no paging.** `androidx.paging` appears nowhere — no dependency, no import. Every list is a
  full `List<T>` in memory: `LibraryRepository` returns `Flow<List<Book>>`, and `HomeViewModel.books` is
  the whole list per emission.

## Decision

**The grid target is re-expressed against the screen that exists, and a second target is added for the
risk that actually threatens it.**

### 1. The scroll target measures `BooksView.List`

A flat `LazyColumn` of `BookCard`s at a fixed 132.dp row height, over a 2,000-item library. This is the
nearest real thing to what 17.3 describes, it is the screen a user with a large library will actually
scroll, and measuring it needs no new UI.

**No grid will be built in order to satisfy a benchmark.** If a grid is wanted later it should be wanted
for a reason a user has, and the performance target should follow the screen rather than the screen
following the target.

### 2. A second target, for the thing the requirement did not anticipate

**Home must reach interactive with a 2,000-item library without exceeding the app's memory budget, and an
emission must not block the frame.**

This is where the exposure actually is, and 17.3 could not have named it because it describes a grid rather
than an architecture. With no paging, `books: Flow<List<Book>>` materialises every visible book on every
emission, and the series, author and genre axes group that list in memory. `HomeViewModel` already reasons
explicitly about the cost at **490 books** and mitigates part of it — `flatMapLatest` cancels the axes that
are not on screen, so a user reading the flat list does not pay to group it four ways. That mitigation is
real and it bounds *grouping*, not *materialisation*.

At 2,000 items the flat list is four times the size the code was reasoned about at, and nothing in the
current design bounds it.

### 3. The fixture has to be generated

`core/network/src/main/resources/fixtures/demo-library.json` holds **7 books**. A 2,000-item fixture cannot
be hand-written and must not be committed as a two-thousand-entry JSON file that no one will ever read a
diff of. It should be produced by a generator with a fixed seed, so the fixture is reproducible without
being stored.

## What this explicitly does not decide

**Whether to adopt paging.** That is the obvious remedy and it is not being adopted here, for R-27's
reason: *"A measurement, not a change. Guessing at a cache size is how a cache gets worse."* Paging is a
large change to `LibraryRepository`, `HomeViewModel` and every screen that consumes a list, and it would
be justified by a number nobody has yet. The targets above are how that number gets taken.

It is worth saying plainly that the measurement may show paging is unnecessary. A `Book` is a small object
and 2,000 of them is not obviously a problem on a 2026 device; the reason to measure is that nobody knows,
not that anybody suspects.

## Consequences

- R-26 closes. R-25 stays open, with a target that describes a real screen.
- The benchmark module, when it is built, scrolls `BooksView.List` and measures Home's time to interactive
  and its memory at 2,000 items. It does not scroll a grid.
- A seeded fixture generator is a prerequisite for both, and is cheaper than either. **The domain half is
  built:** `LargeLibrary` produces a seeded library of any size, and `LargeLibraryScaleTest` runs every
  pure function in `:domain`'s library package over 2,000 books on every pull request. It asserts
  correctness and the shelf cap, never a duration — a JVM stopwatch on a CI runner measures the runner's
  contention, and a threshold written from one fails on a busy machine and passes on a broken change. The
  benchmark's own fixture, a seeded Room database or fake server behind the real UI, is a different
  artefact and still to build.
- **PRODUCT_SPEC 17.3's wording is now known to be inaccurate for this build.** It is left as written
  rather than edited, because the specification is the contract and this ADR is the record of how one of
  its clauses was interpreted. A reader who finds the mismatch should find this file.
- None of the above needs a device to *decide*, which is why it was worth doing before the hardware work
  rather than during it.
