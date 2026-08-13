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
| **Android Auto** can play, pause, seek, skip, stop | ❌ no browse tree — `onGetLibraryRoot` rejects |
| Notification shows cover, title, author, progress | ✅ — the progress became the *book's* in wave 5 |
| Notification has play/pause, **backward and forward** | ✅ — wave 5 replaced skip-to-previous/next, which restarted the book |
| Foreground-service type and permissions declared | ✅ |
| Only one local audio media session | ✅ structurally: the module is the only one that can name the types |
| **A process restart restores the last playable item, paused** | ❌ — the position is in Room, nothing restores the item |

Two gaps, and the second is also ROUTE-001's.

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
| **Marking finished is explicit** | ✅ *fixed this round* — a checkbox, both directions |
| Rewinding preserved; conflict never blindly takes the maximum | ✅ |
| **`markAsFinishedTimeRemaining` from library settings** | ❌ ADR-0013's other half |

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

### PLAY-009 Auto-rewind after pause

Every criterion built: off by default, four configurable bands, clamped to chapter start, not after a user
seek or a focus interruption, visible and undoable.

### ROUTE-001 Media-button resume — ❌ **entirely absent, and an exit criterion**

No `MediaSession.Callback.onPlaybackResumption`. A headset play button against a dead process does nothing,
and no resumption metadata is published to the system. This is the largest single gap in the phase.

### ROUTE-002 Per-device playback policy — ❌ absent

Eleven acceptance criteria, none built. Large: a device registry, four policies, a permission story, and
rules about locked profiles and speakers.

### ROUTE-003 Startup mode — ⚠️ partly true by accident

"App launch alone never starts playback" and "no foreground service from boot" both hold, because nothing
starts playback automatically at all. The three-way profile setting does not exist.

## The four exit criteria

| Criterion | State |
| --- | --- |
| Two-hour streaming soak | 🔬 not run |
| Process/activity recreation | 🔬 not run — rotation is covered by tests, process death is not |
| **Media-button resume** | ❌ unbuilt (ROUTE-001) |
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
| 12 | No history pane on the player | ✅ *this round* — every jump, tap one to go back |

### Defect 6, because it is the instructive one

An ExoPlayer that hits an error moves to `STATE_IDLE`, and **an idle player ignores everything** — `play()`
does nothing, `seekTo()` does nothing. Only `prepare()` gets it out. Nothing in the app called it, so a
single dropped stream was permanent until a different book was loaded, which is precisely what was
reported.

It is fixed in three places, deliberately: the service retries transient errors itself, the play button
prepares whenever the player is idle so pressing play always means something, and when the retries are
exhausted the player says so and offers a button. One of those alone would leave a hole.

## What is left, in the order it should be done

1. **Media-button resume (ROUTE-001).** An exit criterion, and the phase cannot close without it.
2. **Android Auto browse tree.** PLAY-001's Auto criterion and PRODUCT_SPEC 11.1's responsibility. The
   owner's "car mode" is this.
3. **Bookmarks.** PRODUCT_SPEC 11.1 lists the custom command; the player has carried a disabled button
   since wave 2. Needs a capture first (22.4/22.5).
4. **The small ones:** `markAsFinishedTimeRemaining`, a configurable finished threshold, rebuffer count and
   startup latency in diagnostics, and the duck-vs-pause setting.
5. **The exit criteria on hardware:** the two-hour soak, process death, progress against the server.

Items 1 and 2 decide whether Phase 2 is finished. Items 4 and 5 decide whether it is finished *honestly*.

## Deliberately not in Phase 2

Recorded so they are not mistaken for gaps: **ROUTE-002 and ROUTE-003** are a phase's worth of work about
device policy rather than about playing a book, and PRODUCT_SPEC's own Phase 2 deliverable list does not
name them — only ROUTE-001 appears, as an exit criterion. **PLAY-006's Advanced buffer mode** is a
five-field form for a preference the presets already cover. **Equaliser, widgets and statistics** are the
owner's, for a later phase (`docs/phase-2-closeout.md`). **The queue** is smart download and belongs to
Phase 3 (ADR-0017).
