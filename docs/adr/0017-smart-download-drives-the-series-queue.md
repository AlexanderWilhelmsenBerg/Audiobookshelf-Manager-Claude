# ADR-0017 — The "queue" is smart download, and it belongs to Phase 3

- **Status:** Accepted, scheduled for Phase 3
- **Date:** 2026-08-13
- **Requirements:** to be assigned when Phase 3's requirements are written. Relates to PRODUCT_SPEC section
  8's recommended features and to Phase 4's "auto-play next in a series".

## Context

`docs/phase-2-closeout.md` compared the app against the clients people use and declined a **queue**, on the
grounds that only SoundLeaf has one and an audiobook is not a playlist.

The owner's answer, on 2026-08-13, reframed the feature rather than overruling the reasoning:

> the queue should just start the next book in the series. But queue is more for phase 3 as I want it
> connected to download, as in it will download the next book in the series when the current book is over
> halfway, if the book is over an hour and "smart download" is on in the settings and only on wifi.

That is not a queue in the media-player sense — no reorderable list, no arbitrary items, no UI for building
one. It is **prefetching the next book in a series**, with playback continuing into it as a consequence.

## Decision

Build it in **Phase 3**, alongside downloads, as a setting called **smart download**. It is a download
feature that happens to make playback continuous, not a playback feature that happens to download.

The trigger is all four of these, and a change to any of them is a change to this ADR:

| Condition | Value |
| --- | --- |
| Progress through the current book | **over halfway** |
| Length of the current book | **over one hour** |
| The `smartDownload` setting | **on** |
| Network | **unmetered (Wi-Fi) only** |

And the obvious implicit ones, which are conditions all the same: the current book is **in a series**, the
next book **exists and is visible to this profile** (PRODUCT_SPEC 5.2 — a series can span a permission
boundary), and it is **not already downloaded**.

### Why each condition is there

- **Over halfway** — a listener who abandons a book usually does so early. Halfway is the point at which
  finishing it is likely, so it is the point at which prefetching the next one stops being speculative.
- **Over an hour** — a twenty-minute short story is not the start of a listening habit, and downloading the
  next thing after it would spend somebody's disk and bandwidth on a guess.
- **The setting** — this spends storage and data without being asked. It is off unless turned on, and its
  wording has to say what it actually does (product priority 5).
- **Wi-Fi only, and not configurable to anything else** — an audiobook is hundreds of megabytes. A feature
  that quietly downloads one over a mobile connection is a feature that costs a user real money, and
  "you left the toggle on" is not an answer. WorkManager's `NetworkType.UNMETERED` constraint expresses it
  and survives reboots, which is the whole reason Phase 3's downloads run under WorkManager.

## Consequences

- **Phase 2 does nothing for this**, which is the point of recording it now — it is a Phase 3 feature and
  writing any of it into the player would be scope the phase did not ask for.
- **Phase 4's "auto-play next in a series"** becomes the playback half of the same idea, and should read
  this file before deciding what "next" means. The two must agree on series ordering or a listener will be
  handed a book they did not have downloaded.
- **A capture is owed first.** Which field identifies a book's position in its series, and whether the
  server exposes "the next book in this series" directly or only a sorted list, is not settled by any
  capture in `core/network/src/test/resources/contracts/`. PRODUCT_SPEC 22.4/22.5 forbid building on an
  unverified shape, so Phase 3 starts this item with a capture.
- **Storage policy interacts with it.** A feature that downloads books nobody asked for needs an answer to
  what deletes them. That answer belongs with Phase 3's retention rules rather than here, but it cannot be
  left out of them.
