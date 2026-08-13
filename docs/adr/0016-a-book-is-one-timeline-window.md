# ADR-0016 — A book is one timeline window, not a playlist

- **Status:** Accepted, scheduled for Phase 2 wave 5
- **Date:** 2026-08-13
- **Requirements:** PLAY-001, PLAY-003, PLAY-004

## Context

Wave 1 built the player as a **playlist**: one `MediaItem` per audio file, with each item's extras carrying
the track's start offset on the book's timeline and the book's total duration. Wave 2 built `GlobalTimeline`
on top of that — a global book position converts to a window index plus an offset within it, and back.

That design works, and every seek in the app goes through one conversion so none of them can disagree. But
it leaks in one place the app does not control: **Media3 reports the current media item's position and
duration to every controller**, including the media notification, the lock screen and Android Auto. Those
surfaces therefore describe the current *file*.

A device run found it. On a library where each file is a chapter, the notification's progress bar and its
two clocks read as "time left in this chapter" — plausible enough that it took a report to notice, and
wrong enough to be useless for the question a listener actually asks. Wave 4 answered the report by putting
the book's remaining time on the notification's second line as text (`BookRemaining`,
`BookNotificationProvider`). That is a caption beside a wrong number, not a fix.

## Decision

Present a book to Media3 as **one timeline window** using `ConcatenatingMediaSource2`, which is the pattern
Media3 provides for exactly this: several sources combined into a single `MediaItem` with a single period,
whose duration is the sum of theirs.

The player then reports book-global positions natively. Nothing has to convert anything.

`ConcatenatingMediaSource2` is chosen over the other candidate — a `ForwardingPlayer` that maps a playlist
timeline down to one window — because the wrapper has to map *every* position, duration, seek, buffered
position and timeline method consistently, and a single mistake in any one of them is a position bug in the
one subsystem that must not have them. The concatenating source moves the problem into the library.

## Consequences

### What this deletes

- `MediaItems.KEY_TRACK_START_OFFSET_MS` and `KEY_TRACK_DURATION_MS`, and `KEY_BOOK_DURATION_MS` with them:
  the player knows the book's duration once the book is one item.
- `MediaItems.globalPositionOf`, `MediaItems.tracksOf`, and `GlobalTimeline.cursorFor` — the global-to-window
  conversion has nothing left to convert. `GlobalTimeline`'s **chapter** functions stay; chapters are still
  the app's own data and still independent of file boundaries.
- Every caller of that conversion simplifies to reading `player.currentPosition`: the progress journal, the
  sync coordinator, the auto-rewind, the seek bar, the chapter sheet.
- **`BookRemaining` and `BookNotificationProvider`.** Wave 4's second line becomes redundant the moment the
  notification's own clocks describe the book. Worth stating plainly: that work is paid for twice. It was
  the right answer to the report at the time — it is small, it cannot affect playback, and it shipped in a
  day — but it is scaffolding, and this ADR is the decision to take it down.

### What this gains beyond the notification

- The lock screen, Android Auto and any future Wear surface all get book-level positions with no work.
- Playback resumption gets simpler: `MediaSession` restores a single `MediaItem` rather than a playlist
  whose window indices have to line up with a track list the app rebuilt.
- One fewer place where a multi-file book can behave differently from a single-file one — which is the class
  of bug wave 2 spent most of its tests on.

### What this risks, and it is not small

1. **It is the core playback path**, and it changes what *every* controller sees. The app's own mini player,
   full player, seek bar, journal, sync and rewind all read positions through the session. They should all
   become simpler, and each one is a chance to get it wrong.
2. **`ConcatenatingMediaSource2` needs each source's duration up front.** The server supplies them
   (`PlayableTrack.duration`), but a book with a track reporting zero or no duration cannot be built this
   way. Such a book must fall back to the playlist, which means both paths exist and the fallback is the one
   nobody will test. The fallback needs a fixture.
3. **It is `@UnstableApi`.** So is most of what this module already uses, and ADR-0011 records how that is
   handled, but a signature change here moves playback rather than a setting.
4. **No unit test can settle it.** Whether a concatenated source gaplessly crosses a file boundary at 2×
   with a cold buffer is a two-hour soak on hardware, which is precisely why this is scheduled into wave 5
   beside the soak rather than dropped into wave 4.

### Sequencing

Wave 5, as its own slice, before the soak rather than after — the soak is the test for it. Wave 4's
notification text stays until the timeline change is verified on a device, and is removed in the same commit
that verifies it. Shipping both and removing neither would leave the remaining time printed twice.

## Alternatives considered

**Leave it, and keep the caption.** Rejected on the owner's instruction, and it was the weaker answer anyway:
a correct number beside an incorrect one still leaves the incorrect one on the lock screen.

**A `ForwardingPlayer` wrapper.** Rejected above — more code, and the code is all position arithmetic in the
one place the app cannot afford arithmetic bugs.

**One `MediaItem` per book with a server-side concatenated stream.** Not available: Audiobookshelf serves the
files it has, and asking it to stitch them would be a transcode per playback.
