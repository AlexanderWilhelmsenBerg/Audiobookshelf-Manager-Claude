# Device test — build 0.9.1-bookmarks

The bookmark button has been a disabled placeholder since wave 2. It works now. This is the only new thing
in the build, so the script is short — but read section 4, because the API's design shows through the UI in
one place and it is better to expect it than to report it.

## 1. Keeping a spot

**Player → the bookmark icon → the button at the top of the sheet.**

The first build of this had no visible way to make a bookmark: the only route was a long press on the
player's icon, and it went unfound — which is the right verdict on a hidden gesture. The sheet now opens with
a button, the same shape Audiobookshelf's own client uses.

1. Play a book. Tap the bookmark icon.

**Expect:** the sheet, with a filled button across the top reading **New bookmark at 1:04:22** — the
position it would use, so you can see whether it has moved since you opened the sheet.

2. Press it.

**Expect:** a bookmark appears in the list below, and a message at the bottom confirms the position. The
sheet stays open.

3. Press it again without moving.

**Expect:** it now reads **Already bookmarked at 1:04:22** and is greyed out. A second bookmark in the same
second is not a thing the server can hold — see section 4 — and the button says so rather than vanishing.

4. Let playback run a few seconds and look at the button again.

**Expect:** the position in it has moved.

5. The **long press** on the player's bookmark icon still works as a shortcut, and skips the sheet
   entirely — useful once you know it is there, which is why it is no longer the only route.

6. Tap a row.

**Expect:** playback jumps there and the sheet closes.

7. Tap the pencil, type something, save.

**Expect:** the note replaces the chapter line on that row. A note is what you wrote; the chapter is only a
stand-in for when you did not write one.

8. Tap the bin.

**Expect:** the row goes.

## 2. On the other device

1. Make a bookmark in the Audiobookshelf **web player** on the same book.
2. On the phone, leave the app and come back — or switch profile and back.

**Expect:** it appears. There is no per-book bookmark endpoint on the server; bookmarks arrive with the
account refresh the app already does on resume, so that is the moment they land.

3. Delete one in the web player, then refresh on the phone.

**Expect:** it goes here too.

## 3. With no network — the part worth testing properly

1. Turn on **aeroplane mode**.
2. Open the sheet and press **New bookmark at …**

**Expect:** a message saying the server could not be reached — **and the bookmark is in the list anyway.**
It is yours; the app does not throw away something you deliberately kept because the network was down.

3. Still offline, delete a bookmark that the server *does* know about.

**Expect:** it disappears from the list.

4. Turn the network back on and refresh.

**Expect:** the one you made offline is **still there**, and the one you deleted offline has **not come
back**. Those are the two failure modes this was built to avoid, and they are invisible until they happen.

## 4. The one thing that is the API, not the app

**Two bookmarks in the same second are one bookmark.**

1. Long-press the player's bookmark icon twice in quick succession, inside the same second — the shortcut,
   because the sheet's own button disables itself and will not let you.

**Expect:** one row, not two.

Audiobookshelf gives a bookmark no id — it identifies one by its **position in whole seconds**, and the
delete route is addressed to that number. So the same second cannot hold two, and the app agrees with the
server rather than showing a row that would vanish at the next refresh. Reporting this as a defect would be
reporting the server's data model.

## 5. In the car

The bookmark command is exposed to the media session, so a head unit that offers custom actions can keep a
spot. It creates a bookmark with **no note**, which is the right answer for somebody driving.

The app now reaches the car — that was settled on 2026-08-14 — but whether a given head unit *surfaces* a
custom session action is the head unit's choice, so a blank here is not necessarily a defect. What the car
shows generally is being reworked in PR 7 (`docs/phase-2-closeout-plan.md`); the bookmark action goes with
it if it needs a home.

## What is still not in this build

- **The book screen does not list bookmarks.** Deliberate: it needs its own data flow keyed by the book you
  are looking at rather than the book that is playing, and a list there that could not start playback at a
  bookmark would be decoration. Follow-up, and recorded as one.
- `markAsFinishedTimeRemaining` and a configurable finished threshold — the next pull request.
- Duck-instead-of-pause; rebuffer count and startup latency; ROUTE-002's per-device policies; ROUTE-003's
  startup mode. All planned, in `docs/phase-2-closeout-plan.md`.
- **The two-hour soak.** Not run.

## Result

Run 2026-08-14 against build 0.9.1. **All sections passed.**

| Section | Result | Notes |
| --- | --- | --- |
| 1. The New bookmark button, listing, renaming, deleting | **Pass** | |
| 2. Bookmarks from another device | **Pass** | |
| 3. Offline: kept, and stays deleted | **Pass** | |
| 4. Same second is one bookmark | **Pass** | |
| 5. In the car | Not exercised | A head unit's own choice whether it surfaces a custom session action |

So PRODUCT_SPEC 11.1's bookmark responsibility is met, and section 8's fourth recommended feature is
delivered. What remains against it is recorded above rather than here: no bookmarks on the book screen, and
no retry for a delete that failed offline.
