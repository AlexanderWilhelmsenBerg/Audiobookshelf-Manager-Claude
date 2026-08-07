# Delta test script — 0.1.5-phase1

Only what changed since 0.1.4. The 0.1.4 script (`docs/phase-1-delta-test.md`) and the full acceptance
plan still apply; nothing here replaces them.

**Upgrade over 0.1.4 rather than a clean install for at least one run.** Three Room migrations land in
this build (5→6, 6→7) and one of them is only interesting on a database that already has books in it.

---

## Migration

| # | Steps | Expected |
| --- | --- | --- |
| E-01 | Install over an existing 0.1.4 install without clearing data. Open the shelf. | Your library is still there, immediately, with progress intact. **No** re-sync is forced — unlike the 0.1.3 upgrade, which deliberately blanked every shelf. |
| E-02 | Open a book you have progress on. | The position is unchanged. |

---

## Series (P1-15)

| # | Steps | Expected |
| --- | --- | --- |
| E-03 | Settings → tap a library → **Series** tab. | A list of series, alphabetical, each with a book count. |
| E-04 | Find a series with ten or more books. | Book 10 is **after** book 9, not between 1 and 2. This is the whole reason the tab exists. |
| E-05 | Open a series. | Its books in sequence order, and the app bar is the series name. |
| E-06 | On a book inside a series, check the "…, book N" line. | N is that book's position in **this** series, not in some other one it also belongs to. |
| E-07 | Search "harbour" (or any title you have) on the Series tab. | Series containing a matching book stay listed, not only series whose *name* matches. |
| E-08 | A series you have finished entirely. | Says "finished" rather than pointing at book one. |

## Authors and genres (P1-16)

| # | Steps | Expected |
| --- | --- | --- |
| E-09 | **Authors** tab. | Authors alphabetically, each with a book count. |
| E-10 | Tap an author. | Jumps to the **Books** tab, narrowed to that author, with a chip naming them above the filters. |
| E-11 | With the chip showing, change the sort order and type in the search box. | Both still work *inside* the narrowed list. |
| E-12 | Tap the chip. | The narrowing clears; the full library is back. |
| E-13 | **Genres** tab, on a library whose genres are inconsistently capitalised. | `Sci-Fi` and `sci-fi` are **one** row, not two. |

## Filters and sort (P1-16, P1-17)

| # | Steps | Expected |
| --- | --- | --- |
| E-14 | Books tab → **Continue listening**. | Only books you have started and not finished. A book you finished must **not** appear. |
| E-15 | **Downloaded**. | Empty. Nothing downloads yet in Phase 1 — this is correct, not a bug. |
| E-16 | **Recently added** sort. | Newest on the server first. On a library synced before this build, books may all sort together at the bottom: their added date was never fetched, and it fills in on the next refresh. Refresh and re-check. |
| E-17 | Set a sort order on library A, then open library B. | B has its own order, not A's. |
| E-18 | Set a sort order, force-stop the app, reopen, return to that library. | The order is still the one you chose. |
| E-19 | Change the home shelf's sort order, force-stop, reopen. | Still the one you chose. A profile that never chose opens on **Last played**. |
| E-20 | Two accounts: set different orders on each, then switch back and forth. | Each account keeps its own. Neither inherits the other's. |

## Default library (P1-21)

| # | Steps | Expected |
| --- | --- | --- |
| E-21 | Settings → tap the **star** beside a library. | The star fills. Tapping the library *name* still opens it — the two are separate targets. |
| E-22 | Go back to the home shelf. | It shows only that library's books, and the app bar is titled with the library name rather than "Library". |
| E-23 | Unstar it. | The shelf widens back to every library and the title returns to "Library". |
| E-24 | Star a library, switch to an account that cannot see it. | That account's shelf shows **everything it can see** — not an empty screen. |
| E-25 | Remove a profile that had a starred library and a custom sort. | No error. Re-adding the same account starts with defaults, not the removed account's arrangement. |

## Book detail (P1-18, P1-19)

| # | Steps | Expected |
| --- | --- | --- |
| E-26 | Open any book. | Two chips: **On the server** and the download state, side by side, with "Last checked <date>" under them. |
| E-27 | Same screen, scroll. | Genres and tags as chips; publisher, year, language, size, ISBN and ASIN as lines — each **absent** rather than shown as a dash when the server has no value. |
| E-28 | A book whose server metadata has an ISBN: search the number **with hyphens** as printed on a jacket. | It is found. Search it without hyphens too. |
| E-29 | Search the last few digits of that ISBN. | **Not** found. Only a prefix matches — this is deliberate. |

---

## Known gaps — do not report

- **No cover art anywhere.** Still blocked: the contract capture returned 404 for the cover endpoint
  because the CI fixture book had no cover. The seed is fixed; the shape has to be captured before any
  image code is written (PRODUCT_SPEC 22.5).
- **Downloaded filter is always empty.** Downloads are Phase 4.
- **No server-side search enrichment.** Needs its own capture.
- **No playback.** Phase 2.
- **Collections tab absent.** Conditional on a capability probe that does not exist.
- **Nothing in Settings about server compatibility.** That is P1-22.
- **`http://` servers still refused by every build.** That is P1-23.
