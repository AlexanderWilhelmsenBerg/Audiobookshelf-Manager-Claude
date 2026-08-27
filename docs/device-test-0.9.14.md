# Device test — build 0.9.14, against a local Audiobookshelf instance

**What this covers.** Everything merged since the 2026-08-24 session (PRs #42, #44, #46, #47, #48, #49, #50,
#51) plus PR #52, and the six outstanding items that have always needed hardware. It is written to be run in
order against a **local Audiobookshelf instance you control**, because five of the tests need you to change
something on the server and watch the phone notice.

**The build under test is `main` with PR #52 merged.** §3 is #52's own feature and §5 is #51's, which is
already on `main`; if you test a checkout without #52, skip §3 and say so.

**Record the outcome inline, next to each step.** A step that fails is worth more than a step skipped. Where
a step says *capture the log*, do it even if the step passed — three of these tests are diagnostics, not
assertions, and the log is the whole result.

**How to get the log, every time it is asked for:** Settings → About → Diagnostics → **Open the event log** →
**Copy**. Paste it into your report. It is cleared when the app closes, so copy it before killing the app.

> **One thing to read before starting.** §1 tests a fix for a defect *you* found — an untrusted controller
> could clear the queue and stop playback. The fix withholds four Media3 *player* commands, not just session
> commands. The risk of that fix is over-restriction: a headset, a watch, the notification or Android Auto
> losing transport control. §1.4 and §2 are where that would show up, and they matter more than §1.3.

---

## 0. Setup

### 0.1 Build and install

```bash
./scripts/check-local-environment.sh          # says what is missing; --install fixes SDK packages
./gradlew --stop                              # long sessions leak daemons (R-56)
./gradlew ktlintFormat && ./gradlew ktlintCheck
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true --no-build-cache --rerun-tasks
./gradlew :app:installDebug
```

`--rerun-tasks` is not optional on a branch that changed a classpath. Gradle has considered test-compile
tasks up to date when only the classpath moved, and that let two stale test doubles pass locally and fail in
CI (R-31).

**Confirm the build you are testing.** Settings → About → **Version** should read `0.9.14-browse-and-genres`
(code 40). If it does not, you are testing a different build and every result below is misfiled — R-04 is
exactly that failure, nine builds long.

The debug build installs as `org.homebord.bookwave.debug`, so it sits alongside a release install without
clashing.

### 0.2 The server

You need **two accounts** on the local instance and **at least one multi-file book**.

1. In the Audiobookshelf web UI, confirm you have a book with **two or more audio files**. §5 and §8 need it;
   a single-file book cannot tell a per-file position from a per-book one.
2. Create a second user (Settings → Users) if you have not already. §10 needs it, and §1 is easier to read
   with two accounts on the phone.
3. Note the server's address as the phone will reach it. If it is `http://` rather than `https://`, cleartext
   is permitted **in the debug build only** (ADR-0009) — a release build will refuse it, which matters for §6.

### 0.3 What each section needs

| § | Needs |
| --- | --- |
| 1 | The phone. §1.3 needs a third-party media controller app or `adb`. |
| 2 | Desktop Head Unit, or a real head unit |
| 3 | The web client open on a computer, signed in as the **same** account as the phone |
| 4 | The phone, and about twelve minutes of it playing |
| 5 | A crafted audio file — read §5 first, it may not be runnable |
| 6 | An upload keystore, generated in §6.1 |
| 7 | A USB cable and a charged phone; ~20 minutes |
| 8 | Two hours, mostly unattended |
| 9–11 | The phone |

---

## 1. The P1: an untrusted controller cannot clear the queue (PR #48)

**What was wrong.** `onConnect` narrowed the *session* command set for an untrusted caller and left Media3's
default *player* commands alone. Media3's default grants `COMMAND_SET_MEDIA_ITEM`, `COMMAND_CHANGE_MEDIA_ITEMS`,
`COMMAND_STOP` and `COMMAND_RELEASE` to everything that binds — so an outside app could not read the library
and could still stop your book and empty the queue. You found this at `PlaybackService.kt:959` and `:1041`;
both line references were right.

### 1.1 The queue survives an unresolvable request

This is the half that is testable without another app, because the same code path handles it.

1. Play a book. Let it reach a position you can remember — say 3 minutes in.
2. From the notification, press pause, then play. Then skip forward and back.

**Expect:** ordinary behaviour, and the book is still loaded with its position intact. The old code replaced
an unresolvable request with `emptyList()`, which is what emptied the queue; it now returns the currently
loaded item at its current position instead.

**Result:**

### 1.2 The app's own controls are unaffected

3. In the app: seek by dragging the bar, change chapter from the chapter sheet, add a bookmark, set a sleep
   timer, and switch to a different book.

**Expect:** all of it works. The app connects under its own UID and gets full access; if any of this is
refused, the UID check is wrong and that is a serious regression.

**Result:**

### 1.3 An untrusted controller keeps transport and loses the library

Needs a third-party app that binds as a `MediaBrowser`. If you do not have one, **skip to 1.4 and say so** —
1.4 and §2 are the regression risks, and this is the confirmation.

4. Install any third-party media-controller or "media session inspector" app that lists media apps and offers
   play/pause. Do **not** grant it notification-listener access — that is what would make it trusted, and it
   would then legitimately get the library.
5. Start a book in BookWave. Open the other app and find BookWave's session.

**Expect:** it can **play, pause, seek, next and previous** — transport works. It **cannot** list your books:
the browse tree is empty or errors. It cannot stop playback outright or replace the queue.

6. Capture the log. You should see, once per connection:

```
A controller connected without library access   controller=<the other app's package>
```

and, if it tried to browse:

```
A controller without library access was refused   controller=<package>   request=onGetChildren
```

**Note:** `adb shell` is **not** a substitute here. The shell user holds `MEDIA_CONTENT_CONTROL`, so the
platform reports it as trusted for media control and it will legitimately get full access. Testing with adb
tests the *trusted* branch — useful for 1.4, useless for 1.3.

**Result:**

### 1.4 Trusted surfaces still work — the regression that matters

7. **Lock screen / notification:** play, pause, skip both ways, and the sleep-timer and bookmark actions.
8. **Assistant:** "Hey Google, pause" while a book is playing, then "resume".
9. **A Bluetooth headset or watch**, if you have one: play/pause from its button.

**Expect:** every one of these keeps working. These are the callers the platform reports as trusted, and the
whole design of the fix is that they are unaffected. Any of them failing is worse than the defect that was
fixed, so say so plainly if it happens.

**Result:**

---

## 2. Android Auto in the DHU — the empty browse root (R-64)

**This is a diagnostic, not a pass/fail.** Your 2026-08-24 session found the browse root rendering **zero
items** while search returned books. That ruled out the trust gate — `onGetSearchResult` is gated identically
and worked. **Nothing merged since fixes it**, because it has never been diagnosed beyond a hypothesis. This
run is to collect the evidence that settles it.

The hypothesis, so you know what you are looking for: `AutoLibrary.rootTabs()` builds the root only from the
four shelves, `shelves()` takes the first emission of `homeShelves()`, and `ObserveHomeShelvesUseCase` emits
`HomeShelves.Empty` while the active profile is still null. A browse arriving before the profile resolves
would therefore cache an empty root, and Media3 only re-asks on `notifyChildrenChanged`.

1. **Before connecting**, force-stop BookWave so the next launch is cold: Settings → Apps → BookWave → Force
   stop. This is the condition the hypothesis needs.
2. Connect the DHU. Open BookWave.
3. **Record what the root shows** — count the items. Then try search, and record what that shows.
4. **Now the measurement.** Capture the log and find every line reading:

```
A browser asked for a node's children   parent=<id>   children=<count>
```

Write down **each** `parent` and its `children` count, in order.

**What the answer means:**

- `parent=/` (or the root id) with **`children=0`** → the root was built empty and cached. The hypothesis is
  confirmed and the fix is to make the root wait for a resolved profile, or to invalidate it when one arrives.
- **No such line at all** for the root → the car never asked, and the problem is discovery or the root's
  declaration, not its contents.
- `children=4` (the four shelves) while the car shows nothing → the app answered and the host discarded it,
  which is a different defect entirely.

5. **Then repeat the whole thing warm:** with the app already open and the shelf populated on the phone,
   connect the DHU. Record the root's item count again.

**Expect if the hypothesis is right:** cold shows zero, warm shows four. That difference *is* the evidence.

6. While you are there, re-check the things that passed last time, because §1 changed the connection path:
   transport (+30 s / −30 s should still read as exactly +30,005 ms / −30,000 ms in the log), artwork,
   reconnection after unplugging, and the resume tile.

**Result (root cold):**
**Result (root warm):**
**Result (transport, artwork, reconnect):**
**The `children=` lines:**

---

## 3. The history pane shows what the server recorded (PR #52)

**What is new.** The pane's remote rows used to be *derived* — a diff of stored progress against a sync —
which could not see a book this phone had never played, and collapsed two sessions between syncs into one.
The app now reads `GET /api/me/listening-sessions`, the server's own record, when the pane opens.

This is the test the local instance is for.

### 3.1 A book this phone has never played gets history

1. On the phone, make sure the book you are about to use has **never been played on this device**. If it has,
   pick a different one — this is the case the old code could not reach.
2. In the **web client on your computer**, signed in as the **same account**, open that book and listen for
   **at least 30 seconds**. Do not just open it: a session with nothing listened is deliberately filtered out.
3. On the phone, open that book's screen → three-dot menu → **History**. (The book screen, not the player —
   the point of this case is a book the phone has never loaded.)

**Expect:** a row reading **"Listened on another device"**, with a cloud-download icon, showing the span the
web client covered and the time the session started — the *server's* start time, not the moment the phone
fetched it.

**Result:**

### 3.2 The fetch happened, and what it filtered

4. Capture the log. Find:

```
Imported the server's sessions for a book   fetched=<n>   imported=<m>
```

`fetched` is how many sessions the account has in the page (up to 50, across all books). `imported` is how
many became rows for this book. **`imported` should be 1** after step 2.

5. **Check nothing private is in the log.** There must be no book title, no author, no device name and no
   server hostname anywhere in it. Counts and durations are expected. A title here is a defect worth its own
   report (PRODUCT_SPEC 14.5).

**Result:**

### 3.3 Opening the pane twice does not double the row

6. Close the history pane and open it again. Then again.

**Expect:** still **one** row for that session, not two or three. The row's key is the server's own session
id, so re-importing is idempotent. This is the check that would catch the mistake a fresh UUID would make.

**Result:**

### 3.4 This phone's own listening is not duplicated

7. On the **phone**, play the same book for 30 seconds. Pause. Wait ~40 seconds so the session syncs and
   closes.
8. Open the history pane again.

**Expect:** the phone's own listening appears as the ordinary **"Played"** / **"Paused"** rows the player
writes. It must **not** also appear as a second "Listened on another device" row. The app tells its own
sessions apart by the per-install device id it sends when it opens a session.

**Result:**

### 3.5 It survives losing the network

9. With the imported row on screen, turn **aeroplane mode on**. Close the pane. Open it again.

**Expect:** the row is **still there**. This is the reason the rows are persisted rather than merged at read
time — offline is exactly when somebody wonders where their position went. The log will show:

```
Could not read the server's listening sessions; the stored history stands   error=Network
```

10. Turn aeroplane mode off.

**Result:**

### 3.6 A book played only on a *third* device

If you have a second phone or a tablet, listen there instead of the web client and repeat 3.1. Not required,
but it is the only way to see two different remote devices in one pane.

**Result:**

---

## 4. Sleep-timer rows (already built — confirming, not testing new code)

You asked for local events to show the sleep timer starting and the sleep timer stopping the book. **That
already worked** before PR #52 and nothing in it changed that half. This section confirms it reads the way
you expected, because it is the part I did not touch.

1. Play a book. Open the sleep-timer sheet → set a **5 min** timer.
2. Open the history pane.

**Expect:** a row **"Sleep timer set"** showing the timer's length beside the position.

3. Close the pane, and from the notification press the sleep-timer **extend** action (or shake the phone, if
   shake-to-extend is on).
4. Open the pane.

**Expect:** a row **"Sleep timer extended"**, showing the new remaining time.

5. Now set a **short** timer — the custom field accepts seconds; use **30 s** — and let it run out.
6. Open the pane.

**Expect:** two rows. **"Sleep timer ended playback"** at the position it stopped, and — if auto-rewind is on
— **"Rewound after the sleep timer"**, which is deliberately a different row from the ordinary "Rewind after
a pause" because the amount a listener wants after falling asleep is minutes, not seconds.

7. Tap the "Sleep timer ended playback" row — **from the player's history pane, not the book screen's.**

**Expect:** playback returns to where the timer stopped it. "Take me back to where I fell asleep" is the most
useful thing this list does.

> **The book screen's copy of this sheet is deliberately read-only**, so tapping a row there does nothing and
> that is correct, not a defect. The player is *at* a position and can return to one; the book screen may be
> showing a book that is not playing, and a row that started playback from a tap meant for a record would
> move a listener without being asked.

**Result:**

---

## 5. The zero-duration fallback (R-61, PR #51) — read before attempting

**Be aware this may not be runnable, and that is an honest result.** The fix guards a path nobody has ever
observed: a server reporting a track duration of zero. No capture against 2.36.0 has produced one. If you
cannot provoke it, record "not provokable" and move on — do not manufacture a pass.

### 5.1 The regression check, which you *can* do

1. Play a **multi-file** book to a position well into the second or third file — say 8 minutes into a book
   whose first file is 5 minutes.
2. Force-stop the app. Reopen it and resume the book.

**Expect:** it resumes at **8 minutes of the book**, not 3 minutes into file two and not at the start. The
seek bar's total should be the whole book's length, and the notification's clock should count the book, not
the file.

3. Check the server agrees: in the web client, open the book and read its progress.

**Expect:** the same position, within a few seconds.

**Result:**

### 5.2 If you want to provoke the degraded path

Only worth attempting if it is easy on your instance. Put an audio file in the library whose duration
Audiobookshelf cannot determine — a truncated or headerless MP3 is the usual way — and scan it into a book
that has other, normal files alongside it.

**Expect if you manage it:** the book **still plays** (first file only), and the log shows:

```
A track's length is unknown, so this book plays its first file only and will not save progress   tracks=<n>
```

Then the two things the fix is actually for: playback starts at **zero**, not at your stored position; and
after playing for a minute, the book's stored progress on the **server** is **unchanged**. That second one is
the data loss this closed — the old build would have written a file offset over a whole-book position.

**Note:** a book with exactly **one** unknown track will probably *not* degrade at all — the app now
recovers its length from the server's own total for the book. To see the fallback you need **two** unknown
tracks, or one unknown track plus an excluded one.

**Result:**

---

## 6. The release APK — sign it, install it, launch it (PR #50)

**The item that has been open longest.** R8 and resource shrinking run in CI on every push and their output
has **never been executed**. It could not be: the release variant declared no signing config, so the APK was
unsigned and uninstallable. That is now fixable from your side without any key material entering the repo.

### 6.1 Generate an upload key, once

```bash
mkdir -p ~/.bookwave
keytool -genkeypair -v -keystore ~/.bookwave/upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the passwords somewhere a lost laptop does not take with them. This is an **upload** key, not the key
end users verify against — ADR-0024 chose Play App Signing, so Google holds that one and losing this is
recoverable by asking Play to reset it.

### 6.2 Tell the build where it is

In `~/.gradle/gradle.properties` — **outside the checkout**, which the build enforces:

```properties
bookwave.signing.storeFile=/home/you/.bookwave/upload.jks
bookwave.signing.storePassword=…
bookwave.signing.keyAlias=upload
bookwave.signing.keyPassword=…
```

### 6.3 Build, verify, install, launch

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Expect:** `apksigner` prints `Verifies` and `Verified using v2 scheme (APK Signature Scheme v2): true`.
v1 will read `false` and that is correct — v2 is verified from API 24 and this app's `minSdk` is 26.

### 6.4 The bit that has never happened: run it

The release build is a **different application id** (`org.homebord.bookwave`, no `.debug` suffix), so it is a
fresh install with no data. Sign in and then exercise the paths R8 is most likely to have broken:

4. **Sign in** to the local server. If your server is `http://`, expect this to **fail** — cleartext is
   debug-only by design (ADR-0009). Use `https://`, or note it and test the rest against a TLS endpoint.
5. Open a book and **play it**. Then seek, change chapter, and pause.
6. **Download** a book and play it with aeroplane mode on.
7. Open **Settings → About** and confirm the version and the diagnostics screen render.
8. Set a **sleep timer** and a **bookmark**.
9. Open the **history pane**.

**What you are looking for:** anything that works in the debug build and not here. R8 removing a class that
only reflection or serialization reaches is the classic failure, and `kotlinx.serialization` and Room are
both in that category. A crash here is a release blocker; note the exact screen.

10. If it does crash, keep the mapping — `app/build/outputs/mapping/release/mapping.txt` — because a release
    stack trace is unreadable without it.

**Result:**

### 6.5 Three build refusals, if you want to confirm the guard rails

Cheap, and they prove key material cannot slip into the repository:

```bash
./gradlew :app:assembleRelease -Pbookwave.signing.storeFile=$PWD/inside.jks   # refused: inside the repository
./gradlew :app:assembleRelease -Pbookwave.signing.storeFile=/nope/x.jks       # refused: not found
# and with only one of the four properties set: refused, naming the three missing
```

**Result:**

---

## 7. The four 17.3 numbers and the baseline profile (task #54)

`docs/benchmark.md` has the full procedure and the results table to fill in — **use that document, not this
one**, for the details. What belongs here is the running order:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

The phone must be **unlocked, plugged in, and left alone** — an animation or a notification shade during a
frame-timing pass corrupts it. It seeds a 2,000-book fixture library, so give it room.

Then fill in `docs/benchmark.md`'s **Results** table with the device, Android version and date, and commit
the recorded `baseline-prof.txt`.

**One thing to read honestly when you have the numbers:** the fixture seeds **no cover images**, because
there is no server behind it. The scroll figure is therefore a **floor** — a real library with covers will be
slower, because image decode is a large part of what scrolling costs. It is sound as a regression baseline
against itself and it overstates if read as "scrolling is fine". If you can, do one manual scroll pass
through your own library with covers loaded and note whether it feels different.

**These numbers decide the paging question.** ADR-0025 deliberately did not adopt paging, on the grounds that
nobody had measured whether 2,000 un-paged books cost anything. The two memory rows are that measurement. Do
not adopt paging on a feeling; adopt it if the numbers say so.

**Result — the four numbers:**
**Result — memory:**
**Result — baseline profile committed:**

---

## 8. Process death and the two-hour soak (PRODUCT_SPEC 25)

### 8.1 Progress survives the process being killed

Your last session measured 8.3 s / 85 files / 7,508 KiB here. Repeat it, because §1 and PR #51 both touched
the write path.

1. Play a multi-file book to a position you write down — to the second.
2. **Send the app to the background first** — press Home. `am kill` will not touch a foreground process,
   and a silent no-op here would make the test pass without testing anything. Then:

   ```bash
   adb shell am kill org.homebord.bookwave.debug
   adb shell pidof org.homebord.bookwave.debug   # expect no output: the process is gone
   ```

   A kill, not a force-stop: this simulates the system reclaiming the process, which is the case the journal
   exists for. A force-stop is a user action and the app is allowed to behave differently.
3. Reopen the app.

**Expect:** the book is where you left it, within the journal's five-second window. Never at the start of a
file, and never at zero.

4. Check the web client agrees.

**Result (position before / after / server):**

### 8.2 Two hours of continuous playback

Mostly unattended. Start a long book, plug the phone in, and leave it playing.

Check at roughly 30, 60, 90 and 120 minutes:

- it is still playing, and has not silently stopped;
- the position on the phone and the position in the web client still agree;
- the notification is still there and its clock is moving;
- **Settings → About → Playback since the app started** — note **"Times playback ran out of buffer"**. A
  handful over two hours is ordinary on a local network; dozens is a finding.

At the end, capture the log and look for anything repeated many times. A warning that fires once is
information; the same warning 400 times is a defect.

**Result (30 / 60 / 90 / 120 min):**
**Rebuffer count:**
**Anything repeated in the log:**

---

## 9. The app-switcher thumbnail (R-62, PR #47)

**Only meaningful on Android 13 or newer.** Below API 33 the thumbnail is deliberately still there —
`setRecentsScreenshotEnabled` did not exist before then, and ADR-0026 declined `FLAG_SECURE`, which would
have blocked every screenshot for every user to solve a narrow problem for some.

1. Note your phone's Android version.
2. Open BookWave on a screen showing **book titles** — the shelf or a library list.
3. Press the app-switcher (square, or swipe up and hold).

**Expect on Android 13+:** BookWave's card shows **no readable library** — a blank or generic surface, not a
screenshot of your books.

**Expect on Android 12 or older:** the thumbnail still shows the library. That is the recorded, accepted
residual, not a defect.

4. Confirm the narrow control did not become the blunt one: take an **ordinary screenshot** of the app.

**Expect:** the screenshot works. If it is blocked, `FLAG_SECURE` has been set somewhere and that reverses a
decision taken on purpose.

**Result (Android version / thumbnail / screenshot):**

---

## 10. A passcode survives ordinary reauthentication (R-44, PR #46)

**What was wrong.** Any sign-in cleared the profile's passcode, including a routine reauthentication the user
did not initiate. Only explicit locked-profile recovery may clear it now.

### 10.1 Set a passcode

1. Settings → **Passcode lock** → turn on **Require a passcode for this account** → set a 6–12 digit code
   that is not a run or a repeat. Save.
2. Force-stop the app, reopen it.

**Expect:** the lock curtain, **"This account is locked"**.

**Result:**

### 10.2 Ordinary reauthentication keeps it

3. Force the token to be rejected: in the web UI, **change that user's password**.

   **This trigger is not certain and that is worth knowing before you rely on it.** Whether Audiobookshelf
   2.36.0 invalidates an outstanding refresh token on a password change is not something this project has
   captured, so it is the likely lever rather than a verified one. If the profile never flips, that is itself
   a result worth reporting — and the fallback is patience: the access token expires in hours, and the app
   meets it on the next resume.
4. On the phone, unlock and use the app until it notices — open the shelf, pull to refresh, or switch
   profiles. The profile should show **"Needs to sign in again"**.
5. Sign in again with the **new** password, from the shelf banner or the profile card.
6. Force-stop the app. Reopen it.

**Expect:** the lock curtain is **still there**. The passcode survived. This is the fix; before it, step 5
silently removed a security setting the user had chosen.

**Result:**

### 10.3 Only explicit recovery clears it

7. Force-stop, reopen, and at the curtain tap **"Forgotten your passcode?"**.

**Expect:** a warning that signing in clears the passcode, and a button reading **"Sign in and clear the
passcode"** — not a bare "Sign in". The wording has to say what it will do.

8. Enter the account password and submit.

**Expect:** the app opens, and force-stopping and reopening now goes **straight in** — no curtain. The
passcode is gone, because you asked for that.

**Result:**

### 10.4 The lockout, briefly

9. Set a passcode again. Force-stop, reopen, and enter a **wrong** code repeatedly.

**Expect:** it counts down remaining tries, then makes you wait, and the wait grows. After the final failure
the passcode is refused permanently and only signing in again clears it. You do not need to exhaust it —
confirm the countdown and the first wait appear.

**Result:**

---

## 11. The instrumented tier on hardware

CI has no emulator, so this suite never runs there. It passed 27/27 on 2026-08-23; it is worth re-running
because PR #46 changed the profile-lock code around it.

```bash
./gradlew :core:datastore:connectedDebugAndroidTest
```

**Expect:** all tests pass. This is the only tier that exercises the profile lock's real AndroidKeyStore
storage; a JVM test cannot.

**Result (count passed / failed):**

---

## 12. What to send back

For each section: the result, and for anything that failed, the **event log** around it.

Three things are worth reporting even if everything passes:

1. **§2's `children=` lines.** That is the only evidence that will settle R-64, and this run is the only
   place it can be collected.
2. **§7's numbers.** They decide the paging question, and nothing else can.
3. **§6.4 — whether the release build ran.** It has never been executed. If it works, that is the last thing
   between this build and a Play upload.

And say plainly which sections you **skipped**, and why. An honest gap is planning information; a section
quietly marked green is a defect that ships.
