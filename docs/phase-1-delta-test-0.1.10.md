# Delta test script — 0.1.10-phase1

Three changes, all from the 0.1.9 device run. Short script; 0.1.9's still applies for everything else.

**Install over 0.1.9 without clearing data.** No migration. H-01 needs an existing cache with a
part-played book in it, so keeping the data is the point.

---

## Continue listening is fetched first

| # | Steps | Expected |
| --- | --- | --- |
| H-01 | With books already part-played, pull to refresh and watch the **Continue listening** shelf. | It is correct and complete **early**, before the rest of the library finishes. The other shelves fill in behind it. |
| H-02 | Time the whole refresh and compare against 0.1.9. | **The same or faster.** This is a reordering, not extra work — same requests, different order. A slower refresh here is a bug, and worth reporting with the number. |
| H-03 | Clear data, sign in fresh, and watch. | No priority is applied — there is nothing started yet, so the order is the server's. Should look exactly like 0.1.9's G-12. |
| H-04 | Finish a book, refresh. | It drops off Continue listening and stops being prioritised. It appears on Listen again. |

## The list cover

| # | Steps | Expected |
| --- | --- | --- |
| H-05 | Switch to the flat list. | The cover runs the **full height of the card**, flush against its left edge — no gap above, below or left of it. |
| H-06 | Find a row with a series line and one without. | The taller row has the larger cover. Both are square. |
| H-07 | A book with no cover art. | The placeholder box is the same full height, so the text column still starts in the same place on every row. |
| H-08 | Scroll fast. | No jitter, no reflow as covers load. |

## Settings tabs

| # | Steps | Expected |
| --- | --- | --- |
| H-09 | Open Settings. | Two tabs: **Server** and **About**. Server is selected. |
| H-10 | Server tab. | Address, reported version, sign-in methods, live updates — then the **Libraries** list with the stars. |
| H-11 | Tap a library here. | Stars it, and the home shelf narrows to it, exactly as before. |
| H-12 | About tab. | Version and the phase note, then **Testing**: the capability handshake list and the storage counts. |
| H-13 | The sentence above the capability list. | Still says whether the server has been **checked at all** — this is the old F-20, and it must not have regressed in the move. |
| H-14 | Rotate the device on the About tab. | Still on About. The tab selection survives a rotation. |
| H-15 | Look for a separate About screen. | There isn't one — it is a tab now, and nothing should still navigate to it. |

## Regression

| # | Steps | Expected |
| --- | --- | --- |
| H-16 | Everything in 0.1.9's G-series that touched search, covers on the shelves, or the five shelves. | Unchanged. |
| H-17 | Two accounts: switch, and check Settings on each. | Each shows its own server, its own libraries, its own star. |
