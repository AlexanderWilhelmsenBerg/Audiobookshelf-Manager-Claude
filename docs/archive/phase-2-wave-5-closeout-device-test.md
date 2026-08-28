# Wave 5 closeout — device test, build 0.7.0-wave5-closeout

Everything PRODUCT_SPEC names for Phase 2 is now built. This is the run that decides whether that is true in
practice. Five areas, and the first two are the ones nothing in CI can reach.

`docs/phase-2-gaps.md` is the full checklist. When something goes wrong, **Settings → About → Diagnostics →
the event log**, filter to Problems, copy — that is what turns a report into a fix.

## 1. Android Auto — it should exist now

The 0.6.0 run found the app missing from the dashboard entirely. The cause was a single missing manifest
entry (`automotive_app_desc`): Auto enumerates apps by that metadata, not by the media-browser service, so a
perfect browse tree was a tree nobody could reach.

**Before anything else:** in the Android Auto app on the phone, tap the version seven times to enable
Developer settings, then turn on **Unknown sources**. A debug build is not installed from Play, and Auto
hides those by default. If ShelfPlayer is still missing after that, stop — that is the defect, and the event
log will not know about it.

1. Connect to the head unit (or start the Desktop Head Unit) and open the media app list.

**Expect:** ShelfPlayer is there.

2. Open it.

**Expect:** three tabs across the top — **Continue**, **Chapters**, **History**.

3. **Continue.**

**Expect:** your unfinished books, most recently played first, each with a progress bar under the tile. The
first one is the book you were last listening to.

4. Tap one.

**Expect:** it plays, from where you left it. Not from the beginning.

5. **Chapters.**

**Expect:** the chapters of whatever is playing — or of the last book, if nothing is. Tapping one plays the
book from that chapter.

6. **History.**

**Expect:** the recent events for that book, each showing a position. Tapping one returns there. The car list
is capped at fifteen and leaves out plain "played" entries; the phone's is longer.

7. **Voice search.** Say "play <a book title>" to the assistant.

**Expect:** it plays that book. A title, an author or a narrator should all match.

8. **Transport.** Play, pause, and the two skip buttons on the head unit.

**Expect:** thirty seconds each way — the same amounts as the phone, following the Settings value.

### What Android Auto cannot do, so it is not a defect

The request was *"a view next to the cover which can be changed by tabs"*. **An app cannot draw anything in a
car** — Auto renders its own UI from the browse tree, and there is no surface to put a custom view on. Tabs
across the browse screen are the platform's version of that idea, and that is what is built. The
**equaliser** is not a tab because it is not a list of media and, more to the point, is not built at all;
it is a later phase and belongs on the phone.

## 2. Media-button resume — the last Phase 2 exit criterion

1. Play something, then pause.
2. **Swipe the app away** so the process dies. Confirm with the notification gone.
3. Press **play on a headset** (or a Bluetooth car button).

**Expect:** the last book resumes, from its stored position. This has never worked before.

4. Do the same having *finished* every book you have.

**Expect:** nothing happens, and the event log says *"A resume was requested with nothing to resume"*.
Pressing play the morning after finishing a book must not start it again from the end.

## 3. The history pane — now everything, not just seeks

The 0.6.0 run found it showed only seeks and starts.

1. Play a book. Pause it. Play again. Set a sleep timer. Shake to extend it (if shake is on). Seek. Change
   chapter.
2. Open the player's **History**.

**Expect:** all of it, newest first — Played, Paused, Sleep timer set · 30 min, Sleep timer extended,
Seek, Chapter. The sleep-timer rows carry their length after the `·`.

3. **Tap any row**, including a "Paused" one.

**Expect:** playback goes to that position. Every row is tappable now; a marker takes you to where it is, a
jump takes you back to where it came *from*.

4. Play with the screen off on a slow connection for a few minutes.

**Expect:** the list does **not** fill with alternating play/pause entries. Buffering is not a pause.

## 4. The sleep timer's two new settings

**Settings → Sleep.**

1. **Fade out over** now has an **Off** chip.

**Expect:** with Off chosen, a timer expiring cuts the audio without the volume ramping down. With any other
value, it fades over that long.

2. **Rewind when the timer stops playback** — set it to **5 min**.
3. Set a short sleep timer and let it expire.

**Expect:** playback pauses, and the position moves back five minutes. Open History: a **Rewound after the
sleep timer** row, and tapping it returns you to where the timer actually stopped.

**Expect also:** the rewind happens *after* the pause. You should not hear five minutes replay.

4. Set it back to **Off** and let a timer expire.

**Expect:** the position does not move. This is the default, and it matters: an app that moves a saved
position without being asked is the thing product priority 2 forbids.

## 5. Nothing else regressed

The 0.6.0 script (`docs/phase-2-closeout-device-test.md`) still applies in full. Worth re-running:

- **playback recovery** — aeroplane mode mid-book, then Try again;
- **Finished / un-finished** on the book screen;
- the notification's **total** and its **back button**;
- **progress against the server** after a minute of listening.

## What is still not in this build

- **Bookmarks.** The button is still disabled. The 2026-08-13 capture did not include the endpoints, so
  `scripts/capture-contracts.sh` now probes them; the next capture run unblocks it.
- **ROUTE-002's per-device policies.** One global auto-play switch, not `Never react` / `Arm only` /
  `Auto-play` / `Ask` per head unit.
- **A configurable finished threshold**, `markAsFinishedTimeRemaining`, rebuffer count and startup latency,
  the duck-instead-of-pause setting.
- **The two-hour soak.** Not run.
- **Equaliser, widgets, statistics.** Later phases, by decision.
- **Downloads.** Phase 3.

## Result

| Section | Result | Notes |
| --- | --- | --- |
| 1. Android Auto appears and browses | | |
| 2. Media-button resume | | |
| 3. History shows every event | | |
| 4. Fade off, and rewind on stop | | |
| 5. No regressions | | |
