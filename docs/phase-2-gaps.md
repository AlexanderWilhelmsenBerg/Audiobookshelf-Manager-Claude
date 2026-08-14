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
| **Android Auto** can play, pause, seek, skip, stop | ⚠️ built — three tabs, voice search, the `automotive_app_desc` metadata — but **two device runs have failed to see the app in a car**. Everything in the APK is verified correct; 0.8.0 adds the readings that say what is not (defect 17) |
| Notification shows cover, title, author, progress | ✅ — the progress became the *book's* in wave 5 |
| Notification has play/pause, **backward and forward** | ✅ — wave 5 replaced skip-to-previous/next, which restarted the book |
| Foreground-service type and permissions declared | ✅ |
| Only one local audio media session | ✅ structurally: the module is the only one that can name the types |
| **A process restart restores the last playable item, paused** | ✅ *wave 5 closeout* — `onPlaybackResumption` returns the last unfinished book at its stored position |

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
| Finished threshold 95%, configurable 90–99% | ⚠️ the threshold is 95% and **not configurable** |
| **Marking finished is explicit** | ✅ a checkbox, both directions — and the un-finish PATCH is confirmed accepted by the server |
| Rewinding preserved; conflict never blindly takes the maximum | ✅ |
| **`markAsFinishedTimeRemaining` from library settings** | ❌ ADR-0013's other half — the setting is now *observed* (10 s default) but its endpoint is still uncaptured |

The 2026-08-13 capture settled both directions. `isFinished: true` round-trips, and the un-finish PATCH
answers `200 OK` — the server accepts it. What made the first probe look like a failure was the server's own
`markAsFinishedTimeRemaining` rule, default ten seconds, applied to a contract book **eight seconds long**:
every position in it is inside the last ten, so it can never be un-finished. A real book is not affected.

That is also the **first observation of `markAsFinishedTimeRemaining` in action**, which is the unbuilt half
of ADR-0013 in the row above. Demonstrating un-finishing in a fixture needs a seeded book longer than the
threshold, which moves the duration in a dozen committed fixtures — so it belongs with that work rather than
with the capture, and the work has to read the setting anyway.

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

### ROUTE-001 Media-button resume — ✅ **built in the wave 5 closeout** 🔬 untested on hardware

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
| **Media-button resume** | ✅ built 🔬 needs a headset and a killed process |
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
| 17 | **The app did not appear in Android Auto at all** | ⚠️ *0.7.0 added the missing `automotive_app_desc` metadata, and the app is still not listed* — see below |
| 18 | **Pressing a second book left the first one playing** | ✅ *0.8.0* — the session callbacks dropped the app's own items |
| 19 | History showed only this device, and no time or chapter | ✅ *0.8.0* — server changes, wall-clock times, chapter names, day headings |

### Defect 6, because it is the instructive one

An ExoPlayer that hits an error moves to `STATE_IDLE`, and **an idle player ignores everything** — `play()`
does nothing, `seekTo()` does nothing. Only `prepare()` gets it out. Nothing in the app called it, so a
single dropped stream was permanent until a different book was loaded, which is precisely what was
reported.

It is fixed in three places, deliberately: the service retries transient errors itself, the play button
prepares whenever the player is idle so pressing play always means something, and when the retries are
exhausted the player says so and offers a button. One of those alone would leave a hole.

### Defect 17, and what is actually left of it

The 0.6.0 run found the app missing from the car entirely, and the cause was real: Android Auto enumerates
media apps by the `com.google.android.gms.car.application` metadata and nothing else, and the app did not
declare it. 0.7.0 added it. **The 0.8.0 report is that the app is still not listed**, so the fix was
necessary and not sufficient.

What the APK contains was checked against the shipped binary rather than the source manifest — `aapt2
dump xmltree` on `app-debug.apk` — and all three declarations are present and correct: the car metadata
pointing at `@xml/automotive_app_desc`, that resource containing `<automotiveApp><uses name="media"/>`,
and an exported service answering `android.media.browse.MediaBrowserService`. There is nothing left in the
build to fix.

Everything remaining is on the phone, and none of it is visible from a car seat, so 0.8.0 stops guessing
and asks the platform instead. **Settings → About → Testing → Android Auto** now reports the two things
the build controls and the three that it does not, including **Installed by** — Android Auto hides media
apps it did not get from Play unless *Unknown sources* is on in its developer settings, and unlocking
developer settings does not turn that on — and **Last car connection**, which is written whenever a
controller from `gearhead` binds to the session. If that still says *never* after a drive, no car ever
reached the app and the browse tree cannot be at fault.

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

1. **Bookmarks — now unblocked.** PRODUCT_SPEC 11.1 lists the custom command and the player has carried a
   disabled button since wave 2. It was blocked on 22.4/22.5: no capture had produced the shape. The
   2026-08-13 capture has, and all four endpoints are recorded and pinned by `CapturedShapesTest` — create,
   where a bookmark is stored (`user.bookmarks`, keyed by its `time` in seconds, with no id), update, and a
   delete that answers plain-text `OK`. See `docs/api-compatibility.md`. This is the next slice.
2. **The small ones:** `markAsFinishedTimeRemaining`, a configurable finished threshold, rebuffer count and
   startup latency in diagnostics, and the duck-vs-pause setting.
3. **The exit criteria on hardware:** the two-hour soak, process death, progress against the server.

Every item PRODUCT_SPEC names for Phase 2 is now **built**. What is left is one recommended feature waiting
on a capture, four small requirement clauses, and the hardware runs — and the hardware runs are the ones that
decide whether the phase is finished *honestly*, because nothing above has been seen in a car or across a
two-hour soak.

## Deliberately not in Phase 2

Recorded so they are not mistaken for gaps: **ROUTE-002 and ROUTE-003** are a phase's worth of work about
device policy rather than about playing a book, and PRODUCT_SPEC's own Phase 2 deliverable list does not
name them — only ROUTE-001 appears, as an exit criterion. **PLAY-006's Advanced buffer mode** is a
five-field form for a preference the presets already cover. **Equaliser, widgets and statistics** are the
owner's, for a later phase (`docs/phase-2-closeout.md`). **The queue** is smart download and belongs to
Phase 3 (ADR-0017).
