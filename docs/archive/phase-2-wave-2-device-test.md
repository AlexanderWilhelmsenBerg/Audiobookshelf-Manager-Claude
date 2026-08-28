# Phase 2 wave 2 — device test, build 0.2.5-chapters

Wave 2 is the global timeline, and almost everything about it is invisible until a **multi-file book**
is played. A single-file book exercises none of the arithmetic this wave added.

**Use a book with several files, and ideally with chapters.** If your library has no multi-file book,
that is worth arranging before this test — a pass on a single-file book proves very little here.

Record the outcome inline. A step that fails is more useful than a step skipped.

## 1. It crosses a file boundary without noticing

1. Play a multi-file book. Drag the scrubber to just before the end of the first file.
2. Let it run past the boundary.

**Expect:** no gap, no restart, and the elapsed time keeps counting up through the whole book rather than
resetting when the file changes.

**Watch for:** the elapsed clock jumping backwards. That is the failure this wave exists to prevent, and
it would mean the position conversion is wrong.

## 2. The scrubber

1. Drag the dot to roughly the middle of the book.

**Expect:** the dot follows your finger without springing back, the two clocks update as you drag, and on
release playback resumes at where you dropped it.

2. Drag it to the far left, and to the far right.

**Expect:** the start of the book, and the end. Not a stuck player, and not a position past the last file.

## 3. Chapters

1. Open the player, tap the **chapters** icon in the lower row.

**Expect:** the list opens **already scrolled to the chapter you are in**, with that chapter marked and
its start time shown against each row.

2. Tap a chapter several places away.

**Expect:** playback jumps there. On a book whose chapters do not line up with its files, it should land
mid-file — that is the point of PLAY-003.

3. On a book with **no** chapter metadata, check the chapters icon is greyed out rather than opening an
   empty sheet.

## 4. The resume position, again

Wave 1 tested this on a single-file book. It is worth repeating here because the arithmetic changed.

1. Play a multi-file book to somewhere in its **third or later** file. Stop.
2. Reopen the book.

**Expect:** the button reads **Resume at …** with a sensible time, and pressing it lands where you left
off — in the right file.

**This is the most important step in this document.** Resuming into the wrong file is the specific bug
the global timeline exists to prevent, and it is invisible on a single-file book.

## 5. The mini player

1. With something playing, collapse the player.

**Expect:** a double-height bar with the cover, play/pause, both skips, the countdown if a timer is
running, and stop.

2. Tap the **cover or the title**.

**Expect:** the full player opens.

3. Tap **pause**.

**Expect:** it pauses, and the player does **not** open. Both controls doing their own thing is the fix
for a real bug — a container click that swallowed its buttons — so it is worth confirming.

4. Check the **forward skip icon reads "30" the right way round.** It was mirrored in 0.2.3.

## 6. Skips across a boundary

1. Position playback about ten seconds before a file boundary. Press **forward 30**.

**Expect:** it crosses into the next file and lands about twenty seconds in. Not stopping at the
boundary, which is what Media3's own skip would have done.

2. Just after a boundary, press **back 30**.

**Expect:** it crosses back into the previous file.

## 7. The book screen

Not arithmetic, just whether the rebuild reads better than what it replaced.

1. Open several books, including one with a long title, one with no author, and one in a series.

**Expect:** the cover, the play and download buttons top-right, then title, subtitle, author, narrator
and series at descending sizes; one line of length/tracks/download state; the progress line; then
sections.

2. Scroll a shelf and the flat list.

**Expect:** every card the same height, covers all the same size, nothing ragged.

3. If you use a large system font, check whether the flat-list rows clip. `ROW_HEIGHT` is a chosen
   number and this is the case that would expose it.

## What is knowingly not in this build

- **Speed and bookmark in the player are disabled placeholders** (wave 4 and Phase 5).
- **The skip interval is fixed at 30 seconds** each way. The setting is wave 4.
- **Progress still does not reach the server.** Wave 3. The web UI will not move while you listen.
