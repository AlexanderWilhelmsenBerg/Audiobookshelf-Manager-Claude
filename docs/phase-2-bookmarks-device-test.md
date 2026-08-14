# Device test — build 0.9.0-bookmarks

The bookmark button has been a disabled placeholder since wave 2. It works now. This is the only new thing
in the build, so the script is short — but read section 4, because the API's design shows through the UI in
one place and it is better to expect it than to report it.

## 1. Keeping a spot

**Player → the bookmark icon.** It now does two things:

- **tap** — opens the list;
- **long press** — keeps wherever you are, with no note.

1. Play a book. Long-press the bookmark icon.

**Expect:** a message at the bottom saying **Bookmarked at 1:04:22** — the position, not just
"Bookmarked", so you can tell whether it caught the bit you meant. Nothing opens.

2. Tap the bookmark icon.

**Expect:** the list, with that bookmark in it, showing its position and the chapter it falls in.

3. Tap the row.

**Expect:** playback jumps there and the sheet closes.

4. Tap the pencil, type something, save.

**Expect:** the note replaces the chapter line on that row. A note is what you wrote; the chapter is only a
stand-in for when you did not write one.

5. Tap the bin.

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
2. Long-press the bookmark icon.

**Expect:** a message saying the server could not be reached — **and the bookmark is in the list anyway.**
It is yours; the app does not throw away something you deliberately kept because the network was down.

3. Still offline, delete a bookmark that the server *does* know about.

**Expect:** it disappears from the list.

4. Turn the network back on and refresh.

**Expect:** the one you made offline is **still there**, and the one you deleted offline has **not come
back**. Those are the two failure modes this was built to avoid, and they are invisible until they happen.

## 4. The one thing that is the API, not the app

**Two bookmarks in the same second are one bookmark.**

1. Long-press the bookmark icon twice in quick succession, inside the same second.

**Expect:** one row, not two.

Audiobookshelf gives a bookmark no id — it identifies one by its **position in whole seconds**, and the
delete route is addressed to that number. So the same second cannot hold two, and the app agrees with the
server rather than showing a row that would vanish at the next refresh. Reporting this as a defect would be
reporting the server's data model.

## 5. In the car, if you get that far

The bookmark command is exposed to the media session, so a head unit that offers custom actions can keep a
spot. It creates a bookmark with **no note**, which is the right answer for somebody driving.

This is untested — the app has not yet been seen in a car at all
(`docs/phase-2-switching-device-test.md` section 2 is the script for that, and its readings are what the
next attempt needs).

## What is still not in this build

- **The book screen does not list bookmarks.** Deliberate: it needs its own data flow keyed by the book you
  are looking at rather than the book that is playing, and a list there that could not start playback at a
  bookmark would be decoration. Follow-up, and recorded as one.
- `markAsFinishedTimeRemaining` and a configurable finished threshold — the next pull request.
- Duck-instead-of-pause; rebuffer count and startup latency; ROUTE-002's per-device policies; ROUTE-003's
  startup mode. All planned, in `docs/phase-2-closeout-plan.md`.
- **The two-hour soak.** Not run.

## Result

| Section | Result | Notes |
| --- | --- | --- |
| 1. Keeping, listing, renaming, deleting | | |
| 2. Bookmarks from another device | | |
| 3. Offline: kept, and stays deleted | | |
| 4. Same second is one bookmark | | |
