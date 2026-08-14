# Phase 2 — every gap, and what closes it

Written 2026-08-13 after the second device run of wave 5, at the owner's request: *"Find all the gaps of
the phase 2 and begin to fix the gaps so we can close phase 2 after testing."*

This is the checklist Phase 2 is judged by. Three sources feed it: PRODUCT_SPEC's PLAY and ROUTE
requirements, the four exit criteria the spec names for the phase, and the defects two device runs have
found. Everything is listed, including the parts that are done, because a gap list that only shows gaps
cannot be used to decide whether the phase is finished.

Legend: ✅ built · ⚠️ partial · ❌ absent · 🔬 needs hardware

## The requirements

### PLAY-001 Native background playback

| Criterion | State |
| --- | --- |
| Playback runs in a `MediaLibraryService` | ✅ |
| Closing the activity does not stop playback | ✅ |
| Lock screen, notification, Bluetooth, wired headset, system controls | ✅ |
| **Android Auto** can play, pause, seek, skip, stop | ✅ **confirmed in a car, 2026-08-14** — the app appears, browses and plays. What it *shows* is wrong (defect 21) but that is a different criterion |
| Notification shows cover, title, author, progress | ✅ — the progress became the *book's* in wave 5 |
| Notification has play/pause, **backward and forward** | ✅ — wave 5 replaced skip-to-previous/next, which restarted the book |
| Foreground-service type and permissions declared | ✅ |
| Only one local audio media session | ✅ structurally: the module is the only one that can name the types |
| **A process restart restores the last playable item, paused** | ✅ **confirmed in a car and on a headset, 2026-08-14** — `onPlaybackResumption` returns the last unfinished book at its stored position, and since 0.8.0 a different book can then replace it |

Both closed in the wave 5 closeout. Neither has been seen in a car.

### PLAY-002 Audio focus and route handling

| Criterion | State |
| --- | --- |
| Focus requested through Media3 | ✅ |
| Transient loss pauses (speech content type), permanent loss pauses | ✅ |
| Calls and navigation prompts behave | 🔬 needs a phone call |
| Headphones out pauses immediately | ✅ `setHandleAudioBecomingNoisy` |
| Never moves to the phone speaker | ✅ same mechanism |
| **Transient loss pauses *or ducks* according to setting** | ❌ there is no setting; the app always pauses |
| **Reconnecting a route follows the configured per-device policy** | ❌ that is ROUTE-002, unbuilt |

### PLAY-003 Playback queue and tracks

| Criterion | State |
| --- | --- |
| Multi-file books play in server order | ✅ |
| Seeking across boundaries preserves global position | ✅ — and since ADR-0016 there is no conversion to get wrong |
| Excluded tracks are not played | ✅ dropped from the source |
| Chapter navigation independent of file boundaries | ✅ |
| Missing local part / unreadable file / repair | — Phase 3 (no local files exist yet) |
| No automatic cellular fallback unless policy allows | — Phase 3 |

Complete for a streaming client. The three remaining criteria are about downloaded files.

### PLAY-004 Progress persistence

| Criterion | State |
| --- | --- |
| Journaled at least every 5 s | ✅ |
| Remote sync ~30 s plus the seven named triggers | ✅ all seven |
| Survives process death, ≤10 s lost | ✅ by construction 🔬 unproven on hardware |
| Finished threshold 95%, configurable 90–99% | ⚠️ **deviates, by the owner's decision.** A duration rather than a percentage, and **not configurable in the app at all** — the value is the library's `markAsFinishedTimeRemaining`, changed in the web interface. ADR-0013 records both departures and why |
| **Marking finished is explicit** | ✅ a checkbox, both directions — and the un-finish PATCH is confirmed accepted by the server |
| Rewinding preserved; conflict never blindly takes the maximum | ✅ |
| **`markAsFinishedTimeRemaining` from library settings** | ✅ **read and used as the rule.** Thirty seconds applies only to a library whose settings have not been read yet |

The 2026-08-13 capture settled both directions. `isFinished: true` round-trips, and the un-finish PATCH
answers `200 OK` — the server accepts it. What made the first probe look like a failure was the server's own
`markAsFinishedTimeRemaining` rule, default ten seconds, applied to a contract book **eight seconds long**:
every position in it is inside the last ten, so it can never be un-finished. A real book is not affected.

That is also the **first observation of `markAsFinishedTimeRemaining` in action**. Both rows above closed on
2026-08-14 (build 0.9.2, Phase 2 closeout PR 2): `LibraryDto` no longer parses `settings` away, the rule is
stored per library in Room, and `FinishedThreshold` resolves it against the listener's own setting. The
decision moved out of `PlaybackService` — which had been applying a hard-coded thirty seconds — into
`DefaultPlaybackRepository`, the one place that can resolve both.

The rule the app applies is the **server's**, on the owner's instruction: where a library sets
`markAsFinishedTimeRemaining`, that value is used, and the listener's setting covers libraries that set none.
`markAsFinishedPercentComplete` is deliberately not read — a percentage of a long book is a long time. Both
decisions and the `max` they replaced are recorded in ADR-0013.

"Its endpoint is still uncaptured", which this row said for a week, was wrong: `settings` is nested in the
`GET /api/libraries` response and has been in `libraries.json` since the wave A capture. Nothing needed
capturing; the fields needed reading. `CapturedShapesTest` now asserts them so the claim cannot rot again.

Still outstanding, and still not a blocker: demonstrating un-finishing in a fixture needs a seeded book longer
than the threshold, which moves the duration in a dozen committed fixtures. The threshold itself is
unit-tested against synthetic durations, so it never depended on that.

### 11.1 Bookmarks — ✅ **built, and passed on hardware 2026-08-14**

Four routes, three of them writes; the read rides on `GET /api/me` because that is where the server keeps
them. The custom session command is included, so a car or a headset can keep a spot with no note — which is
exactly what a driver means by the button. See the note below on what the API does not do.

### PLAY-005 Offline session synchronization

Every criterion built: UUIDv4 ids, idempotent retry, 7-day retention then compaction, latest-trustworthy-
timestamp conflict rule, clock-skew detection over five minutes in diagnostics, no silent history deletion.
🔬 The two-device conflict case needs two devices.

### PLAY-006 Streaming buffer

| Criterion | State |
| --- | --- |
| Five presets with the named values, Automatic default | ✅ |
| Invalid combinations rejected | ✅ |
| Applied on next player preparation | ✅ |
| Position and queue survive player recreation | ✅ |
| Estimate of memory/data cost shown | ✅ |
| **Advanced mode: explicit min/max/start/rebuffer/bytes** | ❌ presets only |
| **Rebuffer count and startup latency in diagnostics** | ❌ |

### PLAY-007 Speed and skip controls

| Criterion | State |
| --- | --- |
| 0.5×–3.0× in 0.05 steps, pitch preserved | ✅ |
| Per-book override beats profile default | ✅ |
| Skips independently configurable 5–120 s | ✅ |
| **Hardware/media controls use the configured values** | ✅ *fixed this round* — the notification's buttons follow the setting live |
| Speed persists across local and streamed versions | ⚠️ true today because there is no local version |
| Defaults 15 back / 30 forward | ⚠️ deliberately 30/30 — ADR-0015 |

### PLAY-008 Sleep timer

Every criterion built: the seven lengths plus end-of-chapter and custom, 5–30 s fade, survives recreation,
shows in notification and player, notification action extends, shake opt-in and sensor only while a timer
runs, expiry pauses and syncs, malformed chapters handled.

The wave 5 closeout added two things the requirement implies and the first build did not offer: the fade can
be turned **off** (PLAY-008 calls it *optional*, and the chips started at five seconds so a timer always
faded), and a **rewind when the timer stops playback** — the owner's *"set rewind time for five minutes and
it will rewind five minutes"*, which auto-rewind's seconds-scale bands cannot express.

### PLAY-009 Auto-rewind after pause

Every criterion built: off by default, four configurable bands, clamped to chapter start, not after a user
seek or a focus interruption, visible and undoable.

### ROUTE-001 Media-button resume — ✅ **built, and confirmed on hardware 2026-08-14**

`onPlaybackResumption` returns the most recently played **unfinished** book at its stored position, which is
what a headset play button against a dead process now gets. Finished books are excluded on purpose: pressing
play the morning after finishing something should not start it again from the end. With nothing to resume it
does nothing and logs a non-fatal diagnostic, which is the requirement's own second clause.

### ROUTE-002 Per-device playback policy — ⚠️ one global switch instead of a policy per device

Eleven acceptance criteria. The closeout built the *behaviour* the policies select between — a car connecting
either starts the last book or opens on it paused — as one setting under Settings → Playback → In the car,
off by default. What is missing is the **registry**: `Never react` / `Arm only` / `Auto-play` / `Ask` chosen
per head unit, the last-seen dates, the locked-profile and speaker rules.

That is a phase's worth of work about device identity rather than about playing a book, and PRODUCT_SPEC's
own Phase 2 deliverable list does not name it — only ROUTE-001 appears, as an exit criterion.

### ROUTE-003 Startup mode — ⚠️ partly true by accident

"App launch alone never starts playback" and "no foreground service from boot" both hold, because nothing
starts playback automatically at all. The three-way profile setting does not exist.

## The four exit criteria

| Criterion | State |
| --- | --- |
| Two-hour streaming soak | 🔬 not run |
| Process/activity recreation | 🔬 not run — rotation is covered by tests, process death is not |
| **Media-button resume** | ✅ **passed on hardware, 2026-08-14** |
| Progress verified against server | ⚠️ verified by capture, not by a device run |

## Defects the device runs found

| # | Report | State |
| --- | --- | --- |
| 1 | Notification bar described the file, not the book | ✅ ADR-0016, wave 5 |
| 2 | 34-hour book shown as 527 hours | ✅ the microseconds/milliseconds error |
| 3 | Notification back restarted the whole book | ✅ own buttons in `SLOT_BACK`/`SLOT_FORWARD` |
| 4 | Seek bar too thick, thumb a bar not a dot | ✅ one `ThinSlider` for both |
| 5 | Chapter bar not seekable | ✅ it is a slider now |
| 6 | **Playback stopped mid-seek and would not restart** | ✅ *this round* — see below |
| 7 | No event or error log in the app | ✅ *this round* |
| 8 | About tab claimed playback was not built | ✅ *this round* |
| 9 | Book marked finished with no way to undo | ✅ *this round* |
| 10 | Play button squeezing the row below it | ✅ *this round* — 88 dp → 72 dp |
| 11 | Synopsis not collapsed | ✅ *this round* — three lines and a Show more |
| 12 | No history pane on the player | ✅ — every jump, tap one to go back |
| 13 | History showed only seeks and starts — no play, pause or sleep timer | ✅ *wave 5 closeout* — see below |
| 14 | Shake-to-extend left no history entry | ✅ *wave 5 closeout* |
| 15 | No way to turn the sleep-timer fade **off** | ✅ *wave 5 closeout* |
| 16 | No rewind when the sleep timer stops playback | ✅ *wave 5 closeout* |
| 17 | **The app did not appear in Android Auto at all** | ✅ *settled on hardware 2026-08-14* — the metadata was the fix; the two runs that still failed were the phone's *Unknown sources* setting |
| 21 | **Android Auto's Continue tab opens empty** | ❌ diagnosed, and it has its own pull request — see below |
| 18 | **Pressing a second book left the first one playing** | ✅ *0.8.0* — the session callbacks dropped the app's own items |
| 19 | History showed only this device, and no time or chapter | ✅ *0.8.0* — server changes, wall-clock times, chapter names, day headings |
| 20 | The bookmark button had been a disabled placeholder since wave 2 | ✅ *0.9.0* — bookmarks, including the session command |
| 22 | **0.9.0 shipped bookmarks with no visible way to make one** | ✅ *0.9.1, confirmed on device* — a button at the top of the sheet; the long press is now a shortcut, not the only route |

### Defect 6, because it is the instructive one

An ExoPlayer that hits an error moves to `STATE_IDLE`, and **an idle player ignores everything** — `play()`
does nothing, `seekTo()` does nothing. Only `prepare()` gets it out. Nothing in the app called it, so a
single dropped stream was permanent until a different book was loaded, which is precisely what was
reported.

It is fixed in three places, deliberately: the service retries transient errors itself, the play button
prepares whenever the player is idle so pressing play always means something, and when the retries are
exhausted the player says so and offers a button. One of those alone would leave a hole.

### Bookmarks, and the three things the API does not do

Built in 0.9.0, and every one of these would have been got wrong by a client written from memory:

- **A bookmark has no id.** The server keys it by its `time` in whole seconds — the delete route ends in the
  number — so `(book, second)` is the identity in the model, in the table's primary key and on the wire.
  Two bookmarks in the same second are one bookmark, and the app agrees with that rather than showing a row
  that vanishes at the next refresh.
- **Bookmarks live on the user, not the item.** `GET /api/me` returns one flat array across every book, so
  there is no per-book read to add — and `SyncAccountUseCase` already makes that call on every resume and
  every profile switch, which is where they refresh.
- **`DELETE` answers `200 text/plain OK`.** A client that parsed the success case as JSON would throw the
  first time somebody deleted something.

Writes are local-first with the same rule progress follows: the row lands before the server is called and a
failure is **not** rolled back. Two flags carry the difference — `hasUnsyncedChanges` so a refresh cannot
discard a bookmark made offline, and `isPendingDelete` so one deleted offline cannot come back on the next
refresh. Database version 13.

### Defect 22, and the shape of the mistake

0.9.0 built the whole feature — routes, table, repository, offline path, twenty-two tests — and shipped it
with **no visible way to create a bookmark**. The only route was a long press on the player's icon, and the
device run reported having *"no way of marking a bookmark"*.

Every layer was tested and every layer was right. What was missing was the affordance, and nothing in the
suite asserted on one: the tests asked whether a bookmark could be *stored*, never whether it could be
*made*. `BookmarkSheetScreenTest` now asks the second question, which is the one a listener asks.

The fix follows Audiobookshelf's own client, whose maintainer described the feature as *"an icon that opens
up a pop-up list of timestamps with an 'add bookmark' button"* — so the sheet opens with a button naming the
position it would use. It differs in one place on purpose: the official client **hides** the button when the
current second is already bookmarked, and this one disables it and says so, because a control that
disappears is the defect being fixed.

**Not in this slice, deliberately:** the book screen does not list bookmarks. It would need its own flow
keyed by the route's book rather than by what is playing, and a list there that could not start playback at
a bookmark would be decoration. The player's sheet is the whole feature; the book screen is a convenience,
and it is a follow-up rather than a silent omission.

### Defect 17, settled — and what it cost to settle

Three device runs. The 0.6.0 run found the app missing from the car entirely, and the cause was real:
Android Auto enumerates media apps by the `com.google.android.gms.car.application` metadata and nothing
else, and the app did not declare it. 0.7.0 added it, and the app was **still** missing. 0.8.0 therefore
stopped guessing and added the readings — and on 2026-08-14 **the app appeared, browsed and played**.

The lesson is worth keeping, because it is about how the two rounds were spent. The APK was correct after
0.7.0; it was verified against the *shipped binary* rather than the source manifest (`aapt2 dump xmltree` on
`app-debug.apk`). Everything that remained was on the phone — Android Auto hides media apps it did not get
from Play unless **Unknown sources** is on in its developer settings, and unlocking developer settings does
not turn that on. A round was spent looking for a defect in a build that did not have one, because the app
could not say what the phone's settings were. It can now: **Settings → About → Testing → Android Auto**
reports the two things the build controls and the three it does not, and `PlaybackService` logs every
connection from a car package, so *"Last car connection: never"* after a drive rules the browse tree out
entirely.

### Defect 21 — the Continue tab is empty, and why

The first screen a car shows reads **"no books"**, while voice search finds them. Diagnosed, not guessed:
`AutoLibrary.continueListening()` filters on `book.progress?.isFinished == false`, and a book that has
**never been played** has no progress at all — so `progress?.isFinished` is `null`, the comparison is false,
and the book is excluded. The tab therefore shows only books that have been started and not finished, which
on a fresh library is none of them. Search is unaffected because it reads the whole list.

That is a one-line fix, but it is the smallest part of what the tab should be. The owner's ask is the app's
own library structure in the car and the app's own player, which is a browse tree with several shelves rather
than one filtered list — so it belongs in **PR 7** (`docs/phase-2-closeout-plan.md`) rather than being
patched here.

### Defect 18, and why no test caught it

Wave 5 overrode `onAddMediaItems` and `onSetMediaItems` so a car could turn a browse id into a playing
book. Both overrides resolved through `AutoLibrary`, which knows `book/…` and `at/…` and nothing else —
and the app's own items carry a bare book id. So every app-initiated play resolved to nothing, the player
was handed an empty list, and whatever was already playing carried on. The device report described it
exactly: *"I press book b, but book A continues."*

The override replaced a Media3 default that was doing real work. `MediaSession.Callback.onAddMediaItems`
passes a list straight through when every item has a `localConfiguration`, and that default is what
carried the app through waves 1–4. Overriding it without reproducing that clause removed it.

No test caught this because nothing in the suite goes through a `MediaSession`, and the callbacks are
inner classes of a `Service`. The decision now lives in `MediaItems.isReadyToPlay`, which is a pure
function with the regression pinned in `MediaItemsTest`.

### Defect 13, and what a history is for

The first version recorded five kinds of position change and nothing else, on the reasoning that ordinary
playback is a line and writing it down would be writing down a clock. The reasoning was right about the clock
and **wrong about what a history is for**: a listener does not open it to audit position arithmetic, they open
it to answer "what happened, and can I get back to before it". "I paused here" and "I set a timer here" are
answers to that.

So the model widened from `PlaybackJump` to `PlaybackEvent`: play, pause, sleep timer set, extended, expired
and the rewind it applies, alongside the five jumps. Play and pause are recorded from **`playWhenReady`, not
`isPlaying`** — `isPlaying` goes false on every buffer, so a book on a slow connection would have written a
pause and a play every few seconds and buried everything else.

## What is left, in the order it should be done

1. ~~**Bookmarks.**~~ ✅ **Built** — see below. PRODUCT_SPEC 11.1's custom command included, so a car or a
   headset can keep a spot.
2. **The small ones:** ~~`markAsFinishedTimeRemaining`~~ ✅ and ~~a configurable finished threshold~~ ✅ (both
   in PR 2); rebuffer count and startup latency in diagnostics, and the duck-vs-pause setting.
3. **The exit criteria on hardware:** the two-hour soak, process death, progress against the server.

Every item PRODUCT_SPEC names for Phase 2 is now **built**. What is left is two small requirement clauses,
the two ROUTE items the owner asked for on 2026-08-14, and the hardware runs — and the hardware runs are the ones that
decide whether the phase is finished *honestly*, because nothing above has been seen in a car or across a
two-hour soak.

## Deliberately not in Phase 2

Recorded so they are not mistaken for gaps. **ROUTE-002 and ROUTE-003** were here until 2026-08-14, on the
grounds that they are about device policy rather than about playing a book and that PRODUCT_SPEC's Phase 2
deliverable list names only ROUTE-001. The owner decided otherwise once the app reached a car, and they are
PRs 5 and 6 of `docs/phase-2-closeout-plan.md`. The reasoning above was about *sequencing*, not about whether
they are worth building — with a head unit in the loop, per-device policy stopped being hypothetical. **PLAY-006's Advanced buffer mode** is a
five-field form for a preference the presets already cover. **Equaliser, widgets and statistics** are the
owner's, for a later phase (`docs/phase-2-closeout.md`). **The queue** is smart download and belongs to
Phase 3 (ADR-0017).
