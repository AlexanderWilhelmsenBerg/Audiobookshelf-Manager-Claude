# Phase 2 wave 5 — device test, build 0.5.1-one-timeline

Wave 5's first slice is a correctness change, not a feature: **a book is now one timeline window**
(ADR-0016). Media3 used to report the *current audio file's* position and duration to every control
surface, so on a library with a file per chapter the notification and the lock screen read as "time left in
this chapter". Wave 4 papered over it by printing the book's remaining time as a second line of text; this
replaces the number itself and deletes the caption.

The second slice is additive: **a chapter progress bar** under the book's in the full player.

**0.5.1 fixes four things the 0.5.0 run found.** Sections 1, 1b and 3 are where they show up:

- the book's **duration** was a thousand times too long (a 34-hour book read as 527 hours in the
  notification, and the bar sat at the end);
- **back on the notification restarted the book**, because with one timeline window Media3's default
  skip-to-previous has nowhere to go and seeks to zero;
- the seek bar was **too thick** and its thumb was a bar rather than a dot;
- the chapter bar could not be **seeked**.

**Use a multi-file book with chapters.** A single-file book cannot fail any of section 1 — its file *is* the
book, so the old code and the new one agree. If you only have single-file books, sections 1 and 2 prove
nothing and should be recorded as *not tested*.

Record the outcome inline. A step that fails is more useful than a step skipped.

## 1. The notification and the lock screen describe the book

1. Play a multi-file book and skip forward until you are **well past the first file** — a few chapters in.
2. Pull down the notification shade.

**Expect:** the seek bar under the controls is a **book** bar: near the start of its travel if you are an
hour into a twenty-hour book, not near the end of a chapter. The times beside it are the book's elapsed and
the book's total.

**This is the whole change.** Before this build the bar filled and reset at every file boundary.

3. Check the **total**. This is the 0.5.0 defect: a 34-hour book showed as 527 hours.

**Expect:** the book's real length, matching what the Audiobookshelf web UI says and what the app's own
player shows. Not hundreds or thousands of hours, and not a bar pinned at the far end.

4. Look at the notification's **second line**.

**Expect:** the author, and nothing appended after a `·`. Wave 4's "3 h 24 min left" caption is gone,
because the bar above it now says the same thing properly. Its absence is the pass condition.

5. Lock the phone and look at the lock screen.

**Expect:** the same book-wide bar and times.

6. Scrub the notification's bar to roughly halfway.

**Expect:** playback lands roughly halfway **through the book** — crossing into whichever file that is —
rather than halfway through the current file.

## 1b. The notification's two side buttons

The 0.5.0 run found that **pressing back restarted the whole book**. It no longer exists as a control: the
app now puts its own skip buttons in those two slots.

1. Look at the buttons either side of play/pause.

**Expect:** a **back-30** and a **forward-30** glyph, not the previous/next track arrows.

2. Press each one, well into the book.

**Expect:** thirty seconds back, thirty seconds forward. **It must not jump to the start of the book.** This
is the defect; if it reappears, stop and report it.

3. Change **Settings → Playback → Skip buttons** to `10 s` back and `45 s` forward, then look again without
   restarting the app.

**Expect:** the back glyph now shows **10**. The forward glyph loses its number entirely — Media3 has no
45-second glyph, and a button reading "30" that jumps 45 seconds would be worse than one with no number. A
screen reader announces the real amount in both cases. Pressing them jumps 10 and 45.

4. Set both back to `30 s`.

## 2. Crossing a file boundary changes nothing visible

1. Position the book a minute before the end of an audio file. (Chapter navigation is the easy way if the
   book has a chapter per file.)
2. Let it play across the boundary, watching the notification.

**Expect:** the bar keeps advancing. It must **not** jump back to the left, and the elapsed time must not
reset. There should be no audible gap at the join either.

3. Watch the app's own player across the same boundary.

**Expect:** the same — the position keeps counting, and the chapter title changes when the chapter does.

## 3. The chapter bar

1. Open the full player on a book with chapters.

**Expect:** under the book's seek bar and its two times, a **second bar** in a different colour, with
`Chapter 4 of 32` on the left and a countdown like `−12:04` on the right.

Both bars should now be **the same thickness** and much thinner than in 0.5.0, and each thumb should be a
**round dot** rather than a vertical bar.

2. **Drag the chapter bar.** It is a control now, not a readout.

**Expect:** it seeks *inside the current chapter* — dragging to the middle lands halfway through the
chapter, not halfway through the book. The book's bar above moves by a hair. This is the finer control the
0.5.0 run asked for.

3. Compare the two bars.

**Expect:** they disagree, and should. The book's bar barely moves inside one chapter; the chapter bar
crosses its whole width during it.

4. Let a chapter end.

**Expect:** the chapter bar empties and refills from the left, the ordinal goes up by one, and the book's bar
carries on unchanged.

5. **Drag the book's seek bar** slowly.

**Expect:** the chapter bar and its labels follow the finger *during* the drag — not only when you let go.

6. Open a book with **no chapter metadata**.

**Expect:** no chapter bar at all. Not an empty one, not `Chapter 1 of 1`.

7. Turn **TalkBack** on and swipe through the player.

**Expect:** the chapter bar announces something like *"The Flood, 15:00 left in this chapter"* — not a bare
percentage.

## 4. Nothing else regressed

The one-window change touched every position reader in the app, so this section is about the things that
read a position rather than about anything new.

1. **Progress sync.** Play for a minute, then check the book's position in the Audiobookshelf web UI.

**Expect:** the position the app shows, within a few seconds. Not the position inside the current file.

2. **Sleep timer, end of chapter.** Set it and watch the countdown.

**Expect:** it counts down to the end of the *chapter*, and stops there.

3. **Auto-rewind.** Pause for three minutes, resume.

**Expect:** it rewinds by the configured amount, and does not rewind past the start of the chapter.

4. **Skip buttons** near the start and end of the book.

**Expect:** back at 10 seconds in goes to zero rather than to a negative position; forward near the end lands
at the end rather than wrapping.

5. **Resume after the process dies.** Swipe the app away while **paused**, then reopen it.

**Expect:** the same position, to the second. This is the one that would break loudly if a position were
being written as a file offset and read as a book offset.

6. **Settings → About → Testing.** Run the checks that were added in wave 3.

**Expect:** no regression there either.

## 5. What is not in this build

Named so a tester does not report them as defects:

- **Media-button resume** — a headset play button against a dead app still does nothing. It is a Phase 2
  exit criterion and is the next slice of wave 5.
- **Android Auto** — the browse tree is still absent; the app will not appear in a head unit.
- **Bookmarks** — the button in the player is still disabled. It needs a server capture first.
- **Downloads** — Phase 3.

## Result

| Section | Result | Notes |
| --- | --- | --- |
| 1. Notification describes the book | | |
| 1b. Notification skip buttons | | |
| 2. Crossing a file boundary | | |
| 3. Chapter bar | | |
| 4. Nothing else regressed | | |
