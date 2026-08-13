# Phase 2 — the streaming player, in waves

Written against `PRODUCT_SPEC` PLAY-001 … PLAY-009 and the Phase 2 exit criteria, with Phase 1 merged.

## Where Phase 2 actually stands — 2026-08-08

**Three waves of six.** `:playback` exists, with a `MediaLibraryService`, ExoPlayer on the app's
authenticated client, a media notification and a mini player. Against the deliverables PRODUCT_SPEC
lists for the phase:

| Deliverable | State |
| --- | --- |
| MediaLibraryService | **Built** — one session, foreground `mediaPlayback` type |
| Remote playback session | **Built** — `PlaybackApi.openSession`, 13 contract tests |
| ExoPlayer | **Built** — on the `@AuthenticatedClient` OkHttp data source |
| Global timeline | **Built** — offsets, seeking across boundaries, chapter navigation |
| Progress sync | **Built** — journal every 5 s, remote sync on the cadence PLAY-004 names, and a durable outbox |
| Notification / lockscreen / headset | Notification and lock screen built; **headset resume untested on hardware** |
| Speed / skip | Not started — wave 4 |
| Buffer presets | Not started — wave 4 |
| Sleep timer | **Built** — pulled forward from wave 4, plus shake-to-restart and a local history (ADR-0014) |
| Audio focus / noisy handling | **Built** — pause-on-transient via speech content type, becoming-noisy on |

What that table does **not** say is that any of it works on a device. Nothing here has played a second
of audio outside a unit test: every assertion is over a semantics tree, a Room row or a MockWebServer
exchange. The four exit criteria all need hardware, and none of them has been attempted.

## Exit criteria, and what each one actually demands

PRODUCT_SPEC names four. None of them is a unit test, and that shapes the whole plan:

| Criterion | What proves it |
| --- | --- |
| Two-hour streaming soak | A device, a real server, and two hours. No fake can produce it. |
| Process/activity recreation | A device plus `adb shell am kill`, or a managed-device test. |
| Media-button resume | A headset or an emulator route equivalent. |
| Progress verified against server | The server's own record read back after a session. |

All four are hardware. What the repository can carry is everything that makes them *pass on the first
try* — and, per Phase 1's lesson, the thing that decides that is fixtures.

## Wave 0 — the capture ✅ **complete, 2026-08-07**

Five fixtures committed. What they settled is in `docs/api-compatibility.md`; the three consequences
for the waves below are:

- **Track URLs are relative and carry no credential**, so ExoPlayer's data source must be the app's
  authenticated OkHttp client. PRODUCT_SPEC 14.5 is satisfied by the server's design rather than
  worked around.
- **Sync and close answer `200` with an empty body.** No confirmation to reconcile against, so wave 3's
  outbox is retry-until-200 plus a separate read of `/api/me`.
- **`startOffset` across tracks is still unverified** — the seed library had one single-file book. The
  seed now creates a two-file book and the capture opens a session against it. **Wave 2 is blocked
  until that fixture exists**, because computing a global position from an unverified offset is exactly
  what 22.5 forbids.

One accident worth keeping: the capture synced a position past the fixture book's duration, and the
server clamped it and marked the book finished. That records a real behaviour — **a session sync can
mark a book finished without being asked** — and the reason turned out to be a library setting we had
captured in Phase 1 and never read. See wave 3.

### What the final run settled

- **`startOffset` is global.** Track two of the two-file book reports `startOffset: 6`, the duration of
  track one, and chapters are globalised the same way. **Wave 2 is unblocked.**
- **The outbox uses `local-all`, even for one session.** `POST /api/session/local` answers `200` with an
  empty body — no per-session result — while `local-all` reports `{id, success, progressSynced}`. A
  queue that cannot tell "applied" from "ignored" cannot drain correctly.
- **`progressSynced: false` was observed**, because the capture sent `updatedAt: 0` and the server
  declined to apply progress older than what it held. The conflict rule, demonstrated rather than read.

Still uncaptured, and not blocking: `GET /api/session/{id}` (resuming an open session) and
`POST /api/items/{id}/play/{episodeId}` (podcast episodes, out of Phase 2 scope).

## Wave 1 — the vertical slice ✅ **built, 2026-08-08 (untested on hardware)**

- `MediaLibraryService` with the media-playback foreground-service type and the permissions PLAY-001
  names. **One** session, enforced structurally: the player and the session are private to the service,
  the service is the only one in the module, and the module is the only one that can name either type.
- ExoPlayer behind the gateway's session, over the app's **authenticated** OkHttp client — the server
  sends credential-free track URLs, so the `Authorization` header is what fetches the audio and
  PRODUCT_SPEC 14.5's no-token-in-a-URL rule is met by the server's own design.
- Media notification with cover, title, author, progress and transport controls. Artwork loads through
  the same authenticated stack, for the reason `ImageModule` already records.
- Audio focus through Media3, default-to-pause on transient loss (PLAY-002), and the becoming-noisy
  receiver. The pause-rather-than-duck behaviour comes from declaring `AUDIO_CONTENT_TYPE_SPEECH`:
  Media3's focus manager ducks music and pauses speech, so the requirement is met by describing the
  content honestly rather than by intercepting focus callbacks.
- Progress journaled locally every five seconds (PLAY-004), plus on pause, track change, end of book
  and player error. The last write goes on the application scope so it survives the service's own
  destruction.
- A play button on the book screen and a mini player above every screen, both driven through a
  `MediaController` — the same client a headset and Android Auto are, so a book started from any of
  them renders identically.

**What wave 1 deliberately does not do**, and where each lands:

| Deferred | Where |
| --- | --- |
| Seeking across a track boundary, chapter navigation | Wave 2 |
| Sending progress back to the server | Wave 3 |
| Speed, skip, sleep timer, buffer presets, auto-rewind | Wave 4 |
| A browse tree for Android Auto and Wear | Later; `onGetLibraryRoot` rejects, honestly, rather than returning an empty root |
| Reading the library's own `markAsFinishedTimeRemaining` | Wave 3 — ADR-0013's `max(...)` half; the app's flat 30 s is in force |

### What is testable off a device, and what is not

Everything in wave 1 that can be asserted without hardware is asserted: 13 contract tests over the two
play fixtures, 14 over the timeline arithmetic, 10 over the playlist construction, 7 over the progress
journal against a real database, and 7 over the mini player's semantics tree.

**None of that plays audio.** Whether the service actually holds a foreground notification, whether the
focus behaviour is what Media3's documentation says, whether the stream survives doze — those are wave
5's, and the first device run is where they will be found.

## Wave 2 — the global timeline ✅ **built, 2026-08-08 (untested on hardware)**

PLAY-003, and the wave with the most arithmetic in it.

- **Multi-file books in server track order**, excluded tracks dropped from the playlist rather than
  skipped at playback time — a playlist that does not contain a file cannot play it by accident, from
  any control surface.
- **Seeking across a track boundary** keeps the global book position. Every seek in the app — the resume,
  the dragged bar, the skips, chapter navigation — goes through the same `GlobalTimeline` conversion, so
  none of them can disagree with another about where a position is.
- **Chapter navigation independent of file boundaries**: a sheet listing every chapter with its start
  time, opening scrolled to the current one, plus previous/next that restarts the current chapter when
  well into it and steps back when just past a boundary.
- **The unit tests that belong here rather than on a device**: 23 over the conversion both ways, boundary
  seeking, fractional offsets that a summing implementation would drift on, and chapter data that is
  absent, malformed or unordered.

Deliberately not here: the skips are a hardcoded thirty seconds each way. PLAY-007's configurable
5–120 second setting is wave 4's, and the constant is named in both players so the gap is visible in the
code rather than only in this plan.

The server has already globalised both track offsets and chapter times, so neither needs deriving by
summing durations. What still needs arithmetic is the other direction: Media3 plays a **playlist** and
its position is per-item, so a global book position maps to a window index plus an offset within it.
Using `startOffset` makes that exact rather than accumulated, and an accumulated one drifts on a book
whose track durations are not whole seconds.

- Multi-file books in server track order, with excluded tracks skipped.
- Seeking across a track boundary while keeping the global book position.
- Chapter navigation that is independent of file boundaries — a chapter can start mid-file and a file
  can hold several.
- The unit tests that belong here rather than on a device: offset conversion both ways, boundary
  seeking, malformed and absent chapter data.

## Wave 3 — progress, and not losing it ✅ **built, 2026-08-08 (untested on hardware)**

PLAY-004 and PLAY-005. The requirements product priority 2 exists for. **Revised after reading the
server's session manager** — the original plan had a structural hole in it.

### The hole, and what fills it

The plan had the outbox retrying `POST /api/session/{id}/sync` per session. That cannot work for an
offline session: the route needs an id the *server* issued, and a session recorded on a train has never
been to the server. There was no route by which an offline session could ever have been uploaded.

`POST /api/session/local` is the one built for it — the **client** generates the id, and the server
treats an unseen id as a new session. That is what PLAY-005's "every offline listening session has a
UUIDv4 identifier" is for, and it is what makes a retry idempotent: the second attempt carries the same
id and is recognised as the same session rather than duplicated.

`POST /api/session/local-all` takes `{"sessions": [...]}` and answers with a per-session result, so an
outbox drains in one request rather than N.

### What wave 3 builds

- Remote sync every ~30 s plus on pause, seek completion, chapter change, book change, timer stop,
  service shutdown and background transition — over `POST /api/session/{id}/sync` while online.
- Position survives process death with under 10 s lost.
- Session outbox: UUIDv4 per session, drained through `/api/session/local-all`, retried until the
  per-session result says `success`, seven-day retention, then compaction.
- **Conflict resolution is the server's**, and the app's job is not to fight it. The server takes the
  newer `updatedAt` and lets progress move backwards, which is exactly PLAY-004's "never blindly
  chooses the maximum position". So the app must send an honest `updatedAt` and must **not** clamp its
  own position to the maximum before sending, which would defeat a rule the server implements
  correctly.
- Clock-skew detection stops being hygiene and becomes load-bearing: the server trusts `updatedAt`, so
  a device five minutes fast wins every conflict it takes part in.

### The finished threshold — decided, ADR-0013

**A book is finished when 30 seconds or less remain**, and the app is never less eager than the server:
`max(30s, library.markAsFinishedTimeRemaining)`. A book the server reports as `isFinished` is finished
regardless of position.

This deviates from PLAY-004's literal "95%, configurable 90–99%", and deliberately: 95% of a ten-hour
book is half an hour from the end, which is not what anyone means by finished. The requirement's intent
— a book near its end counts as done, and the user can tune it — is kept; its unit is not.

### What wave 3 actually shipped

- **One table, `playback_sessions`** (database version 9), carrying a session from `Open` through
  `Pending` to `Synced`. An "active session" table plus a separate outbox would need a hand-off, and the
  hand-off is exactly where a session is lost: the process dies between the delete and the insert and
  nobody ever learns the listener was there.
- **Two id columns, not one.** `sessionId` is a UUIDv4 this device generated and is what the offline
  route uploads under — which is what makes a retry idempotent. `remoteSessionId` is what the *server*
  issued, and it is `null` for a session recorded with no connection. Collapsing them is how an offline
  session ends up with no route by which it could ever be uploaded.
- **Write first, send second, everywhere.** Every method in `DefaultSessionSyncRepository` stores the
  position before it touches the network and leaves the row queued when the send fails. A position that
  was attempted and lost is indistinguishable, afterwards, from one that was never recorded.
- **No retry ceiling.** `attempts` climbs and the row stays. An outbox that gives up has discarded
  listening the user did.
- **The cadence, each trigger from the place that knows it happened**: the thirty-second ticker and the
  player events from the service, the chapter crossing from `PlaybackController` (the only place that
  holds the chapter list), the timer stop from `SleepTimerController`, and the background transition from
  the composition's `ON_STOP`. Nothing infers a trigger from a state change, because a trigger inferred
  from state is a trigger that fires on rotation.
- **Clock skew from the `Date` response header**, on every exchange, surfaced under Settings → About.
- **The readings, on a screen.** Every criterion in PLAY-004 and PLAY-005 is about something that
  happened *between* the app and the server, and none of it is visible from either side alone: "the queue
  drained" and "the queue was silently discarded" look identical on a phone. So the About tab now carries
  the outbox's counts, the last accepted position and its trigger, the last error code, the skew — and a
  checklist of the wave-3 checks with each one's verdict where the app can judge it, and an explicit
  "needs a device" where it cannot.

### What wave 3 deliberately did not do

- **`markAsFinishedTimeRemaining` is still unread**, so ADR-0013's `max(30s, library setting)` is still
  half-implemented and the app's flat thirty seconds is in force. It needs the library setting modelled,
  which is a Phase 1 fixture the app has never parsed. Carried to wave 4.
- **`timeListened`'s accumulate-or-replace question is stated rather than answered** — see
  `docs/api-compatibility.md`. It affects a statistic, never a position, and it is on the checklist.
- **The outbox is drained by a sync trigger, not by WorkManager.** A book that was listened to offline and
  then never played again keeps its row until the next play. PLAY-005 does not ask for a constrained
  background drain and Phase 4's download work brings WorkManager into playback anyway; adding a worker
  now would be a second scheduler for one queue.

### Tests

- 19 contract tests over the play, sync, close and `local-all` fixtures (6 new).
- 11 over the outbox's SQL against a real database — including that compaction removes uploaded rows by
  `syncedAt` and leaves an *older* queued row alone, which is the one query that could silently destroy
  listening.
- 14 over the repository: a failed sync leaves a durable row, an offline session is uploadable at all, a
  retry carries the same id, a declined position is recorded rather than retried, a session the batch did
  not mention stays queued, and no book title reaches the log.
- 13 over `ListenedTime`, 10 over `ServerClock`, 8 over the checklist's verdicts, 2 over the migration.

## Wave 4 — the controls a listener expects

PLAY-006 through PLAY-009, each small, each independently testable.

- Speed 0.5×–3.0× in 0.05 steps, pitch preserved, per-book override, persisting across local and
  streamed copies.
- **Carried in from wave 3**: read the library's `markAsFinishedTimeRemaining` and prefer it over the
  app's flat thirty seconds (ADR-0013's unfinished half).
- Skip back/forward, independently configurable 5–120 s, defaulting to 15 and 30.
- Buffer presets, remote streams only, with the invalid combinations rejected rather than clamped.
- ~~Sleep timer with end-of-chapter, fade-out, notification extension, surviving recreation.~~ **Built
  ahead of this wave**, at the project owner's request, together with two things PLAY-008 does not ask
  for: a shake that *restarts* rather than extends, and a local record of every timer. ADR-0014 records
  both, and PLAY-008's **custom length** is the one part still outstanding.
- Auto-rewind buckets, off by default, never crossing a chapter or book start, undoable.

## Wave 5 — the exit criteria

Hardware. A delta test script per build, as in Phase 1, plus the soak.

## What is carried in from Phase 1

- **Cover art** is already fetched through the authenticated client, so the notification has artwork
  available without new plumbing.
- **Progress is already written and read** by `SyncAccountUseCase` and `DefaultLibraryRepository`, with
  the rule that an unsynced local write always wins. Wave 3 extends that path rather than inventing one.
- **The gateway pattern, `AppResult`, redacted logging and the contract-test harness** all apply
  unchanged. A playback endpoint is a gateway method with a fixture behind it, like every other.

## Risks worth naming now

1. **The capture may not answer everything.** If `item-play` comes back transcoding rather than direct,
   or the URLs need a token this app will not put in a URL (PRODUCT_SPEC 14.5), wave 1's design changes.
   That is the reason wave 0 exists.
2. **`targetSdk` 37's background-audio hardening** is not in force at target 36, so wave 1 will pass on
   Android 17 devices under the old rules. ADR-0011 records that the eventual bump is a validation pass
   — provided PLAY-001 and PLAY-002 are built to spec, which is exactly what wave 1 does.
3. **The soak is unforgiving.** Two hours of streaming exposes leaks, wake-lock mistakes and buffer
   mismanagement that nothing shorter does. It should be run early and often, not saved for the end.
