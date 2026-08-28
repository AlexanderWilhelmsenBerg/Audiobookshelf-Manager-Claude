# Phase 2 closeout — device test, build 0.6.0-phase2-closeout

Nine of the twelve defects the 0.5.x runs found are fixed in this build, and one of them was serious enough
to be worth reading about before testing:

> **Playback stopped mid-seek and would not restart.** An ExoPlayer that hits an error sits in `STATE_IDLE`,
> and an idle player ignores `play()` *and* `seekTo()`. Only `prepare()` gets it out, and nothing called it —
> so one dropped stream was permanent until a different book was loaded. Exactly what was reported.

`docs/phase-2-gaps.md` is the full Phase 2 checklist this build is measured against.

Record the outcome inline. A step that fails is more useful than a step skipped. **When something goes
wrong, open Settings → About → Diagnostics → the event log and copy it** — that is what section 2 exists for,
and it turns "it stopped" into an error code.

## 1. Playback recovers, and the play button always means something

The hardest one to provoke deliberately. The most reliable way is to break the network mid-book.

1. Play a multi-file book. Once it is going, turn **aeroplane mode on** and wait for it to stop.

**Expect:** it stops (there is nothing to play), and within a few seconds it tries again — the event log
shows `Playback hit an error and will retry` with an attempt number. After three failed attempts the player
shows **"Playback stopped. The server or the connection may be unavailable."** with a **Try again** button.

2. Turn aeroplane mode **off**, then press **Try again**.

**Expect:** it resumes from where it stopped. Not from the beginning.

3. Do it again, and this time press the **play button** rather than Try again.

**Expect:** the same. This is the second half of the fix — an idle player now gets prepared by the play
button, so pressing play is never a no-op.

4. **Now the original report.** Seek back and forth around a multi-file book, hard: drag the bar, jump
   chapters, use the skip buttons, several times in quick succession.

**Expect:** it keeps playing. If it does stop, it must recover by itself or offer the button — **the
combination that must not come back is a stopped book with no message and a play button that does nothing.**

## 2. The event log

1. **Settings → About → Diagnostics → Open the event log.**

**Expect:** a list of what the app has done since it started, newest at the top, with times.

2. Tap **Problems only**.

**Expect:** just the warnings and errors, and the chip shows how many there are.

3. Tap **Copy**, then paste somewhere.

**Expect:** the visible lines as text.

4. **Read it for anything that should not be there.** This is the check that matters more than the feature:
   there must be **no book titles, no author names, no track URLs and no server hostname**. Counts,
   durations, error codes and status codes are expected. If you see a title, that is a defect worth
   reporting on its own (PRODUCT_SPEC 14.5).

## 3. Finished, and un-finished

1. Open a book you have part-listened to. Under the progress bar there is a **Finished** checkbox.
2. Tick it.

**Expect:** the label above changes to *Finished*, the bar fills, and the Audiobookshelf web UI shows the
book finished too.

3. **Untick it.**

**Expect:** it comes back to unfinished, at the position it had, and the server agrees. This is the defect —
before this build there was no way back at all.

4. Mark a book finished, then **play it from the beginning**.

**Expect:** it becomes unfinished by itself. That is what "restarting" means.

5. Mark a book finished, then play the **last minute** of it.

**Expect:** it **stays** finished. Re-listening to the end of a book you finished is a normal thing to do and
must not silently mark it unread.

6. Tick **Finished** on a book you have never opened.

**Expect:** it works. The checkbox is there even with no progress row.

## 4. The history pane

1. Play a book, then seek, skip and change chapter a few times.
2. Open the full player and tap the **History** icon (the clock, between Chapters and Bookmark).

**Expect:** a list, newest first, of every jump — `1:04:12 → 3:20:00` with what kind of jump it was
underneath. The entry where the book started says *Started at …* and has no arrow.

3. **Tap one of the jumps.**

**Expect:** playback returns to where that jump *started* — the position it replaced. This is the undo a
seek has never had.

4. Let the app be killed (swipe it away) and reopen the book.

**Expect:** the history is still there. It is stored, not remembered.

5. Pause for three minutes and resume, with auto-rewind on.

**Expect:** a **Rewind after a pause** entry appears, and tapping it puts you back where you paused — the
same thing the transient Undo notice does, but hours later.

## 5. The small ones

1. **The play button.** Open the full player.

**Expect:** the play button is smaller than in 0.5.1, and the row of actions below it (speed, chapters,
history, bookmark, sleep) is fully visible and not squeezed off the bottom.

2. **The synopsis.** Open a book with a long description.

**Expect:** three lines and a **Show more**. Tapping it expands, and **Show less** collapses it. On a book
with a two-line description there should be **no button at all**.

3. **Settings → About.**

**Expect:** the text under the version no longer says playback is not built.

## 6. Nothing else regressed

Everything the 0.5.1 script covered still applies — `docs/phase-2-wave-5-device-test.md` sections 1 to 4.
Worth re-running at least:

- the notification's **total** (the 527-hour defect);
- the notification's **back button** (must not restart the book);
- the **chapter bar** seeking inside the chapter;
- **progress against the server** after a minute of listening.

## 7. What is still not in this build

Named so they are not reported as defects. All of these are in `docs/phase-2-gaps.md`.

- **Media-button resume** — a headset play button against a dead app does nothing. A Phase 2 exit criterion,
  and the next thing to be built.
- **A process restart does not restore the last book** — same gap.
- **Android Auto** — no browse tree, so the app will not appear in a head unit.
- **Bookmarks** — the button is still disabled; it needs a server capture first.
- **A configurable finished threshold**, `markAsFinishedTimeRemaining`, rebuffer count and startup latency,
  and the duck-instead-of-pause setting.
- **Downloads** — Phase 3.

## Result

| Section | Result | Notes |
| --- | --- | --- |
| 1. Playback recovers | | |
| 2. Event log (including the redaction check) | | |
| 3. Finished / un-finished | | |
| 4. History pane | | |
| 5. Play button, synopsis, About | | |
| 6. No regressions | | |
