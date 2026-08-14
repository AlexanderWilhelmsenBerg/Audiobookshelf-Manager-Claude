# Device test — build 0.9.4, the book screen's three-dot menu

> **0.9.3 would not start**, on one device and for one reason: it declared database version 14 with a
> different schema than the 0.9.2 build that had already run on that phone, so Room refused to open the file.
> Nothing was damaged — Room refuses *before* it writes — and 0.9.4 migrates that database to version 15 and
> keeps its rows. If this build still fails to start, the cause is something else and I need the crash text.

**Book screen → top right of the cover: download, play, and now ⋮.**

The **Finished checkbox is gone from the screen body** — it is *Mark as finished* in the menu now. That is
the one change here that removes something, so it is worth confirming you can still reach it.

## 1. The menu

**Expect**, in this order: History · Mark as finished · Discard progress · Manage local files (Phase 3) ·
Delete local item (Phase 3) · Go to web client · More info.

The two Phase 3 rows are **greyed out and say so**. They are shown rather than hidden so the menu does not
look finished when it is not.

There is no *Add to playlist*. This app has no playlists in any planned phase, so a greyed row would have
promised something nothing is building.

## 2. Mark as finished

1. On an unfinished book, press **Mark as finished**.

**Expect:** the progress line above reads *Finished*, and the Audiobookshelf web interface agrees.

2. Open the menu again.

**Expect:** the row now reads **Mark as not finished** — it names the state it would put the book *into*.

3. Press it.

**Expect:** the book is unfinished again, at the position you were at rather than at zero.

## 3. Discard progress — the destructive one

1. On a book you are part-way through, press **Discard progress**.

**Expect:** a dialog, **"Discard your progress?"**, whose body says the book goes back to the beginning on
this device and on your server, that no audio files are deleted, that nothing leaves your library, and that a
download stays put.

Read it and tell me if any of it is untrue on your server — that is the whole point of the wording.

2. Press **Keep it**.

**Expect:** nothing happened. The position is where it was.

3. Press **Discard progress** again, then confirm.

**Expect:** the book is back at the beginning, here and in the web interface, and any download is still
downloaded.

4. Open the menu on a book you have never started.

**Expect:** **Discard progress** is greyed out. There is nothing to discard, and a destructive-sounding
control that does nothing is worse than none.

## 4. Go to web client

1. Press it.

**Expect:** your browser opens this book's page in the Audiobookshelf web interface.

**This is the one assumption in the build.** The address is built as `{your server}/item/{item id}`, which is
the web client's own route rather than an API this project has captured. If you land on a not-found page, say
so — it is a one-line correction, and it is the only thing in this build I could not verify from a fixture.

A browser rather than a window inside the app, deliberately: a WebView would ask you to sign in again, inside
an app that is already holding a token it must not hand to a web page.

## 5. More info

**Expect:** a sheet with the publisher, year, language, ISBN, ASIN, audio-file count and the **item's id on
your server**. Fields your server has not filled in are absent rather than blank.

The item id is there because when something is *wrong* — a book matched to the wrong edition, a duplicate,
a file that will not play — that id is what the answer is about.

## 6. History

1. Press **History**.

**Expect:** this book's own events, newest first, with a day heading, a wall-clock time and the chapter each
position falls in. A book nothing has happened to says so rather than showing a blank sheet.

2. Tap a row.

**Expect: nothing.** Deliberate, and I want your opinion on it. In the *player* this list is an undo and a tap
returns you to that position. Here the book may not be playing at all, and starting playback from a tap meant
for a record would move you without being asked. If the dead tap reads as broken rather than as read-only, the
fix is either a line of copy or a *play from here* action — say which you would rather have.

## Result

_Not yet run._

| Section | Result | Notes |
| --- | --- | --- |
| 1. The menu, in order, with the two Phase 3 rows disabled | | |
| 2. Mark as finished, both directions | | |
| 3. Discard progress: the wording, declining, confirming, and the disabled case | | |
| 4. Go to web client — **does the address work?** | | |
| 5. More info | | |
| 6. History — and is a dead tap acceptable? | | |
