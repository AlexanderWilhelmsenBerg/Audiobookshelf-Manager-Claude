# Delta test script — 0.1.6-phase1

The shape changed, so this is mostly about navigation. Earlier scripts still apply for anything not
listed here.

**Install over 0.1.5 without clearing data.** No migration this time; the point is that the shelf and
your progress survive a large UI change.

---

## The single browse surface

| # | Steps | Expected |
| --- | --- | --- |
| F-01 | Open the app. | Home shows **horizontal shelves**: Continue listening, Continue a series, Recently added. A shelf with nothing in it is absent, not an empty strip. |
| F-02 | Look at the bottom of the screen. | A bar with **Books, Series, Authors, Genres**. No tabs anywhere. |
| F-03 | Settings → tap a library **name**. | It stars. It does **not** open a second screen — there is no library screen any more. |
| F-04 | Back to home. | The shelves are narrowed to that library and the title is the library's name. |
| F-05 | Settings → tap the same library again. | Unstars; home widens back and the title returns to "ShelfPlayer". |

## Search as a button

| # | Steps | Expected |
| --- | --- | --- |
| F-06 | Home, no search open. | **No text field.** A magnifying glass in the top bar. |
| F-07 | Tap it. | The field appears **with the keyboard up** — no second tap needed. |
| F-08 | Type something, then tap the X. | The field closes **and the query clears**; the full shelf is back. |
| F-09 | Type a title while on the Books axis. | It switches from shelves to a flat list on its own. A five-card preview of search results would be the app discarding matches. |
| F-10 | Search while on the Series / Authors / Genres axes. | Each narrows its own list. |

## Shelves and the list

| # | Steps | Expected |
| --- | --- | --- |
| F-11 | Top bar, Books axis: the list/shelves icon. | Toggles between the three shelves and the flat A–Z list. Sort and filter chips appear only in list mode. |
| F-12 | Continue listening shelf. | Only started-and-unfinished books, most recent first. A finished book must **not** be there. |
| F-13 | Continue a series shelf. | Only series where you have finished at least one book **and** there is another to go. Each card names the next book and shows "N of M done". |
| F-14 | Tap a Continue-a-series card. | Opens that series, ordered by sequence. |
| F-15 | Recently added shelf. | Newest on the server first. On a library synced before 0.1.5 this shelf may be **absent** — those rows have no added date. Pull to refresh, then re-check. |
| F-16 | Authors axis → tap an author. | Jumps to Books, flat list, narrowed, with a dismissible chip naming them. Sort, filter and search all still work inside it. |
| F-17 | Switch to Genres while an author chip is showing. | The chip is gone — an author is not a filter on genres. |

## Server diagnostics (P1-22)

| # | Steps | Expected |
| --- | --- | --- |
| F-18 | Settings → **Server** section. | Address, reported version, sign-in methods, live-updates state. |
| F-19 | The capability list under it. | Every capability the app knows about, each marked Confirmed or Not confirmed. **Most will say "Not confirmed" — that is correct**, not a bug: the handshake confirms nothing it has not probed. |
| F-20 | Read the sentence above the list. | It says whether the server has been checked at all. "Checked and confirmed nothing" and "never checked" are different states and must read differently. |
| F-21 | Live updates row, with the app open and the server reachable. | Should reach **Connected** shortly after the shelf loads. |

## Regression

| # | Steps | Expected |
| --- | --- | --- |
| F-22 | Two accounts: set a different sort order on each and switch between them. | Each keeps its own. |
| F-23 | Force-stop and reopen. | Sort order and starred library survive. |
| F-24 | Open any book. | Detail screen unchanged from 0.1.5: two availability chips, "Last checked", genres/tags, publisher/year/language/size/ISBN/ASIN where present. |
| F-25 | Turn off the network, pull to refresh. | Offline wording, not a server error. Cached shelf stays. |

---

## Known gaps — do not report

- **No cover art.** Blocked on the contract capture. The shelf cards are laid out with room for one, so
  they will not reflow when it lands.
- **No Collections axis.** `/api/libraries/{id}/collections` is a known endpoint now (ADR-0008) but no
  fixture covers it, and PRODUCT_SPEC 22.5 does not bend for a tab.
- **Downloaded filter always empty.** Downloads are Phase 4.
- **No server-side search enrichment.** Also waiting on a capture.
- **No playback.** Phase 2.
- **`http://` servers still refused by every build.** That is P1-23, next.
