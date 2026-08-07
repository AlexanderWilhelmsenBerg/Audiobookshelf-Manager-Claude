# Phase 2 — the streaming player, in waves

Written against `PRODUCT_SPEC` PLAY-001 … PLAY-009 and the Phase 2 exit criteria, with Phase 1 merged.

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

## Wave 0 — the capture, before any player code

**Nothing in Phase 2 may be written until this runs.** `PRODUCT_SPEC 22.4` forbids inventing an
endpoint and `22.5` forbids relying on a response shape without a fixture, and
`docs/api-compatibility.md` currently lists both playback capabilities as *"verified against a server:
No"*:

| Capability | Gates | Verified |
| --- | --- | --- |
| `PlaybackSession` | PLAY-001, the streaming session | **No** |
| `LocalSessionSync` | PLAY-005 | **No** |

`scripts/capture-contracts.sh` now opens a real session and captures five new fixtures:

- `item-play.json` — `POST /api/items/{id}/play`. The one that matters most: it should carry the audio
  tracks with their content URLs, the chapters, and the start position. **Three things it has to
  settle** before the player can be wired at all: whether the track URLs are absolute or relative,
  whether they carry their own credential or need the `Authorization` header, and whether the offsets
  are per-track or already global.
- `session-sync.json` and `session-sync-repeated.json` — `POST /api/session/{id}/sync`, sent twice, so
  that PLAY-005's idempotency requirement is an observation rather than a hope.
- `session-close.json` — and what the server then treats as final.
- `me-after-session.json` — the account record afterwards, diffable against the pre-session `me.json`,
  which is where PLAY-004's conflict resolution reads from.

Expect the capture to change this plan. Phase 1's search capture settled two facts that changed the
implementation — a hit is the expanded item, and it carries no progress — and neither was guessable.

## Wave 1 — the vertical slice: one book, one track, play and pause

The smallest thing that is genuinely Phase 2 rather than scaffolding.

- `MediaLibraryService` with the media-playback foreground-service type and the permissions PLAY-001
  names. **One** session, enforced structurally rather than by convention.
- ExoPlayer behind the gateway's session, streaming a single-file book.
- Media notification with cover, title, author, progress and transport controls.
- Audio focus through Media3, default-to-pause on transient loss (PLAY-002), and the becoming-noisy
  receiver — headphones out pauses, and playback never jumps to the phone speaker.
- Progress journaled locally every five seconds (PLAY-004).

Deliberately deferred out of wave 1: multi-track, chapters, speed, sleep timer. Each is a real
requirement and none of them is needed to prove the service works.

## Wave 2 — the global timeline

PLAY-003, and the wave with the most arithmetic in it.

- Multi-file books in server track order, with excluded tracks skipped.
- Seeking across a track boundary while keeping the global book position.
- Chapter navigation that is independent of file boundaries — a chapter can start mid-file and a file
  can hold several.
- The unit tests that belong here rather than on a device: offset conversion both ways, boundary
  seeking, malformed and absent chapter data.

## Wave 3 — progress, and not losing it

PLAY-004 and PLAY-005. The requirements product priority 2 exists for.

- Remote sync every ~30 s plus on pause, seek completion, chapter change, book change, timer stop,
  service shutdown and background transition.
- Position survives process death with under 10 s lost.
- Session outbox: UUIDv4 per session, idempotent retry, seven-day retention, then compaction.
- Conflict resolution that **never blindly takes the maximum position** — an intentional rewind is data,
  not noise. Clock skew over five minutes is detected and surfaced in diagnostics.
- Finished threshold at 95%, configurable 90–99%.

## Wave 4 — the controls a listener expects

PLAY-006 through PLAY-009, each small, each independently testable.

- Speed 0.5×–3.0× in 0.05 steps, pitch preserved, per-book override, persisting across local and
  streamed copies.
- Skip back/forward, independently configurable 5–120 s, defaulting to 15 and 30.
- Buffer presets, remote streams only, with the invalid combinations rejected rather than clamped.
- Sleep timer with end-of-chapter, fade-out, notification extension, surviving recreation.
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
