# Phase 2 wave 4 — device test, build 0.4.1-book-remaining

Wave 4 is the controls a listener reaches for: speed, the two skips, a rewind after a pause, and the
streaming buffer. Unlike wave 3, almost all of it is visible in the player — so this test is mostly a matter
of using the app and noticing when something feels wrong.

**Use a multi-file book with chapters** for steps 3 and 5. A single-file book cannot fail the chapter clamp.

Record the outcome inline. A step that fails is more useful than a step skipped.

## 1. Speed

1. Play something. Open the full player and tap the **speed** control (top of the lower row — the one that
   shows a gauge at 1×, and the number itself otherwise).
2. Tap **1.5×**.

**Expect:** the audio speeds up immediately, the sheet stays open, the big number reads `1.5×`, and the
control behind the sheet now shows `1.5×` in the accent colour rather than the gauge icon.

3. **The pitch is the point of this step.** Listen to a voice at 1.5× and again at 2×.

**Expect:** faster, not higher. If it sounds like a chipmunk, `setPlaybackSpeed` is not doing what Media3
documents and that is a real defect.

4. Use the **−** and **+** buttons.

**Expect:** 0.05 steps — 1.5, 1.55, 1.6. Tap **+** and then **−** the same number of times and confirm it
returns to exactly the number you started on.

5. Drag the slider to roughly 2.5×, then close the sheet, then reopen it.

**Expect:** the same value. Not 2.4999.

6. **Per-book memory.** Note the speed. Stop the book, play a *different* book.

**Expect:** the second book plays at the **default** speed (1× unless you changed it in Settings), not at the
first book's. Now go back to the first book.

**Expect:** it resumes at the speed you set for it.

7. In the sheet, tap **Use the default speed**.

**Expect:** the book returns to the profile default and the control shows the gauge icon again.

8. Open **Settings → Playback → Speed** and pick `1.25×`. Play a book you have never set a speed on.

**Expect:** it plays at 1.25×. A book you *have* set keeps its own.

## 2. The skip buttons

1. Open **Settings → Playback → Skip buttons**. Set **Back** to `10 s` and **Forward** to `45 s`.
2. Go back to the player and look at both skip buttons.

**Expect:** the back button now shows a **10** inside the arrow. The forward button shows a plain
double-arrow with **no number** — Material has no 45-second glyph, and a button reading "30" that jumps 45
seconds would be worse than one with no number. Both announce the right amount to a screen reader.

3. Press each and watch the elapsed time.

**Expect:** ten back, forty-five forward.

4. Set both to `30 s` and confirm both buttons show **30** again.
5. Check the **mini player's** two skip buttons match.

## 3. Rewind after a pause

1. Open **Settings → Playback → Rewind after a pause**. Turn it **on**. Four bands appear with the amounts
   from the requirement (0 s, 5 s, 15 s, 30 s).
2. Play a book. Pause it. Wait about **three minutes**. Press play.

**Expect:** playback resumes about **five seconds** earlier than where you paused, and a message appears at
the bottom of the screen saying so, with an **Undo** button. It disappears on its own after a few seconds.

3. Do it again and press **Undo** while the message is up.

**Expect:** the position jumps back to exactly where you paused — not five seconds ahead of it, and not
wherever the audio had reached by the time you tapped.

4. **The exclusions matter more than the feature.** Each of these should rewind **nothing**:

| Do this | Why it must not rewind |
| --- | --- |
| Pause for ten seconds, press play | Under two minutes is the 0 s band |
| Pause, drag the scrubber somewhere, press play | You chose that position; moving it overrules you |
| While playing, trigger a navigation prompt or take a phone call, then let it resume | Not a pause you asked for |

5. **The chapter clamp.** Skip to just a few seconds *after* a chapter boundary. Pause. Wait three minutes.
   Press play.

**Expect:** it resumes at the **chapter's start**, not in the previous chapter. This is the step most likely
to find a real bug.

6. Turn the setting **off** and confirm nothing rewinds at all.

## 4. The streaming buffer

1. Open **Settings → Playback → Streaming buffer**. Each preset shows what it buffers — Automatic says it has
   no numbers of its own.
2. Pick **High**. Note the wording: it takes effect the next time a book starts.
3. Stop playback entirely (the × in the mini player), then play a book.

**Expect:** it plays. That is genuinely the assertion — a bad load control crashes the service on the next
play, and there is nothing else to see from the outside.

4. If you have somewhere with a poor connection, compare **Low** and **Very high** there.

**Expect:** the larger buffer stalls less and takes slightly longer to start. If it does not, say so — the
preset may not be reaching the player.

## 5. The sleep timer's custom length

1. Open the sleep-timer sheet and tap **Custom**.

**Expect:** a slider appears with a minute count above it and a **Start this timer** button.

2. Drag it to about 20 minutes and press start.

**Expect:** the countdown begins from 20 minutes and appears in the mini player and the notification.

3. Reopen the sheet.

**Expect:** **Custom** is the selected chip — not nothing selected, and not one of the presets.

## 6. The notification's second line

1. Play a book and pull down the shade.

**Expect:** the second line reads the author, then the **book's** remaining time — "Marisol Holt ·
3 h 24 min left". It changes once a minute.

2. Compare it against the clocks either side of the progress bar.

**Expect:** on a **multi-file** book they should **differ**. Those clocks are Media3's and describe the current
audio *file*; the second line is the whole book. On a single-file book they agree and both are right.

3. Tap the notification.

**Expect:** the app opens.

## What is known to be missing

- **The notification's progress bar is still per-file.** That is ADR-0016 and wave 5: the fix is to present a
  book to Media3 as one timeline window, after which the bar and its clocks describe the book and the second
  line added here is removed. Please do not report the bar as a wave-4 bug — report anything *else* about it.
- **A buffer change does not recreate a live player.** It applies to the next book. Recreating a player
  mid-chapter to honour a setting is the one thing "do not interrupt playback" forbids.
- **No Advanced buffer option**, and **no rebuffer count or startup latency** in diagnostics. Both are
  PLAY-006 and both are wave 5, where there is a soak to measure against.
- **`markAsFinishedTimeRemaining` is still unread.** The app calls a book finished at thirty seconds
  remaining; your library may use a different number. A book that comes back marked finished when the app
  thinks otherwise is that gap, not a new bug.
