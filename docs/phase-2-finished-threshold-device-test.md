# Device test — build 0.9.3-finished-threshold

One requirement, and the rule is **your server's**, not the app's.

Until this build a book was finished when 30 seconds remained, that number was in the code, and your
library's own `markAsFinishedTimeRemaining` was read off the wire and thrown away. Now: where a library on
your server sets that value, ShelfPlayer uses it. The setting in the app covers libraries that set nothing.

There is no percentage rule and there will not be one — 95% of a hundred-hour book leaves five hours to go.
If a library on your server is configured with a percentage and *no* time remaining, this app will not
honour it. That is the one place it knowingly diverges from the server, and it is deliberate.

## 1. The setting exists and says what it does

**Settings → Playback → scroll to Finished.**

**Expect:** a section headed **Finished**, a paragraph explaining it, a row of chips from **5 s** to
**120 s** with **30 s** selected, and under them **one line per library**.

The paragraph states the two things this setting does *not* change: you can always mark a book finished by
hand, and starting a finished book from the beginning always un-finishes it.

## 2. What each of your libraries actually uses — the part that is new

Read the lines under the chips.

**Expect** one line per library, each saying one of two things:

- **"Fiction: 10 s, from your server"** — that library sets its own value, and that value is what its books
  use. The chips above do nothing for it. Audiobookshelf's own default is 10 seconds, so this is the likely
  line for most people.
- **"Fiction: no value on the server, so the one above is used"** — that library sets nothing, so the chips
  apply.

**Cross-check one of them against the web interface:** open the library's settings there and read *Mark as
finished when time remaining is*. The number on the phone must be the same number. If they disagree, that is
the defect worth reporting, and the line on the phone tells you exactly what the app thinks.

Change it in the web interface, refresh on the phone (pull to refresh on the home screen, or switch profile
and back), and the line should follow. It arrives with the library refresh, not instantly.

## 3. It actually finishes a book — and read section 4 first

Use the number *that library reports*, not the chips, unless the line says the chips apply.

1. Play a book and seek to a few seconds before the reported threshold — for a library at 10 s, about
   **15 seconds** from the end.
2. Let it play past the threshold for five to ten seconds; the position is journaled every five.

**Expect:** the book shows as finished.

## 4. The bug that stopped the last run, and how to avoid it

The previous run found a book of 24:32:34 that **could not be seeked into its last 7:52**: the top bar
jumped to -0:00 and the chapter bar snapped back to -7:52.

That is a real defect and it is being fixed separately. The cause is that the player's timeline is built
from the book's *playable* audio files summed from zero, while the chapter bar uses the server's own
coordinates — and on that book the two differ by 472 seconds. Where they differ, the last stretch of the
book is unreachable and no end-of-book rule can fire.

**So for section 3, pick a book where the two agree.** A book whose chapter bar reaches -0:00 at the same
moment as the top bar is one; a single-file book (one `.m4b`) always is. If the book you pick shows the same
snap-back, it is that defect and not this one.

**If you can, report for the affected book:** Settings → About → event log, and find
**"Built a book as one timeline window"**. It reports the timeline's own duration. If that number is
24:24:42 against a stated runtime of 24:32:34, the diagnosis above is confirmed and the difference is
exactly the gap.

## 5. The three things that must still be true

These are the ones a change to a finished rule can quietly break, and they matter more than the feature.

1. **A finished book stays finished when you replay the end.** Open a finished book, seek back a few minutes,
   play for ten seconds.

**Expect:** still finished. Un-finishing is your decision, not a side effect.

2. **Starting a finished book from the top un-finishes it.** Open a finished book and start it from the
   beginning.

**Expect:** it is no longer finished.

3. **The checkbox still works, both ways** — now in the three-dot menu as *Mark as finished*.

**Expect:** it takes effect immediately, survives leaving the screen, and the Audiobookshelf web interface
agrees.

## 6. In the car and on a headset

Nothing here changes what the car shows, but the finished rule reaches it by the same route: the **Continue**
tab lists books that are started and not finished, so a book that crosses the threshold should leave it.

**Expect:** nothing new to test. The car's own reworking is PR 7.

## What is not in this build

- **The end-of-book seek defect** from section 4. Its own pull request.
- Duck-instead-of-pause; rebuffer count and startup latency; ROUTE-002's per-device policies; ROUTE-003's
  startup mode; what the car actually shows. All planned, in `docs/phase-2-closeout-plan.md`.
- **The two-hour soak.** Still not run.

## Result

_Not yet run._

| Section | Result | Notes |
| --- | --- | --- |
| 1. The setting is there, with 30 s selected | | |
| 2. Every library reports what it uses, and it matches the web interface | | |
| 3. The threshold finishes a book | | |
| 4. The event log's timeline duration for the 24:32:34 book | | |
| 5. Replaying the end, restarting from the top, the menu item | | |
| 6. The car's Continue tab | | |
