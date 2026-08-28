# Delta test script — 0.1.9-phase1

Four changes: **cover art**, **two more shelves**, a **refresh that no longer crawls**, and **server
search**. Earlier scripts still apply for everything not listed here.

**Install over 0.1.8 without clearing data.** No migration. The most interesting cases below are the
ones that compare a *second* refresh against the first, so keeping the existing cache matters.

---

## Cover art (P1-14 / D2)

| # | Steps | Expected |
| --- | --- | --- |
| G-01 | Open the app on the shelves. | Every card shows its cover. A book with no cover art on the server shows a **book icon in a box of the same size** — never a card that collapses to just text. |
| G-02 | Scroll a shelf quickly, then scroll back. | No reflow, no jumping. The box is drawn before the image arrives, so nothing changes size as covers load. |
| G-03 | Switch to the flat list (top-bar icon). | A small square cover at the left of each row. Row heights stay uniform. |
| G-04 | Kill the app, turn off Wi-Fi and mobile data, reopen. | Covers you have already seen are **still there** — they are on disk, not just in memory. |
| G-05 | Continue-a-series shelf. | Each card shows the cover of the **next** book, not of the first. |
| G-06 | On the server, check the access log while browsing (optional, if you have one). | Cover requests carry an `Authorization` header. **No token anywhere in a URL.** |

## The home screen, filled in

| # | Steps | Expected |
| --- | --- | --- |
| G-07 | Open the app. | Up to **five** shelves: Continue listening, Continue a series, Recently added, **Listen again**, **Discover**. |
| G-08 | Finish a book (or scrub to the end and let it mark finished). | It leaves *Continue listening* and appears on **Listen again**. It must not be on both. |
| G-09 | Look at **Discover**. | Books you have never started. The order looks arbitrary — that is intended. |
| G-10 | Pull to refresh, twice. | Discover keeps the **same order** both times. A row that reshuffles under your finger is the bug this was written to avoid. |
| G-11 | A fresh profile with nothing played. | Continue listening and Continue a series are absent, and **Discover is not** — a new account still gets a home screen. |

## Refresh speed (P1-31 / D1)

This is the one worth timing. Use a real library, not the demo profile.

| # | Steps | Expected |
| --- | --- | --- |
| G-12 | Clear data, sign in, and watch the shelf. | Books appear **within a second or two**, before the sync finishes. Do not wait for the spinner. |
| G-13 | While it is still syncing, tap one of those early books. | It opens. Title, author, cover, genres are there. Track count may correct itself, and the play button may be unavailable until the item is expanded — that is the expected half-state, not a failure. |
| G-14 | Wait for the sync to finish, then pull to refresh **again**, and time it. | The second refresh should be **dramatically faster** than the first — seconds, not minutes. Nothing the server reports as unchanged is fetched again. |
| G-15 | Change one book's metadata on the server, then pull to refresh. | That book updates. The others are still skipped. |
| G-16 | Airplane mode, pull to refresh. | Offline wording. The shelf keeps every book and every cover. |

## Server search (P1-20 / D3)

| # | Steps | Expected |
| --- | --- | --- |
| G-17 | Add a new book on the server. **Do not** refresh the app. Search for its title. | It appears. That is the server search working — the cache could not have known about it. |
| G-18 | Type a single letter. | The shelf narrows locally. Nothing is sent to the server for one character. |
| G-19 | Type a full word quickly. | One request after you stop typing, not one per keystroke. |
| G-20 | Search for a book you are **halfway through**, then open it. | The position is **unchanged**. This is the important one: a search must never be able to rewind a book. |
| G-21 | Airplane mode, search for something cached. | Cached matches still appear. No error banner — the server half failing is not a failed search. |
| G-22 | Search on the Series / Authors / Genres axes. | Still local-only, and still works. Those result kinds are deliberately not read from the server yet. |

## Regression

| # | Steps | Expected |
| --- | --- | --- |
| G-23 | Settings → About → Testing. | Server readings and storage readings, as in 0.1.8. |
| G-24 | Star a library in Settings, return home. | Shelves and search are scoped to it. Unstar widens it back. |
| G-25 | Two accounts: switch between them. | Each keeps its own sort order and starred library. No cover, book or position crosses between them. |
| G-26 | Force-stop and reopen. | Everything survives. |

---

## What to report

For G-12 and G-14, an approximate number is worth more than a pass/fail: **how long until the first
book appeared**, and **how long the second refresh took**. Those two are what the whole of P1-31 and D1
were for, and they are the only claims here that a device can contradict.
