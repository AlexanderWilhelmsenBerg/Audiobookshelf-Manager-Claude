# Phase 2 wave 3 — device test, build 0.3.0-sync

Wave 3 is progress reaching the server and never being lost on the way. Almost none of it is visible in
the player: the whole wave shows up in **Settings → About → Testing → Progress sync**, which is the
screen this test is really about.

Open that screen in a second app instance, or keep switching to it. Every step below names the reading it
should move.

**Have ready:** the server's own web UI (to confirm what it actually stored), and — for step 8 — a second
device or browser signed in as the same account.

Record the outcome inline. A step that fails is more useful than a step skipped.

## 0. The screen exists and reads as zero

1. Open **Settings → About**, scroll to **Progress sync**.

**Expect:** every count is `0`, *Last accepted* says **Not yet**, *Last error* says **None**, and *Clock
offset from server* shows a small `+`/`−` value in seconds — not "Not measured yet", because signing in
already produced a response with a `Date` header on it.

**Watch for:** an offset of several minutes. If it is there before you have done anything, this device's
clock is genuinely wrong, and steps 6 and 8 will behave strangely until it is fixed.

## 1. A session is recorded the moment a book starts

1. Play any book for about five seconds. Go to **Settings → About**.

**Expect:** *Sessions recorded* is `1`, *Playing now* is `1`, and *Waiting to upload* is `1` — the server
has not accepted anything yet.

## 2. The thirty-second cadence

1. Go back to the book, let it play for a full minute without touching anything.
2. Return to **Settings → About**.

**Expect:** *Last accepted* shows a clock time within the last thirty seconds, *Because of* says **The
30-second cadence** or **Leaving the app**, *Accepted by the server* is `1`, and *Waiting to upload* is
back to `0`.

**Watch for:** *Waiting to upload* stuck above zero with a code in *Last error*. That is the sync failing,
and the code names the reason.

## 3. The triggers, one at a time

Each of these should move *Because of* to the matching line. Do them one at a time and check between.

| Do this | *Because of* should read |
| --- | --- |
| Pause the book | **Pausing** |
| Drag the scrubber and release | **A seek landing** |
| Skip forward past a chapter boundary | **A chapter change** |
| Let a multi-file book cross into the next file | **A file change** |
| Set a one-minute sleep timer and let it expire | **The sleep timer stopping** |
| Press stop in the mini player | **The player shutting down** |
| Press home while playing | **Leaving the app** |

**Watch for:** *Because of* never changing from **The 30-second cadence**. That means the triggers are not
firing and only the ticker is, which is a real defect even though the position still arrives — a pause
would be up to thirty seconds late.

## 4. The server agrees

1. Note the position in the player. Pause.
2. Open the server's web UI and look at the same book.

**Expect:** the same position, within a few seconds.

**This is the exit criterion PRODUCT_SPEC names as "progress verified against the server", and no reading
on the phone can stand in for it.**

## 5. Offline, then back

1. Turn on aeroplane mode. Play for a minute. Pause.
2. Check **Progress sync**.

**Expect:** *Waiting to upload* rises, *Last error* shows a network code, and the position in the player is
still correct.

3. Turn the network back on. Play for a few seconds, or press home and reopen the app.
4. Check again.

**Expect:** *Waiting to upload* falls to `0`, *Accepted by the server* rises, and *Last error* returns to
**None** — without you having replayed anything.

5. Check the server's web UI.

**Expect:** the offline position is there.

**Watch for:** *Sessions recorded* rising by more than one per book you played. A duplicate means the retry
generated a new id instead of reusing the one it had, and the server's listening history would double-count.

## 6. A deliberate rewind survives

This is PLAY-004's "conflict resolution never blindly chooses the maximum position", and it is the step
most likely to find a real bug.

1. Play a book to about twenty minutes in. Wait for a sync (*Last accepted* moves).
2. Drag the scrubber **back** to about five minutes. Wait for another sync.
3. Check the server's web UI.

**Expect:** the server shows roughly **five** minutes, not twenty.

**Watch for:** the server keeping the later position. That would mean either the app clamped its own
position before sending, or it sent a stale `updatedAt`.

## 7. Process death

1. Play a book to a memorable position. Leave it **playing**.
2. Force-stop the app: `adb shell am force-stop com.example.shelfplayer`, or Settings → Apps → Force stop.
3. Reopen the app and the book.

**Expect:** it resumes within **ten seconds** of where it was — PLAY-004's limit.

4. Check **Progress sync**.

**Expect:** *Sessions recorded* still counts the interrupted session, and it drains on the next play rather
than sitting there forever.

## 8. Two devices

1. On this device, play a book to about ten minutes. Let it sync. Pause.
2. On the second device (or the web UI), move the same book to about thirty minutes.
3. Back on this device, without touching the book, wait a minute — or press home and reopen.

**Expect:** this device does **not** push ten minutes back over thirty. Opening the book should show the
later position.

**Watch for:** *Position declined as older* rising. That is **not** a failure — it is the server correctly
refusing an older position, and it is exactly what should happen if this device does send one.

## 9. Clock skew

1. Turn off automatic time. Set the device clock **five minutes ahead**.
2. Play for a few seconds and check **Progress sync**.

**Expect:** *Clock offset from server* turns red and says the device will win conflicts it should lose, and
the **Checks after wave 3** list marks the clock line as **Seen**.

3. Set the clock back to automatic and confirm the warning clears on the next sync.

**Do not leave the clock wrong.** With it five minutes fast, step 8 will fail by design.

## 9b. The media notification

A device run on 0.3.0-sync reported a playing book with **no notification**. From inside the app that has
three causes with three different fixes, so this build now says which.

1. Play something. Open **Settings → About → Testing → Media notification**.

| Reading | Meaning |
| --- | --- |
| *Notifications allowed* — **No** | The Android 13+ permission was declined. Use the button that appears; the app cannot ask twice. |
| *Media channel* — **Silenced** | The permission is granted but the channel was turned off. Same button. |
| *Showing right now* — **No**, with the two above fine | **This is our defect**, not a setting. Say so and attach `adb logcat -s MediaSessionService:* MediaNotificationManager:*` — I have no way to reproduce it off a device. |
| *Showing right now* — **Yes**, but the shade is empty | Also our defect, and a stranger one. Same log. |

2. If you had to turn the permission on, **pause and resume once**. Media3 posts the notification on a player
   event, and the one that would have posted it already happened while the permission was off.

3. With the notification showing, **tap it**.

**Expect:** the app opens. That was missing until this build — the notification had no tap target at all.

4. Check the transport controls in the notification work, and that the sleep-timer countdown appears there
   while a timer runs.

## 10. The checklist

1. Scroll to **Checks after wave 3**.

**Expect:** the first four lines are ticked after steps 2 and 5, and the rest say **Needs a device** — which
is what steps 6, 7, 8, 9 and 9b are for. Tick those off against this document rather than the screen. The
notification line ticks itself the moment the app can see its own notification posted.

## What is known to be missing

- **`markAsFinishedTimeRemaining` is not read.** The app calls a book finished at thirty seconds
  remaining; the server's library may use a different number. If a book comes back marked finished when the
  app thinks it is not, or the reverse, that is this gap and not a new bug. Wave 4.
- **`timeListened`'s accumulate-or-replace question is open.** If the server's listening-time statistics
  look low by roughly one sync interval per session, say so — that is the observation that settles it. It
  cannot affect a position.
- **The outbox is drained by playback, not by a background worker.** A book listened to offline and then
  never opened again keeps its row until the next play. Not a defect in this wave; noted so it is not
  reported as one.
