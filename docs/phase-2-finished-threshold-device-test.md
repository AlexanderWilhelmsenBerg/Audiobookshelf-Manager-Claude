# Device test — build 0.9.2-finished-threshold

One requirement, two clauses, and the second is the interesting one: **your server gets a vote.**

Until this build a book was finished when 30 seconds remained, that number was in the code, and your
library's own `markAsFinishedTimeRemaining` was read off the wire and thrown away. Now the number is a
setting, the library's number is honoured, and where they disagree the **longer** of the two wins.

The threshold is not a thing you can watch happen, so most of this is about arranging for it to happen at a
time you are looking.

## 1. The setting exists and says what it does

**Settings → Playback → scroll to Finished.**

**Expect:** a section headed **Finished**, a paragraph explaining it, and a row of chips from **5 s** to
**120 s** with **30 s** selected.

The paragraph is worth reading once, because it states the two things this setting does *not* change: you
can always mark a book finished by hand, and starting a finished book from the beginning always un-finishes
it. Neither is affected by whatever you choose here.

## 2. What your libraries ask for — the part that is new

Look immediately under the chips.

**Expect one of two things**, and both are correct:

- **Nothing.** Your libraries either ask for no rule, or ask for *less* than 30 seconds. Audiobookshelf's
  own default is **10 seconds**, so this is the likely outcome — and it means your setting is the one in
  force. A library asking for less changes nothing, so the app says nothing.
- **A line naming a library**, e.g. *"Fiction asks for 60 s, and the longer of the two wins — so books there
  are finished with 60 s left."* That happens when a library on your server is configured **more** eagerly
  than your setting.

**To make the second one appear on purpose**, and this is worth doing once because it is the clause that was
missing:

1. In the Audiobookshelf **web interface**, open a library's settings and set *Mark as finished when time
   remaining is* to **60 seconds**. Save.
2. On the phone, pull to refresh on the home screen (or switch profile and back) so the library is re-read.
3. Settings → Playback → Finished.

**Expect:** the line, naming that library and 60 s.

4. Now press **90 s** in the chip row.

**Expect:** the line **disappears** — you are now more eager than the library, so your number wins and there
is nothing to warn about. Press **30 s** and it comes back.

That appearing and disappearing *is* the rule. If the line never appears after a refresh, the library setting
did not reach the phone, and that is a defect worth reporting.

## 3. It actually finishes a book

Easiest with a short book, or by seeking near the end of a long one.

1. Set the threshold to **120 s**.
2. Play a book and seek to about **90 seconds before the end**.
3. Let it play for five to ten seconds — the position is journaled every five — then go back to the book.

**Expect:** the book shows as finished.

4. Set the threshold to **5 s** and repeat on a different book at 90 seconds from the end.

**Expect:** it is **not** finished.

## 4. The three things that must still be true

These are the ones a change to a finished rule can quietly break, and they matter more than the feature.

1. **A finished book stays finished when you replay the end.** Open a finished book, seek back a few minutes,
   play for ten seconds.

**Expect:** still finished. Un-finishing is your decision, not a side effect.

2. **Starting a finished book from the top un-finishes it.** Open a finished book and start it from the
   beginning.

**Expect:** it is no longer finished. (This has been true since 0.7.0; it is here because it lives in the same
file as the new rule.)

3. **The checkbox still works, both ways.** Book screen → *Finished*, on and off.

**Expect:** it takes effect immediately and survives leaving the screen. Check the Audiobookshelf web
interface too — it should agree.

## 5. In the car and on a headset

Nothing about this build changes what the car shows, but the finished rule reaches the car by the same route
it reaches the phone: the **Continue** tab lists books that are started and not finished, so a book that
crosses the threshold should leave that tab.

**Expect:** nothing new to test. The car's own reworking is PR 7.

## What is not in this build

- **A percentage rule from your library.** `markAsFinishedPercentComplete` is honoured in code, but no server
  we have ever captured has had a value set for it, so nothing here can demonstrate it. If you set one in
  the web interface, the app will apply it — and if it behaves oddly, that is genuinely new information.
- Duck-instead-of-pause; rebuffer count and startup latency; ROUTE-002's per-device policies; ROUTE-003's
  startup mode; what the car actually shows. All planned, in `docs/phase-2-closeout-plan.md`.
- **The two-hour soak.** Still not run.

## Result

_Not yet run._

| Section | Result | Notes |
| --- | --- | --- |
| 1. The setting is there, with 30 s selected | | |
| 2. A library asking for longer says so, and the note follows the chips | | |
| 3. The threshold finishes a book, and a short one does not | | |
| 4. Replaying the end, restarting from the top, the checkbox | | |
| 5. The car's Continue tab | | |
