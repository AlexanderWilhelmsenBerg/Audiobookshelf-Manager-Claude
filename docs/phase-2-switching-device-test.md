# Device test — build 0.8.0-wave5-closeout

Three things came out of the 0.7.0 run. One of them stopped the app being testable at all, so it is first.

## 1. Playing a second book — the one that was broken

0.7.0 could not start any book from the app. The only thing that played was the last book, resumed
automatically by a media button or a car, and pressing anything else left it playing while the screen
showed the new one. The cause was a wave 5 change that answered the media session's "turn this into
something playable" callback for Android Auto's ids only, and dropped the app's own.

1. Play a book. Let it get going.
2. Go back and play a **different** book.

**Expect:** the second book starts, from its own position, with its own title, author and chapter. The
first one stops.

3. Do it again, the other way round.
4. Then press play on a **headset** with the app closed, let the last book resume, and *then* open the app
   and play a different one.

**Expect:** the same. This is the path that failed — a book that arrived by resumption used to be
impossible to replace.

## 2. Android Auto — new readings, because the app is still not in the car

The 0.7.0 build added the manifest entry Auto enumerates media apps by, and the app still did not appear.
That entry is genuinely there — it was checked against the installed APK, not the source — so nothing left
in the build explains it, and this round stops guessing and reports what the phone says instead.

**Settings → About → Testing → Android Auto.** Five rows:

| Row | What it means |
| --- | --- |
| Declared as a car app | The build. Must be **Yes**. |
| Browser service reachable | The build. Must be **Yes**. |
| Android Auto installed | The phone. |
| Installed by | **The one that usually explains it** — see below. |
| Last car connection | Whether a car has ever reached the app. |

If **Installed by** says *Sideloaded*, a red note appears with the fix. It is worth reading in full even
if you think you have already done it, because the two steps are different things:

1. In the **Android Auto** app on the phone, tap the version **ten times** to unlock Developer settings.
2. In Developer settings, turn on **Unknown sources**. Unlocking developer settings does not turn this on.
3. **Force-stop Android Auto** afterwards, so it rebuilds its list of apps.

Then connect and look at the media app list.

**If ShelfPlayer appears:** open it, and the 0.7.0 script (`docs/phase-2-wave-5-closeout-device-test.md`
section 1) still describes what to check — three tabs, progress bars, voice search, thirty-second skips.

**If it still does not appear:** come back to Settings → About → Testing and read **Last car connection**.

- Still *"Never, since the app started"* → no car ever bound to the app. That rules out everything about
  the browse tree and narrows it to discovery.
- A time → the car did reach the app, and the problem is in what it was shown. The event log will have
  *"A car connected to the media session"* with the package name; copy that section.

## 3. The history pane — now the server too, and much more detail

**Player → History.**

1. Play, pause, seek, change chapter, set a sleep timer.

**Expect:** every one of them, newest first, each row now on **three lines**: the positions, what happened,
and then the **time of day and the chapter** it happened in — `21:04 · The Flood`. Days are separated by
**Today** / **Yesterday** / a date.

2. Now listen to the same book somewhere else — the Audiobookshelf web player is easiest — for more than a
   minute. Come back to the phone and pull to refresh, or leave it to sync.

**Expect:** a row in a different colour: **Moved on another device**, at the position the other device
reached, with the *server's* time rather than the moment the phone noticed. Tapping it takes you back to
where **this** phone was, which is the undo.

3. Mark the book finished in the web player.

**Expect:** **Finished on another device**.

4. Listen on this phone for a few minutes with syncing happening normally.

**Expect:** **no** "moved on another device" rows. The phone's own position coming back from the server is
not another device, and a list that says it is would be useless. Anything under a minute of difference is
deliberately not reported.

## What is still not in this build

- **Bookmarks.** The button is still disabled — but the block is gone: the capture you supplied recorded
  all four endpoints, and `docs/api-compatibility.md` has the shape. This is the next slice.
- **`markAsFinishedTimeRemaining`.** The server has a rule — a book within ten seconds of its end is
  finished whatever you say — and the app does not read the library's value for it. Un-finishing itself is
  confirmed working; this is the threshold clause of PLAY-004, and it is a planned pull request.
- ROUTE-002's per-device policies, a configurable finished threshold, rebuffer count and startup latency,
  duck-instead-of-pause.
- **The two-hour soak.** Not run.
- Equaliser, widgets, statistics — later phases, by decision. Downloads and the queue — Phase 3.

## Result

| Section | Result | Notes |
| --- | --- | --- |
| 1. Switching between books | **Pass** | And a book resumed by a headset can now be replaced, which was the path that failed |
| 2. Android Auto — the five readings | **Pass, with a defect** | The app appears in the car and browses. The **Continue** tab opens empty saying "no books"; search finds them. Cause and fix are in `docs/phase-2-closeout-plan.md` PR 7 |
| 3. History: detail, and the server's changes | | |

### What section 1 and 2 settled

**ROUTE-001 — media-button resume — passes.** Pressing play on a headset against a dead process resumes the
last unfinished book, and 0.8.0's fix means a *different* book can then be started from the app. Both halves
had to work for the criterion to be met, and the first one hid the second for a whole build.

**PLAY-001's Android Auto criterion is met for discovery and browsing.** Two runs failed to find the app at
all; it is there now. What is left is the shape of what it shows, which is a different problem from whether
it shows anything, and it has its own pull request.
