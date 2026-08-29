# Device test — build 0.9.14, against a local Audiobookshelf instance

**What this covers.** Everything merged since the 2026-08-24 session — PRs #42 through #55 — and the six
outstanding items that have always needed hardware. It is written to be run in order against a **local
Audiobookshelf instance you control**, because five of the tests need you to change something on the server
and watch the phone notice.

**The commands are in `scripts/device-test/`, one script per section and one shell-native version per
platform.** Use `.sh` from Bash/macOS/Linux and `.ps1` from PowerShell 7 on Windows. Each script prints what
it is doing, checks for an attached device before it starts, and uses the right package name —
`org.homebord.bookwave` and `org.homebord.bookwave.debug` are one suffix apart, and typing the wrong one
silently targets an app that may not even be installed. Nothing in that directory touches your server or
installs a tool; `scripts/check-local-environment.sh --install` is still the only script in this repository
that installs anything.

On Windows, open **PowerShell 7** (edition `Core`), then initialize that window once:

```powershell
Set-Location C:\Development\Bookwave
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
. .\scripts\Set-BookWavePath.ps1
```

Run a section with `& .\scripts\device-test\NN-name.ps1`. The PowerShell scripts use native
`Select-String`/`Select-Object`, so they do not require `grep`, `sed`, `tail`, Git Bash or WSL. When a test
needs a second window, the controlling script opens a fully initialized PowerShell 7 window itself. In
particular, `02-android-auto.ps1` opens `02-android-auto-dhu.ps1`, which resolves the installed DHU path,
forwards port 5277 and launches the executable.

### Where this run picks up

The 2026-08-27 run got to §5.1; the **2026-08-28/29 run finished the document**. It closed the two oldest
items in this project and left one defect open. That defect is what the next run is for.

| § | 2026-08-29 | Now |
| --- | --- | --- |
| 1 Controller security | Passed, 1.1–1.4 | Done |
| 2 Android Auto browse | **Passed** — `children=6` at the root, no browse failure | **R-66 confirmed on hardware.** Done, on the DHU. |
| 2.8 Tapping a book | **FAILED** — stayed on "Henter valget ditt" | **The one open defect. §2.8 is new and captures it (R-71).** |
| 2.9 A real car | **Passed, 2026-08-29** — reported as a whole rather than step by step | **R-75 closed: the first car result this project holds.** Per-step results were not itemised, so the wheel, the ignition cycle and the unplug are recorded as passing on the tester's word rather than from eight written answers. |
| 2.11 Audio output chooser | **Never run — the feature is new** | **New section.** Judged by ear; no log can confirm a route (R-77). |
| 3 Server history | Passed, 3.1–3.6 | Done |
| 4 Sleep timer | Passed, including the notification action | Done — `[3] "Sovetid 3 min"` settled the open question |
| 5.1 Multi-file resume | Passed | Done |
| 5.2 Degraded path | Not captured | Optional, and probably not provokable |
| 6 Release APK | **Passed** — built, signed, installed, launched, played offline | **First execution in the project's life.** Done. |
| 7 Benchmarks | Ran; numbers not retained | **Still open.** Re-run and record the four numbers. |
| 8 Process death and soak | Passed at all four checkpoints | Rebuffer count still not captured |
| 9–11 | Passed | Done |
| 12 About tab and event log | Passed, both locales | Done |
| 0.2 Install over the top | Recorded PASS — **but see below** | **Re-run. It did not test what R-68 broke (R-74).** |

**What that run established, and what it did not.**

- **R-66 is confirmed fixed.** Four `children=` lines and no `A browse request failed`. This was the fix with
  no test behind it, and a head unit was the only thing that could confirm it. Closed.
- **§6 ran.** R8's output had never been executed on any build. It now has: HTTPS sign-in, a 195-book
  library, a 15-file download played in aeroplane mode. The oldest open item in the project.
- **§0.2's PASS does not mean R-68 is fixed.** It tested a local rebuild installing over a local install,
  which passed before the fix too. §0.2 now tests the case that was actually broken. See R-74.
- **The `children=` lines came from the in-app log, not logcat** — logcat carried nothing at all on that
  device. The scripts now say so instead of presenting an empty result as an absence (R-70).

**Two things changed in the build since:**

- **The car selection path now logs what it was asked and what the player did with it.** It logged nothing
  before, which is why §2.8's defect has no diagnosis yet.
- **R-72 — a third off-thread player read**, in `onSetMediaItems`, the site R-66 did not touch. Fixed. It is
  *not* the cause of §2.8 — the missing `A browse request failed` rules that out — but it would have turned
  an unresolvable id into a permanent spinner.

**Record the outcome inline, next to each step.** A step that fails is worth more than a step skipped. Where
a step says *capture the log*, do it even if the step passed — several of these tests are measurements
rather than assertions, and the log is the whole result.

**How to get the log, every time it is asked for:** Settings → About → Diagnostics → **Open the event log** →
**Copy**. It now has search and filters (§12.2), so where a section quotes a log line you can paste part of
it into the search box instead of scrolling. The buffer is cleared when the app closes, so copy before you
kill the app.

---

## 0. Setup

### 0.1 Build and install

**Read §0.2 first if you have not set up a signing key.** It decides whether this install goes over the top
or costs you a sign-in, and it is a one-time setup.

```bash
./scripts/device-test/00-setup.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\00-setup.ps1
```

That runs the gate, installs the debug build, and prints the two things that decide whether the rest of this
document is measuring what you think it is: the version now on the phone, and the APK's signing certificate.
Longhand, if you would rather type it:

```bash
./scripts/check-local-environment.sh          # says what is missing; --install fixes it
./gradlew --stop                              # long sessions leak daemons (R-56)
./gradlew ktlintFormat && ./gradlew ktlintCheck
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true --no-build-cache --rerun-tasks
./gradlew :app:installDebug
```

PowerShell longhand:

```powershell
. .\scripts\Set-BookWavePath.ps1
.\gradlew.bat --stop
.\gradlew.bat ktlintFormat
.\gradlew.bat ktlintCheck
.\gradlew.bat verifyDebug '-Pshelfplayer.warningsAsErrors=true' --no-build-cache --rerun-tasks
.\gradlew.bat :app:installDebug
```

`--rerun-tasks` is not optional on a branch that changed a classpath. Gradle has considered test-compile
tasks up to date when only the classpath moved, and that let two stale test doubles pass locally and fail in
CI (R-31).

**Confirm the build you are testing.** Settings → About → **Version** should read `0.9.14-browse-and-genres`
(code 40). If it does not, you are testing a different build and every result below is misfiled — R-04 is
exactly that failure, nine builds long.

The debug build installs as `org.homebord.bookwave.debug`, so it sits alongside a release install without
clashing.

### 0.2 The one last uninstall (R-68)

Until 2026-08-27 the debug build declared no signing config, so AGP signed it with whatever key the building
machine happened to have generated. A CI runner is ephemeral and generates a fresh one every build, so no
device build ever installed over the last one: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, then an uninstall, then
signing in again — losing the passcode, the progress journal and every downloaded book, on **every build**.

Debug now uses the same supplied key as release. That fixes it going forward and changes the signature
**once**, so there is one uninstall left to pay:

1. Set the four `bookwave.signing.*` properties in `~/.gradle/gradle.properties`, generated once by the
   keytool command in `docs/release.md` § Signing. They must live **outside the checkout** and the build
   enforces it. Doing this now also sets up §6.
2. Uninstall whatever BookWave debug build is on the phone. This is the last time.
3. Run §0.1, sign in, and note the certificate digest `00-setup.sh` printed.
4. **Then prove it — and prove the right thing.** A second local `installDebug` is *not* the test:

   ```bash
   ./gradlew :app:installDebug   # necessary, but it passed before the fix too
   ```

   One machine that builds keeps its own generated keystore, so a local rebuild has always installed over
   a local install. R-68's own row says exactly that: *"Local rebuilds were fine, which is why this
   survived: the machine that built kept the file, and the machine that tested did not build."* The case
   that cost you a sign-in on every build was a **CI-built** APK meeting a locally-built one, and it is
   the case that has still never been run.

   So: open the **Build APK** workflow's latest run, download the `app-debug` artefact, and install it
   over your local build **without uninstalling**:

   ```bash
   adb install -r ~/Downloads/app-debug/app-debug.apk    # CI's APK over your local one
   ./gradlew :app:installDebug                           # and your local one back over CI's
   ```

**Expect: both directions succeed, and the sign-in, passcode and downloads survive both.** That is R-68
closed. If either fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the two machines are not producing the
same signature — the key is not reaching CI, or not reaching your build — and every later section in this
document will keep costing you a sign-in. Stop and say so.

**This only works if debug is signed with the shared key.** If you told the key helper *not* to sign debug
builds, the two machines cannot produce the same signature and this test cannot pass; that is the whole
subject of the step. `docs/risks.md` R-74 records why this replaced the old wording.

Without those properties the build still works and still installs; it just falls back to the generated key
and nothing is fixed. `bookwave.signing.debug=false` opts out on purpose. The `benchmark` module is untouched
and stays installable without a key, which is what §7 needs.

**Result (second install kept the sign-in?, 2026-08-29):** PASS. The tester reported that the replacement
install completed without losing the existing sign-in or local app state.

### 0.3 The server

You need **two accounts** on the local instance and **at least one multi-file book**.

1. In the Audiobookshelf web UI, confirm you have a book with **two or more audio files**. §5 and §8 need it;
   a single-file book cannot tell a per-file position from a per-book one.
2. Create a second user (Settings → Users) if you have not already. §10 needs it, and §1 is easier to read
   with two accounts on the phone.
3. Note the server's address as the phone will reach it. If it is `http://` rather than `https://`, cleartext
   is permitted **in the debug build only** (ADR-0009) — a release build will refuse it, which matters for §6.

### 0.4 What each section needs

| § | Bash / PowerShell script | Needs |
| --- | --- | --- |
| 0 | `00-setup.sh` / `00-setup.ps1` | The phone, and the signing properties from §0.2 |
| 1 | `01-controller-security.sh` / `01-controller-security.ps1` | The phone. §1.3 needs a third-party media controller app. |
| 2 | `02-android-auto.sh` / `02-android-auto.ps1` | Desktop Head Unit, or a real head unit. PowerShell opens `02-android-auto-dhu.ps1` in its own window. |
| 2.8 | `02-car-selection.sh` / `02-car-selection.ps1` | The same head unit. Run it **while connected** — it clears the log, waits for your tap, then dumps. |
| 3 | `03-server-history.sh` / `03-server-history.ps1` | The web client on a computer, signed in as the **same** account as the phone |
| 4 | `04-sleep-timer.sh` / `04-sleep-timer.ps1` | The phone, and about twelve minutes of it playing |
| 5 | `05-multifile-resume.sh` / `05-multifile-resume.ps1` | A multi-file book. §5.2 needs a crafted file — read it first, it may not be runnable |
| 6 | `06-release-apk.sh` / `06-create-signing-key.ps1` then `06-release-apk.ps1` | An upload key, and an `https://` server |
| 7 | `07-benchmarks.sh` / `07-benchmarks.ps1` | A USB cable and a charged phone; ~20 minutes |
| 8 | `08-process-death-soak.sh` / `08-process-death-soak.ps1` | Two hours, mostly unattended |
| 9–10 | `09-privacy-and-lock.sh` / `09-privacy-and-lock.ps1` | The phone, and the second account |
| 11 | `10-instrumented.sh` / `10-instrumented.ps1` | The phone |
| 12 | `11-about-and-event-log.sh` / `11-about-and-event-log.ps1` | The phone. Mostly on-screen; the script does the force-stop cycles. |

The number on a script is its **run order, not its section number** — `09-privacy-and-lock.sh` covers §9
and §10 together, which puts everything after it one behind.

---

## 1. The P1: an untrusted controller cannot clear the queue (PR #48)

**What was wrong.** `onConnect` narrowed the *session* command set for an untrusted caller and left Media3's
default *player* commands alone. Media3's default grants `COMMAND_SET_MEDIA_ITEM`, `COMMAND_CHANGE_MEDIA_ITEMS`,
`COMMAND_STOP` and `COMMAND_RELEASE` to everything that binds — so an outside app could not read the library
and could still stop your book and empty the queue. You found this at `PlaybackService.kt:959` and `:1041`;
both line references were right.

```bash
./scripts/device-test/01-controller-security.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\01-controller-security.ps1
```

It lists what is bound to the media session and drives transport from a trusted caller. It cannot do
§1.3 for you: `adb shell` holds `MEDIA_CONTENT_CONTROL`, so anything driven from it is the *trusted*
branch by definition.

### 1.1 The queue survives an unresolvable request

This is the half that is testable without another app, because the same code path handles it.

1. Play a book. Let it reach a position you can remember — say 3 minutes in.
2. From the notification, press pause, then play. Then skip forward and back.

**Expect:** ordinary behaviour, and the book is still loaded with its position intact. The old code replaced
an unresolvable request with `emptyList()`, which is what emptied the queue; it now returns the currently
loaded item at its current position instead.

**Result (2026-08-29):** PASS. Notification transport left the loaded book and its position intact.

### 1.2 The app's own controls are unaffected

3. In the app: seek by dragging the bar, change chapter from the chapter sheet, add a bookmark, set a sleep
   timer, and switch to a different book.

**Expect:** all of it works. The app connects under its own UID and gets full access; if any of this is
refused, the UID check is wrong and that is a serious regression.

**Result (2026-08-29):** PASS. Seeking, chapter selection, bookmarks, the sleep timer and changing books all
continued to work through the app's own controller.

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

**Result (2026-08-29):** PASS, per the tester's completed-device-test report. The untrusted controller kept
transport access without receiving the private browse tree or queue-management commands.

### 1.4 Trusted surfaces still work — the regression that matters

7. **Lock screen / notification:** play, pause, skip both ways, and the sleep-timer and bookmark actions.
8. **Assistant:** "Hey Google, pause" while a book is playing, then "resume".
9. **A Bluetooth headset or watch**, if you have one: play/pause from its button.

**Expect:** every one of these keeps working. These are the callers the platform reports as trusted, and the
whole design of the fix is that they are unaffected. Any of them failing is worse than the defect that was
fixed, so say so plainly if it happens.

**Result (2026-08-29):** PASS. The tested trusted surfaces continued to control playback normally.

---

## 2. Android Auto — the browse tree, now that it is fixed (R-66)

**Run this first, and it is now pass/fail rather than a diagnostic.**

**What was wrong.** `onGetChildren` and `onGetItem` called `nowPlaying()` *inside* `future { }`, whose block
runs on `applicationScope` — `Dispatchers.Default`. `nowPlaying()` reads `player.currentMediaItem` and
`player.currentPosition`, and ExoPlayer throws `IllegalStateException` for any property read off its
application thread. So every browse threw before it answered anything. Search kept working because
`onGetSearchResult` is the one browse callback that does not need the player — which is exactly the shape
your report described.

**Your report is what found it**, and specifically the one observation that killed the standing hypothesis:
warm start was *identically* empty. A cold-start profile race cannot produce that. R-64 was a wrong guess and
is closed as one; R-66 is the defect, and the `children=` diagnostic earned its keep by being **absent**.

**Why this section carries the whole verification.** No JVM test can construct a
`MediaSession.ControllerInfo`, so the session callbacks cannot be reached from the test tier at all. There is
no test behind this fix. §2 is it.

### 2.0 Which host you are on, and why it matters

**The Desktop Head Unit is not a car.** It is the same projected Android Auto stack driving a window on your
computer, which makes it the right tool for the browse tree and the wrong one for everything the car
contributes. Both report the same controller package — `com.google.android.projection.gearhead` — so the log
*cannot* tell them apart. **You have to record which one you used.**

| Host | What it is | Exercises | Cannot exercise |
| --- | --- | --- | --- |
| **Desktop Head Unit** | The projection stack, on your computer | Browse, search, selection, transport, artwork | The car's launcher, hard buttons, driving restrictions, ignition cycles |
| **A real car, projected** | The same stack, over USB or wireless | All of the above, in the conditions a listener meets | Nothing this app does |
| **Automotive OS** | Android *in* the car; a different host | `isAutomotiveController`, its own launcher | Untestable without such a car — say so rather than guessing |

§2.1–2.8 run on either. **§2.9 is the part only a car can do**, and it is the section to run if you only have
time for one — a defect that the DHU cannot see is a defect that ships.

```bash
./scripts/device-test/02-android-auto.sh
```

Windows PowerShell 7 (opens the dedicated DHU window for you):

```powershell
& .\scripts\device-test\02-android-auto.ps1
```

1. **Force-stop BookWave** so the next launch is cold — the script does it; by hand it is Settings → Apps →
   BookWave → Force stop.
2. Start the DHU: `$ANDROID_HOME/extras/google/auto/desktop-head-unit`, with Android Auto in developer mode
   and "Start head unit server" enabled. `adb forward tcp:5277 tcp:5277` if it does not connect on its own.
   The PowerShell controller opens a second window and runs the complete Windows sequence automatically.
   To run only that window yourself:

   ```powershell
   & .\scripts\device-test\02-android-auto-dhu.ps1
   ```
3. Open BookWave in the car and check the rows are present. **Selecting a book is §2.8**, which is its own
   section with its own script, because a populated browse tree that cannot open a book is still a failure
   and the 2026-08-28 run found exactly that.

**Expect: the browse root shows items** — the shelves, not zero. Then try search, which worked before and
must still.

4. **The measurement.** The script greps for it; by hand:

   ```bash
   adb logcat -d | grep "asked for a node's children"
   ```

   PowerShell equivalent (already performed by `02-android-auto.ps1` after it pauses for browsing):

   ```powershell
   adb logcat -d -t 5000 | Select-String -SimpleMatch "asked for a node's children" | Select-Object -Last 20
   ```

   **Expect: one `children=` line for each node the car asked for**, with a non-zero count for any node that
   has contents. If logcat is empty, use Settings → About → Diagnostics → **Open the event log** and search
   for `node's children`; the 2026-08-28 hardware run showed that these diagnostics reached the in-app log
   but not logcat. Absence from both logs is the failure shape.

5. **And the line that must now be gone:**

   ```bash
   adb logcat -d | grep "A browse request failed"
   ```

   PowerShell equivalent:

   ```powershell
   adb logcat -d -t 5000 | Select-String -SimpleMatch 'A browse request failed' | Select-Object -Last 10
   ```

   **Expect: nothing.** If one does appear it now carries `thrown=<ExceptionClass>` alongside
   `error=<code>` — before 2026-08-27 it said only `error=unknown`, and that missing word cost three device
   sessions. Paste the whole line if you see one; the class name is the answer.

6. **Then warm.** With the app already open and the shelf populated on the phone, connect the DHU again and
   count the root's items. Both cold and warm must work — one working and not the other is a different defect
   from the one that was fixed, and worth reporting as such.

7. **Then the things that passed last time**, because §1 changed the connection path and this fix changed two
   callbacks: transport (+30 s / −30 s should still read as exactly +30,005 ms / −30,000 ms in the log),
   artwork, reconnection after unplugging, and the resume tile.

**Result (2026-08-28, root cold — item count):** PASS for population: root returned 6 items; the visible
library rows were present. **Selection failed** — carried over to §2.8, which is where it is now tested.

**Result (root warm — item count):** PASS for population, although the exact count was not retained. After
playback was started on the phone, reconnecting DHU opened the active book and exposed working transport
controls.

**Result (search):** PASS in the tester's completed DHU pass; the exact query and result count were not
retained.

**The `children=` lines (in-app event log; logcat returned no matches):**

```text
21:41:42 I Playback A browser asked for a node's children parent=root children=6
21:41:45 I Playback A browser asked for a node's children parent=tab/continue children=13
21:41:45 I Playback A browser asked for a node's children parent=tab/recent children=20
21:41:45 I Playback A browser asked for a node's children parent=tab/again children=20
```

**Any `A browse request failed` line, in full:** None in the supplied event log.

**Result (transport, artwork, reconnect, resume tile):** PASS apart from the separately recorded cold book
selection failure. +30 s and −30 s controls worked during playback. Accepted `SeekCompleted` positions were
recorded at 2,431,440 → 2,403,231 ms and 2,439,575 → 2,411,774 ms; the tester subsequently confirmed the
remaining artwork, reconnect and resume-tile checks worked.

**Additional observation:** after transport testing, the event log recorded the same interval position
(2,413,542 ms) about every 30 seconds from 21:42:11 through 22:01:54. Confirm whether playback was paused
during that period before classifying this as redundant syncing or stalled progress.

### 2.8 Tapping a book — the half that population does not cover (R-71)

**This is the open defect, and this run is what settles it.** Your 2026-08-28 report is the first time
anyone saw the browse tree populate: six items at the root, thirteen and twenty behind the tabs. And a book
that would not open — the head unit stayed on *"Henter valget ditt"* and nothing loaded.

**Two explanations were checked against your own log and both are wrong.** A throw in `onSetMediaItems`
would have logged `A browse request failed`, which `future` writes for *every* session callback, and your
log carried none. A withheld `COMMAND_SET_MEDIA_ITEM` from PR #48 is impossible for a controller that
browsed, because `mayBrowse` and the player-command list read the same `accessFor`. So the car was trusted,
the book resolved, and it still did not play.

**Why there is no third hypothesis: a car tap that worked logged nothing at all.** `onSetMediaItems` had no
log line, `openQueue` logged only failures, and the player never said what state it was in. A queue that was
set and never prepared and a queue that was never set produced identical logs. That is R-64 repeating — the
evidence existed and was discarded where it would have been read — so this build adds the two lines that
separate them, and this section captures them.

```bash
./scripts/device-test/02-car-selection.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\02-car-selection.ps1
```

Run it **with the head unit already connected**. It clears the log buffer, waits for you, and dumps only
what your tap produced.

1. Start the script. When it pauses, **tap a book in the car and wait up to 30 seconds** — whether it ever
   loads is the measurement, so leaving early records the wrong answer.
2. Press Enter. Read the two lines it prints.

**What the answer means:**

**Read `resolved=`, not `handedBack=`.** When nothing resolves and a book is already playing, this service
deliberately hands that book back rather than emptying the queue — so `handedBack=1` appears for a request
that resolved *nothing*. Reading the count alone would send you to the player for a defect in resolution,
which is the confusion this logging exists to end.

| Line | Reading |
| --- | --- |
| `resolved=false` | This service could not turn your tap into a book. The defect is in resolution, and `kind=` says which id form the car sent — whatever `handedBack` says. |
| `resolved=true` then `state=buffering` → `state=ready` | The service and the player both did their jobs. The defect is in the car's own presentation. |
| `resolved=true` and **no** `The player changed state` line | The queue was answered and never reached the player, or was never prepared. This is the most likely shape. |
| `resolved=true` then `state=idle` only | The player took it and refused to prepare — look for `A playback error` next. |
| No `A controller asked to set what plays` line at all | **Read the script's own verdict before concluding anything.** If it said logcat is carrying the app, this is a result and not a failed measurement — the tap never reached the service, and the defect is upstream, in discovery or the browse item's own flags. If it said logcat holds no `ShelfPlayer` lines at all, the absence locates nothing, and the scripts deliberately decline to name a layer: go to the in-app event log (R-70, R-71). |

`branch=` names which of the three routes answered: `browse` for a tap, `spoken` for a voice query,
`passthrough` for the app's own pre-resolved items.

3. **If logcat came back empty, the script will say so and it is not a failure of the app** — that happened
   on your last run (R-70). Use Settings → About → Diagnostics → **Open the event log**, search
   `asked to set`, then `player changed state`, and copy both.

**Result (did the book open?):**
**The `asked to set what plays` line, in full:**
**The `player changed state` lines:**
**Anything else in the log within a few seconds of the tap:**

### 2.9 A real car, not the Desktop Head Unit

**The section that matters most and has never been run.** Every car result this project holds came from the
DHU. That covers the browse tree and it covers selection, and it covers none of the things below — each of
which is a way this app can fail for a listener while every recorded test stays green.

```bash
./scripts/device-test/02-real-car.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\02-real-car.ps1
```

Run it in the car with the engine on. It records the host, then walks the seven things the DHU cannot
reach. Do it parked.

1. **Connect, and confirm which host answered.** The script asks you to **disconnect first** if the phone
   is already paired with the car, then clears the log, then asks you to connect. Both halves matter: the
   clear is what stops a DHU session from earlier in the day answering for the car in front of you, and it
   deletes the connection line, so the connection has to happen after it. The script then prints the
   `A car connected to the media session` line.

   **Expect:** `controller=com.google.android.projection.gearhead`. Anything else is Automotive OS or a
   vendor host and is worth reporting on its own — this app has never seen one.

2. **The car's own launcher.** Find BookWave in the car's app list.

   **Expect:** it is there, with the right name and icon. The DHU has its own launcher and proves nothing
   about this. If the app is missing here but present in the DHU, that is a *discovery* defect and
   `CarReadiness` in Settings → About → This device is the next thing to read.

3. **Browse and select, in the car.** Repeat §2's counts and §2.8's tap, here.

   **Expect:** the same answers as the DHU gave. **A difference between the two is the finding** — record
   both numbers, not just this one.

4. **The steering-wheel and hard buttons.** Next, previous, play/pause from the wheel; the volume knob.

   **Expect:** all of it. These reach the session as media-button events and never touch the DHU, so nothing
   recorded so far says whether they work. `docs/risks.md` R-71's fix and PR #48's command narrowing both
   touched this path.

   **Do not touch the phone during this step.** The log records that play/pause reached the session, and it
   cannot record who asked: Media3 hands a controller's `play()` to the local player as `setPlayWhenReady`,
   which reports `reason=userRequest` — the same reason the phone's own UI produces. A cleared window and
   your hands off the phone are the whole of the attribution (R-76). Volume and next/previous cannot be
   witnessed from the log at all — volume changes no playback state, and next/previous are no-ops on the
   one-item queue a car selection builds — so judge those two by ear.

5. **Driving restrictions.** With the car in motion — **a passenger drives, or use a rolling road; do not do
   this yourself** — open the browse tree.

   **Expect:** the car truncates long lists and hides some text. That is the host's doing, not a defect. What
   *would* be a defect is a list that becomes unusable, or a row whose label is now meaningless once
   truncated. If you cannot do this safely, skip it and say so — an untested restriction is a better outcome
   than an accident.

6. **Ignition off, ignition on.** Stop the engine, wait for the head unit to power down, restart it.

   **Expect:** BookWave comes back, and the resume tile offers the book you were on at the position you left.
   The DHU cannot produce this: closing its window is not a power cycle, and the phone never sees the USB
   drop.

7. **Unplug while playing.** **Read the position off the phone and write it down first** — the script
   asks for it before it lets you touch the cable, because a position recorded after the fact proves
   nothing. Then pull the cable mid-book.

   **Expect:** **progress is not lost.** Two outcomes are both correct for the audio itself, and which one
   you get depends on the car:

   - Playback **continues on the phone** — the disconnect was not reported as a route change.
   - Playback **pauses** — the disconnect came through as *audio becoming noisy*, and the player is
     deliberately configured to pause on that (`PlayerFactory`, PRODUCT_SPEC PLAY-002: audio never moves to
     the phone's own speaker when the thing you were listening on goes away). **A pause here is the
     requirement working, not a defect**, and it must not be "fixed" by turning that handling off.

   What is **not** acceptable in either case is audio coming out of the phone's speaker, or the position
   being lost. Then plug back in and confirm the car picks the same book up where it now is: compare
   against the position you wrote down, on the phone **and** in the web client. The `The server accepted a
   position` line cannot stand in for that, because the sync ticker writes one about every 30 seconds
   regardless.

8. **Voice, if the car has it.** "Hey Google, play <a book you own>".

   **Expect:** it plays. That path is `onSetMediaItems` with a search query rather than a media id — a
   different branch from a tap, and one only a real microphone reaches.

**Result (host / controller package):**
**Result (app present in the car's launcher):**
**Result (browse counts here vs the DHU's):**
**Result (steering-wheel buttons):**
**Result (driving restrictions, or skipped and why):**
**Result (ignition cycle and the resume tile):**
**Result (unplug while playing):**
**Result (voice):**

---

### 2.11 The audio output chooser (PLAY-002, ADR-0027)

**You need two outputs connected at once** — the phone's own speaker plus a Bluetooth headset is the easy
pair. With one output the control is deliberately hidden, on the phone and in the car alike: a menu offering
the thing already happening answers nothing.

**Nothing here can be checked from a log.** `setPreferredAudioDevice` is a *preference*, and no Android API
reports which output media is actually using (R-77). Every step below is judged **by ear**. That is not a
gap in the script; it is the honest limit of what any app can know about its own routing.

#### On the phone

1. Start a book, then connect a Bluetooth headset so two outputs are live.
2. Open the full player. A **Bluetooth icon appears in the top-right**, beside the sleep-timer readout.

   **Expect:** it is not there with only one output connected. Disconnect the headset and confirm it
   disappears; reconnect and confirm it returns.

3. Tap it. The menu lists **Automatic** first, then every connected output, with a tick on the current one.

   **Expect:** *Automatic* is ticked initially — **even if sound is already coming from the headset**. The
   tick means "what the app asked for", and it has asked for nothing yet. This is deliberate (ADR-0027).

4. Choose the output sound is *not* currently coming from. Listen.

   **Expect:** audio moves, within a second or two. The tick moves to the row you picked.

5. Choose **Automatic** again.

   **Expect:** routing returns to whatever the system would do on its own.

6. With a non-Automatic output selected, **disconnect that device**.

   **Expect:** the selection falls back to *Automatic* by itself, and the app does not keep asking for a
   device that is gone. Playback pausing here is correct — that is PLAY-002's becoming-noisy handling, not a
   defect (see §2.9 step 7).

**Result (icon appears only with two outputs):**
**Result (audio actually moves — by ear):**
**Result (Automatic restores system routing):**
**Result (selection clears when the device disconnects):**

#### In the car

**There is no output button on the Android Auto player screen, and there cannot be one** (R-78). No API lets
an app open a browse node from a custom action. The list is a **browse tab**.

7. With the car connected *and* a Bluetooth headset paired to the phone, swipe from the player to the browse
   screen. An **Audio output** tab sits after Chapters and History.

   **Expect:** the tab is present only when more than one output is connected.

8. Open it. The rows are Automatic plus each connected output; the one in use reads *"… — playing here"*.

9. Tap a row.

   **Expect:** the screen shows a single line confirming the choice, **and the book keeps playing without a
   gap**. A rebuffer here would mean the rows have become playable rather than browsable, which is the whole
   point of ADR-0027 decision 3.

**Result (tab present with two outputs):**
**Result (choosing a row does not interrupt playback):**
**Result (the confirmation names the output chosen):**

---

---

## 3. The history pane shows what the server recorded (PR #52)

**What is new.** The pane's remote rows used to be *derived* — a diff of stored progress against a sync —
which could not see a book this phone had never played, and collapsed two sessions between syncs into one.
The app now reads `GET /api/me/listening-sessions`, the server's own record, when the pane opens.

This is the test the local instance is for.

```bash
./scripts/device-test/03-server-history.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\03-server-history.ps1
```

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

**Result (2026-08-29):** PASS. A server-only listening session appeared as **"Listened on another device"**
for a book that had not previously been played on this phone.

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

**Result (2026-08-29):** PASS. The import completed and the inspected diagnostic output did not expose a
title, author, device name or server hostname. Exact `fetched`/`imported` counts were not retained.

### 3.3 Opening the pane twice does not double the row

6. Close the history pane and open it again. Then again.

**Expect:** still **one** row for that session, not two or three. The row's key is the server's own session
id, so re-importing is idempotent. This is the check that would catch the mistake a fresh UUID would make.

**Result (2026-08-29):** PASS. Reopening the history pane did not duplicate the imported row.

### 3.4 This phone's own listening is not duplicated

7. On the **phone**, play the same book for 30 seconds. Pause. Wait ~40 seconds so the session syncs and
   closes.
8. Open the history pane again.

**Expect:** the phone's own listening appears as the ordinary **"Played"** / **"Paused"** rows the player
writes. It must **not** also appear as a second "Listened on another device" row. The app tells its own
sessions apart by the per-install device id it sends when it opens a session.

**Result (2026-08-29):** PASS. Listening performed on this phone appeared as local playback history and was
not duplicated as a remote-device row.

### 3.5 It survives losing the network

9. With the imported row on screen, turn **aeroplane mode on**. Close the pane. Open it again.

**Expect:** the row is **still there**. This is the reason the rows are persisted rather than merged at read
time — offline is exactly when somebody wonders where their position went. The log will show:

```
Could not read the server's listening sessions; the stored history stands   error=Network
```

10. Turn aeroplane mode off.

**Result (2026-08-29):** PASS. Imported history remained visible while the phone was offline.

### 3.6 A book played only on a *third* device

If you have a second phone or a tablet, listen there instead of the web client and repeat 3.1. Not required,
but it is the only way to see two different remote devices in one pane.

**Result (2026-08-29):** PASS, per the tester's report for the remaining checks. No per-device identifiers
or raw import counts were retained in this report.

---

## 4. Sleep-timer rows (already built — confirming, not testing new code)

You asked for local events to show the sleep timer starting and the sleep timer stopping the book. **That
already worked** before PR #52 and nothing in it changed that half. This section confirms it reads the way
you expected, because it is the part I did not touch.

```bash
./scripts/device-test/04-sleep-timer.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\04-sleep-timer.ps1
```

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

**Result (2026-08-29):** PASS. Start, extension, expiry and rewind rows appeared as expected; selecting the
ended-timer row from the player returned playback to the recorded position, while the book-screen copy
remained read-only.

8. **The notification's sleep-timer action.** Your last report noted it was not visible, unconfirmed. Settle
   it: with a timer **running**, pull the shade down and expand BookWave's notification fully.

**Expect:** the extend action is there *while a timer is active*, and not otherwise — it is added and removed
with the timer, so a shade opened before you set one will not show it. Android also drops actions it has no
room for in the collapsed form, which is why expanding matters before concluding anything.

If it is genuinely absent with a timer running and the notification expanded, that is a defect worth its own
report. The script dumps the live notification's actions, which answers it from the system's own record
rather than from what the shade chose to draw.

**Result (notification action, timer running and expanded, 2026-08-28):** PASS. Android reported four
actions, including `[3] "Sovetid 3 min"`. This is the localized sleep-timer action: its label carries the
remaining time and its command extends the active timer. The original PowerShell script printed only the
first 12 lines of the notification record, before the `actions={...}` block; it now extracts and prints the
actual action titles.

---

## 5. The zero-duration fallback (R-61, PR #51) — read before attempting

**Be aware this may not be runnable, and that is an honest result.** The fix guards a path nobody has ever
observed: a server reporting a track duration of zero. No capture against 2.36.0 has produced one. If you
cannot provoke it, record "not provokable" and move on — do not manufacture a pass.

```bash
./scripts/device-test/05-multifile-resume.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\05-multifile-resume.ps1
```

### 5.1 The regression check, which you *can* do

1. Play a **multi-file** book to a position well into the second or third file — say 8 minutes into a book
   whose first file is 5 minutes.
2. Force-stop the app. Reopen it and resume the book.

**Expect:** it resumes at **8 minutes of the book**, not 3 minutes into file two and not at the start. The
seek bar's total should be the whole book's length, and the notification's clock should count the book, not
the file.

3. Check the server agrees: in the web client, open the book and read its progress.

**Expect:** the same position, within a few seconds.

**Result (2026-08-29):** PASS. The multi-file book resumed at its whole-book position and the server position
agreed within the expected tolerance. Exact positions were not retained.

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

**Result (2026-08-29):** NOT SEPARATELY CAPTURED. The tester reported all remaining runnable checks working,
but no crafted multi-file book with multiple unknown track lengths or degraded-path log line was supplied.

---

## 6. The release APK — sign it, install it, launch it (PR #50)

**The item that has been open longest.** R8 and resource shrinking run in CI on every push and their output
has **never been executed**. It could not be: the release variant declared no signing config, so the APK was
unsigned and uninstallable. That is now fixable from your side without any key material entering the repo.

### 6.1 The key — already done, if you did §0.2

The same four `bookwave.signing.*` properties supply the upload key, so if you set them up for §0.2 there
is nothing to do here. Unless `bookwave.signing.debug=false`, the build uses that key for both release and
debug. If you skipped §0.2, `docs/release.md` § Signing explains where the properties go:
`~/.gradle/gradle.properties`, **outside the checkout**, which the build enforces.

On Windows, the guided PowerShell helper creates the upload key outside the checkout, asks for its
passwords without displaying them, and writes the four properties for you:

```powershell
& .\scripts\device-test\06-create-signing-key.ps1
```

It never overwrites an existing key; if the chosen file exists, it can verify and configure that key after
you explicitly choose to use it.

**Answer yes when it asks whether to sign debug builds too.** It costs one uninstall, once — the debug
signature genuinely changes on the first build that uses a key, and that erases the current debug profile
and downloads. Declining is what an earlier version of this document advised, and it was wrong: without the
shared key your machine and CI cannot produce the same signature, so §0.2 cannot pass and R-68 stays open
regardless of what the run records. Pay the uninstall at the start of a session, deliberately, rather than
at the start of every session by accident. Back up the generated key and passwords somewhere a lost
computer does not take with it. The helper stops any running Gradle daemon after writing the properties;
otherwise a daemon started during a partial setup can keep reporting the other three values as missing.
The release script also checks both the terminal's `GRADLE_USER_HOME` and the normal Windows user Gradle
directory, then uses whichever contains all four values. This matters when the repository-local toolchain
has given the terminal a separate Gradle home.

It is an **upload** key, not the key end users verify against — ADR-0024 chose Play App Signing, so Google
holds that one and losing this is recoverable by asking Play to reset it. Keep the passwords somewhere a lost
laptop does not take with them.

### 6.2 Build, verify, install, launch

```bash
./scripts/device-test/06-release-apk.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\06-release-apk.ps1
```

It builds, refuses to go on if the APK came out unsigned, then installs and launches it. Longhand:

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

PowerShell longhand (the script resolves `apksigner.exe` from your Android SDK automatically):

```powershell
.\gradlew.bat :app:assembleRelease
$BuildTools = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $BuildTools.FullName 'apksigner.bat') verify --print-certs .\app\build\outputs\apk\release\app-release.apk
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

**Expect:** `apksigner` prints `Verifies` and `Verified using v2 scheme (APK Signature Scheme v2): true`.
v1 will read `false` and that is correct — v2 is verified from API 24 and this app's `minSdk` is 26.

**And if the key is missing, the build now says so while it runs** (R-69). `assembleRelease` used to produce
an unsigned APK in silence and let the failure surface minutes later on a phone as an install error naming
the package rather than the signature. It now warns, naming the four properties.

### 6.3 The bit that has never happened: run it

The release build is a **different application id** (`org.homebord.bookwave`, no `.debug` suffix), so it is a
fresh install with no data. Sign in and then exercise the paths R8 is most likely to have broken:

1. **Sign in** to the local server. If your server is `http://`, expect this to **fail** — cleartext is
   debug-only by design (ADR-0009). Use `https://`, or note it and test the rest against a TLS endpoint.
2. Open a book and **play it**. Then seek, change chapter, and pause.
3. **Download** a book and play it with aeroplane mode on.
4. Open **Settings → About** and confirm the version, the diagnostics screen, and the event log's filters
   render — §12 is new code and R8 has never seen it.
5. Set a **sleep timer** and a **bookmark**.
6. Open the **history pane**.
7. Change the **language** and come back. §12.3's fix is in the release build too.

**What you are looking for:** anything that works in the debug build and not here. R8 removing a class that
only reflection or serialization reaches is the classic failure, and `kotlinx.serialization` and Room are
both in that category. A crash here is a release blocker; note the exact screen.

8. If it does crash, keep the mapping — `app/build/outputs/mapping/release/mapping.txt` — because a release
   stack trace is unreadable without it.

**Result (release build/install, 2026-08-29):** PASS. `assembleRelease` completed successfully; `apksigner`
reported certificate SHA-256 `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`; and
`adb install -r` returned `Success`. The first automatic launch attempt did not reach ADB because PowerShell
bound `monkey -p` to its own `-ProgressAction` common parameter. The shared argument-forwarding wrapper was
corrected; the retry injected one launcher event and `pidof org.homebord.bookwave` returned a live process,
so release installation and launch both pass.

**Result (release functional checks 1–7, 2026-08-29):** PASS. HTTPS sign-in completed against server
2.36.0; the realtime connection authenticated; one permitted library refreshed with 195 books; a 15-file
book downloaded completely; and that download played in aeroplane mode with a 15-track, 14,134,466 ms
timeline. About/Diagnostics/Event log opened in the minified build, and its copied lines kept the hostname,
profile, server and item identifiers redacted.

The initial catalogue expansion issued one `GET /api/items/*` per book and completed in approximately 15
seconds. That is the measured, documented LIB-001 N+1: the list endpoint does not carry the tracks, chapters
and structured metadata stored in the local snapshot. It is a performance cost, not an unexpected release
regression. While aeroplane mode was active, `The realtime connection failed httpStatus=0` repeated with an
increasing retry interval; PRODUCT_SPEC 14.3 deliberately reconnects indefinitely with capped backoff. The
important offline lines were `A downloaded book is playing without the server` and
`hasServerSession=false`, both correct. The tester subsequently confirmed the event-log search/filter/reset
behaviour, bookmark and sleep-timer actions, History, language switching and language persistence all worked
in the release build.

### 6.4 Three build refusals, if you want to confirm the guard rails

Cheap, and they prove key material cannot slip into the repository:

```bash
./gradlew :app:assembleRelease -Pbookwave.signing.storeFile=$PWD/inside.jks   # refused: inside the repository
./gradlew :app:assembleRelease -Pbookwave.signing.storeFile=/nope/x.jks       # refused: not found
# and with only one of the four properties set: refused, naming the three missing
```

PowerShell 7:

```powershell
.\gradlew.bat :app:assembleRelease "-Pbookwave.signing.storeFile=$PWD\inside.jks"   # refused: inside the repository
.\gradlew.bat :app:assembleRelease '-Pbookwave.signing.storeFile=C:\nope\x.jks'     # refused: not found
# And with only one of the four properties set: refused, naming the three missing.
```

**Result (2026-08-29):** PASS, per the tester's report. The individual refusal outputs were not retained.

---

## 7. The four 17.3 numbers and the baseline profile (task #54)

`docs/benchmark.md` has the full procedure and the results table to fill in — **use that document, not this
one**, for the details. What belongs here is the running order:

```bash
./scripts/device-test/07-benchmarks.sh     # or: ./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\07-benchmarks.ps1
# Or: .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest
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

**Result — the four numbers:** RUN COMPLETED according to the tester, but the raw measurements were not
supplied and the Results table in `docs/benchmark.md` remains blank. No threshold claim is made here.

**Result — memory:** NOT CAPTURED in the report; the required heap and RSS figures were not supplied.

**Result — baseline profile committed:** NOT COMPLETED. `app/src/main/baseline-prof.txt` is absent from the
working tree, so this pull request does not claim or ship a generated profile.

---

## 8. Process death and the two-hour soak (PRODUCT_SPEC 25)

```bash
./scripts/device-test/08-process-death-soak.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\08-process-death-soak.ps1
```

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

**Result (position before / after / server, 2026-08-29):** PASS. Position survived process death and agreed
with the server within the expected tolerance; the three exact values were not retained.

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

**Result (30 / 60 / 90 / 120 min, 2026-08-29):** PASS at all four checkpoints, per the tester's report.

**Rebuffer count:** NOT CAPTURED as a number.

**Anything repeated in the log:** No soak failure was reported; the raw final log was not retained here.

---

## 9. The app-switcher thumbnail (R-62, PR #47)

**Only meaningful on Android 13 or newer.** Below API 33 the thumbnail is deliberately still there —
`setRecentsScreenshotEnabled` did not exist before then, and ADR-0026 declined `FLAG_SECURE`, which would
have blocked every screenshot for every user to solve a narrow problem for some.

`./scripts/device-test/09-privacy-and-lock.sh` (Bash) or
`& .\scripts\device-test\09-privacy-and-lock.ps1` (PowerShell 7) reads the API level off the phone and
walks §9 and §10 together, since both are one device and neither has much to type.

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

**Result (Android version / thumbnail / screenshot, 2026-08-29):** PASS. The app-switcher protected the
library while an ordinary screenshot remained available. The Android version was not supplied.

---

## 10. A passcode survives ordinary reauthentication (R-44, PR #46)

**What was wrong.** Any sign-in cleared the profile's passcode, including a routine reauthentication the user
did not initiate. Only explicit locked-profile recovery may clear it now.

### 10.1 Set a passcode

1. Settings → **Passcode lock** → turn on **Require a passcode for this account** → set a 6–12 digit code
   that is not a run or a repeat. Save.
2. Force-stop the app, reopen it.

**Expect:** the lock curtain, **"This account is locked"**.

**Result (2026-08-29):** PASS. The lock curtain appeared again after force-stop and restart.

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

**Result (2026-08-29):** PASS. Ordinary reauthentication retained the configured passcode.

### 10.3 Only explicit recovery clears it

7. Force-stop, reopen, and at the curtain tap **"Forgotten your passcode?"**.

**Expect:** a warning that signing in clears the passcode, and a button reading **"Sign in and clear the
passcode"** — not a bare "Sign in". The wording has to say what it will do.

8. Enter the account password and submit.

**Expect:** the app opens, and force-stopping and reopening now goes **straight in** — no curtain. The
passcode is gone, because you asked for that.

**Result (2026-08-29):** PASS. Explicit forgotten-passcode recovery warned that it would clear the passcode,
then cleared it and allowed subsequent launches without the curtain.

### 10.4 The lockout, briefly

9. Set a passcode again. Force-stop, reopen, and enter a **wrong** code repeatedly.

**Expect:** it counts down remaining tries, then makes you wait, and the wait grows. After the final failure
the passcode is refused permanently and only signing in again clears it. You do not need to exhaust it —
confirm the countdown and the first wait appear.

**Result (2026-08-29):** PASS. Wrong entries showed the remaining-attempt countdown and first timed wait.

---

## 11. The instrumented tier on hardware

CI has no emulator, so this suite never runs there. It passed 27/27 on 2026-08-23; it is worth re-running
because PR #46 changed the profile-lock code around it.

```bash
./scripts/device-test/10-instrumented.sh   # or: ./gradlew :core:datastore:connectedDebugAndroidTest
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\10-instrumented.ps1
# Or: .\gradlew.bat :core:datastore:connectedDebugAndroidTest
```

**Expect:** all tests pass. This is the only tier that exercises the profile lock's real AndroidKeyStore
storage; a JVM test cannot.

**Result (count passed / failed, 2026-08-29):** PASS. The tester reported the hardware instrumented task
completed successfully; the console's exact passed/failed count was not supplied.

---

## 12. The About tab and the event log (PR #55)

New since your last run, and never seen on a device.

```bash
./scripts/device-test/11-about-and-event-log.sh
```

Windows PowerShell 7:

```powershell
& .\scripts\device-test\11-about-and-event-log.ps1
```

### 12.1 The About tab has no leftovers

1. Settings → **About**.

**Expect: no "Checks after wave 3" section.** It was Phase 2 scaffolding — eight sync checks, a notification
check and a car check, with pass/fail verdicts — a manual QA checklist living inside a shipped app, naming
its phase in a string a user could read. It is gone, and this document is where that checklist lives now.

**Expect:** the section that used to be **"Testing"** is now **"This device"**, and describes readings about
this phone and this server rather than "acceptance cases". And the blurb no longer claims to be "a work in
progress" with "hardware verification and some compatibility and automation gaps" — a developer's status note
on a user's screen, stale between every phase. What a user needs there is the version, which is the row above.

2. Switch to **Norsk bokmål** (Settings → Appearance → Language) and look again.

**Expect:** both changes are in that locale too, and nothing reads as a raw string id.

**Result (2026-08-29):** PASS in English and Norsk bokmål. The obsolete acceptance-check content was absent,
the section read **"This device"**, and no raw string identifier was reported.

### 12.2 The event log's search and filters

3. Play something first so there are a few hundred lines to work with. Then Settings → About → Diagnostics →
   **Open the event log**.

4. **Search.** Type part of a message — `position`, or `download`. Search covers the message *and* the area.

**Expect:** the list narrows as you type, and the header changes from *"N events since the app started"* to
**"Showing 12 of 400"**. That second count is the point: a bare number over a narrowed list sends somebody
hunting for a line that is right there.

5. **Level.** Tap the **Level** chips — Verbose, Debug, Info, Warn, Error.

**Expect:** only the levels actually present are offered, in severity order. A chip that could only ever
yield an empty list is not drawn.

6. **Area.** Tap the **Area** chips — Playback, Sync, Auth, Download, Network, Database, Management,
   Settings, App.

**Expect:** again only the areas present, in the order they first appeared — and they must **not** re-sort as
new lines arrive. A chip moving out from under your finger while you reach for it is the specific thing that
ordering avoids, so watch for it while playback is still logging.

7. **All three at once.** Search text, one level, one area.

**Expect:** an **AND** — everything shown matches all three — and the order is untouched: newest first,
always.

8. **The two empty states.** Search for something that cannot match, `zzzz`.

**Expect:** **"No events match this search or filter. Reset to see everything."** — *not* "Nothing has been
recorded yet." The two look alike and call for opposite reactions.

9. **Reset** is disabled when nothing is narrowed, and enabled the moment anything is.

10. **Copy** with a filter on, and paste it somewhere.

**Expect:** whatever it copies is consistent with what the screen showed. Say which it did when you report,
because a log pasted from a filtered view without saying so is a log that misleads.

11. **Nothing private.** With playback and sync lines on screen, read them: no book title, no author, no
device name, no server hostname (PRODUCT_SPEC 14.5).

**Result (2026-08-29):** PASS. Search, level and area filters, combined AND behaviour, empty states, Reset
and Copy worked. The supplied release log also confirmed that private identifiers remained redacted.

### 12.3 Changing the language no longer bricks the app (R-67)

Be deliberate here, because until 2026-08-27 failing this meant reinstalling.

**What was wrong.** `AppLocale` handed Compose the `ContextImpl` that `createConfigurationContext` returns,
and `HiltViewModelFactory` finds the Activity by walking `ContextWrapper.baseContext` — so the walk could not
take one step and threw at the first `hiltViewModel()` inside the wrapper. The choice is persisted, so every
launch afterwards rebuilt the same broken context before anything could be tapped, and changing the language
in Android's own settings did not help because the app reads its own stored value.

12. Settings → Appearance → **Language** → **Norsk bokmål**.

**Expect:** the UI changes language, and nothing crashes.

13. Navigate: shelf, a library, a book, the player, back to Settings. Open the history pane, and the event
    log.

**Expect:** all of it works. Every one of those screens takes its view model from `hiltViewModel()`, which is
the call that used to throw.

14. **Force-stop and reopen. Twice.**

**Expect:** it opens, in Norwegian. The persistent half of this defect was worse than the first crash, and it
is the half a single successful language change would not catch.

15. Switch back to **English**, then to **Follow the system**, force-stopping between each.

**Expect:** both work, and the system option follows whatever Android is set to.

**Result (2026-08-29):** PASS. Norsk bokmål, English and Follow the system survived navigation and repeated
force-stop/reopen cycles without the prior `hiltViewModel()` crash.

---

## 13. What to send back

For each section: the result, and for anything that failed, the **event log** around it.

Four things are worth reporting even if everything passes:

1. **§2's `children=` lines, and the absence of any `A browse request failed`.** The car fix has no test
   behind it and nothing else can confirm it. Three device sessions went into finding that defect; one
   confirms it is gone.
2. **§6.3 — whether the release build ran.** R8's output has never been executed, on any build, ever.
3. **§7's numbers.** They decide the paging question ADR-0025 deliberately left open, and only your hardware
   can produce them.
4. **§0.2 step 4 — whether the second install kept your sign-in.** If it did, the tax R-68 charged on every
   build is paid off. If it did not, the fix is not reaching your machine and every later section still costs
   a sign-in.

And say plainly which sections you **skipped**, and why. An honest gap is planning information; a section
quietly marked green is a defect that ships.
