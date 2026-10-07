# BookWave state for a future watch face

**Classification:** Accepted owner requirement and architecture; publisher/consumer runtime not implemented.
**Updated:** 2026-10-07. Delivery tracker: [Garmin #8](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/issues/8); Android
[umbrella #119](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/119).

## Selected path and ownership

Use Garmin's [Complications API](https://developer.garmin.com/connect-iq/api-docs/Toybox/Complications.html).
It permits Device Apps and Audio Content Providers to publish state that a watch face can read/subscribe
to on the watch. The owner selected BookWave as the feed owner. A future watch face needs no direct
Audiobookshelf login/polling; Android and WatchShelf Sidecar retain server/account/progress integration.
The API supports the primary fēnix 8 targets. Actual cross-app delivery and battery behavior still require
physical evidence.

Companion publishes validated PHONE display state; the separately selected BookWave Audio Provider
publishes real GARMIN playback state. Each has its own application ID and complication identifiers.
A consumer cannot read another app's private Application.Storage. The apps share versioned projection
code and fixtures, rather than a pretend common storage namespace. Companion is not an audio engine.

This is a logical feed plan, not a released wire/resource contract. Numeric complication IDs, resource
definitions, permissions, text bounds and publication-generation handling must be confirmed against
the SDK, documented and fixture-tested before implementation ships. Phase 2 envelopes remain unchanged.

## Minimum projection

| Field | Meaning and fallback |
| --- | --- |
| feedVersion | Consumer compatibility; incompatible data yields a generic unavailable state. |
| title | Current book, only when publication is authorized; cleared for empty/redacted state. |
| chapter | Current chapter when the source knows it; unavailable remains unknown. |
| position / duration / percentage | Validated coarse book progress; unavailable duration has no fabricated percentage. |
| playbackState | Playing, paused or unknown. A stored playing snapshot does not prove ongoing audio. |
| source | PHONE for Companion projection; GARMIN for actual provider-local listening. |
| observedAt | When this source observed/persisted the projection, distinct from a listening event. |
| listeningEventAt | Original legitimate listen/seek/checkpoint time when known; optional for current PHONE display snapshots. |
| freshness | Recent observed state, stored/stale, or unavailable; receipt does not prove continuous connection. |
| privacyState | Authorized, redacted or empty; never publish credentials, server host or profile identity. |

The future face may interpolate display progress only while source/freshness rules permit; interpolation
never becomes a listening event or changes server progress. Receiving a later PHONE snapshot cannot
make its observation time override a newer legitimate watch listen or deliberate rewind. If source
authority is ambiguous, retain explicit source/unknown state rather than resolving with max(position).

## Publication, privacy and lifecycle

Publish after successful validation and persistence through actual transport/provider lifecycle callers.
No success publication follows a failed store operation. Update on meaningful play/pause/seek/book/
chapter/privacy changes and a bounded checkpoint cadence. No per-second BLE or watch-face networking.
The planned provider journal preserves captured events through phone absence, restart and delayed sync.

Garmin retains fields omitted from updateComplication. Empty/locked/redacted state therefore explicitly
overwrites **every** private label/value, including previously published text; a partial clear can leak
old titles. If multiple complications form one projection, use a documented publication generation and
consumer consistency check rather than assuming all updates are atomic.

Cached data can remain after a publisher closes. The consumer distinguishes stored/stale from playing
now or connected now, and handles absent/uninstalled publishers. An offline watch cannot receive a phone
privacy clear until reconnect. Local downloaded provider media has its own account/authentication
boundary; a phone lock must not be advertised as remotely erasing offline watch storage.

## Verification to log during implementation

Automated: shared PHONE/GARMIN projection fixtures; invalid/missing data and versions; original event vs
observation time; unknown chapter/duration; rewind/source ambiguity; complete clearing of previously
retained private values; failed persistence; bounded cadence; publication-generation consistency;
actual callers from transport/store/provider events; target/test/package compilation. Run No Evil
execution and simulator acceptance are recorded separately from test compilation.

| Physical case | Needed evidence — all NOT RUN |
| --- | --- |
| GF-01 | Actual publisher → subscriber query/callback on both supported fēnix targets where available, separate app IDs and absent/uninstalled publisher. |
| GF-02 | Correct book/chapter/progress/state after actual play, pause, seek, book change and chapter crossing; unavailable chapter/duration stays honest. |
| GF-03 | Disconnect, publisher close, reconnect and watch reboot retain valid state with truthful stored/stale indication; no false continuous connection. |
| GF-04 | Phone lock/profile switch/clear removes every previously published private field when delivered; record offline redaction limitation and provider account boundary. |
| GF-05 | Real provider listening without phone, later sync, deliberate rewind and PHONE/GARMIN transitions preserve source and captured times without autoplay. |
| GF-06 | Long/localized metadata, empty/unknown/incompatible state and readable complication values; owner visual judgment only where needed. |
| GF-07 | Sustained publication/subscription background behavior and battery cost with exact firmware/Connect versions, source and binary hashes. |

An eventual simulator/temporary subscriber harness may test publication before the production watch
face exists. This plan creates no watch face or Data Field. Existing G-01/G-02 transport acceptance and
GD-01–10 device/provider tests remain open. The publisher is a prerequisite for the later watch face;
physical Companion/control/reconciliation acceptance still gates that face's implementation.
