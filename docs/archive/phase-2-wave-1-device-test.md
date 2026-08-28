# Phase 2 wave 1 — device test, build 0.2.0-phase2w1

The first build of this project that can play a sound. Everything below needs hardware; nothing below is
covered by the automated suite, which is the whole reason this document exists.

Record the outcome inline. A step that fails is more useful than a step skipped.

## Before you start

- A real Audiobookshelf server with at least one audiobook, and an account signed in to the app.
- Headphones or a Bluetooth headset for steps 6 and 7.
- Install over the previous build rather than a clean install, so the stored progress from Phase 1 is
  still there — step 2 is about resuming it.

## 1. It plays at all

1. Open a book you have never started.
2. Press **Play**.

**Expect:** audio within a few seconds; the button changes to **Pause**; a mini player appears at the
bottom of the screen with the book's title and author.

**If it fails:** the message on screen is the thing to report. "Sign in to a server before playing" means
the profile did not resolve; anything about the server means the session did not open.

## 2. It resumes where you left off

1. Open a book you are part-way through — one the Continue listening shelf shows.
2. The button should read **Resume at …** with a time on it.
3. Press it.

**Expect:** playback starts at roughly that position, not at zero.

This is the one that matters most (product priority 2). A book that restarts from the beginning is a
failure even if everything else passes.

## 3. The notification

1. With a book playing, pull down the notification shade.

**Expect:** a media notification with the book's **cover**, title, author, a progress bar, and play/pause.

**Watch for:** a missing cover. The artwork is fetched through the authenticated client, and a blank
cover here with a visible cover on the shelf means that path is wrong.

Android 13+ will ask for notification permission the first time a book plays. Denying it should cost the
notification and **nothing else** — playback must continue. Try that on a second book if you can.

## 4. The lock screen

1. Lock the phone while a book plays.

**Expect:** the same controls on the lock screen, and pause/play works from there.

## 5. It survives the app going away

1. Start a book playing.
2. Swipe the app off the recents screen.

**Expect:** **audio keeps playing** and the notification stays.

3. Reopen the app.

**Expect:** the mini player is still there, showing the same book at the position it has actually reached
— not the position it was at when you swiped away.

## 6. Headphones

1. With a book playing through headphones, unplug them (or disconnect the Bluetooth device).

**Expect:** playback **pauses**. It must not continue out of the phone's speaker.

## 7. Audio focus

1. With a book playing, trigger something that takes audio focus briefly — a navigation prompt, or a
   voice assistant.

**Expect:** the book **pauses** rather than ducking to a quiet background. An audiobook playing quietly
under a prompt is unintelligible, and PLAY-002 asks for pause.

2. When the interruption ends, note whether the book resumes on its own. Either answer is fine to
   record; wave 4 decides the policy.

## 8. Progress is not lost

1. Play a book for a minute or two. Note roughly where it is.
2. Force-stop the app from Settings → Apps (this kills the process without a graceful shutdown).
3. Reopen the app and go to the book.

**Expect:** the position is within about five seconds of where you stopped. The journal writes every
five seconds, so that is the worst case.

## 9. A multi-file book, if you have one

1. Play a book made of several files, and seek near the end of the first file (or just let it run over
   the boundary).

**Expect:** it crosses into the next file without restarting, and the position the app reports keeps
counting up through the whole book rather than resetting at each file.

**This is the least-proven thing in the build.** The arithmetic is tested against a fixture of a
six-second file and a four-second file; a real book with forty tracks is a different scale.

## What is knowingly not in this build

Nothing here is a bug to report — it is scope:

- No speed control, no skip-forward/back buttons, no sleep timer, no buffer settings (wave 4).
- No chapter list and no seek bar you can drag (wave 2).
- **Progress is not sent to the server yet** (wave 3). It is stored on the device and will reach the
  server the next time the app writes progress by the route Phase 1 already built — so do not expect the
  web UI to update while you listen.
- No Android Auto or Wear browsing.

## The soak, when the above passes

PRODUCT_SPEC's first Phase 2 exit criterion is **two hours of continuous streaming**. It is worth
starting early rather than saving for the end: two hours exposes leaks, wake-lock mistakes and buffer
problems that nothing shorter does. Leave a long book playing with the screen off and report what the
battery and the playback look like at the end.
