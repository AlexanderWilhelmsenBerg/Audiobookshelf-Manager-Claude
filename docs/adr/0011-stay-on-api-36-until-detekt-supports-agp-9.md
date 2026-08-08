# ADR-0011: Stay on API 36 until detekt supports AGP 9

- **Status:** Accepted
- **Date:** 2026-08-07
- **Requirements:** PRODUCT_SPEC 16.1, 16.3, 20

## Context

Android 17 (API 37) is released and AGP 9.3 supports it. The question raised before starting Phase 2
was whether to upgrade now, on the reasoning that Android 17's **background audio hardening** governs
exactly what Phase 2 builds: an app that plays audio, requests audio focus or changes volume while
backgrounded must run a foreground service with while-in-use capability.

That reasoning was checked and does not hold up.

## The argument for upgrading now, and why it fails

**"Phase 2 must be built against the target it will ship on."** The hardening is gated on **targetSdk
37**, which is set independently of `compileSdk`. An app targeting 36 keeps the previous behaviour on an
Android 17 device — that is what targetSdk gating is for. Nothing in Phase 2 needs API 37 to compile;
Media3 builds against 36.

**"Otherwise we build playback twice."** The opposite is true. `PLAY-001` and `PLAY-002` already require
a `MediaLibraryService`, the media-playback foreground-service type, and audio focus through Media3 —
which is precisely what API 37 will demand. Building Phase 2 to its own specification *is* the
preparation. The later bump becomes a validation pass, not a redesign.

## The argument against, which does hold

**detekt has no stable AGP 9 support.** `detekt/detekt#8981` is an open `InvalidPluginException`
against AGP 9.0, and the only detekt built and tested against AGP 9.3 is **2.0.0-alpha**.

`PRODUCT_SPEC 16.3` makes detekt with type resolution a required gate, and `16.1` forbids dynamic
versions. Upgrading today means pinning an alpha as a quality gate, or removing the gate for the
duration. Both are worse than the thing they would buy, which is nothing yet.

The rest of the migration is real but tractable, and worth recording so the next attempt is not a
discovery exercise:

| | Current | Required by AGP 9.3 |
| --- | --- | --- |
| Gradle | 8.14.3 | 9.5.0 |
| AGP | 8.12.0 | 9.3.0 |
| Kotlin | 2.2.0 | 2.2.10+ |
| KSP | 2.2.0-2.0.2 | 2.2.10-2.0.2+ |
| JDK | 21 | 17+ ✅ |

AGP 9 also enables the new DSL by default and replaces `kotlin-android` with built-in Kotlin support.
**This repository is not exposed to the removed APIs** — `build-logic` uses no `CommonExtension<…>`
parameterization, no `applicationVariants`, no legacy variant API. The migration is a version-and-plugin
problem, not a rewrite. Hilt, Room, protobuf, ktlint and Kover all need compatibility confirmed.

## Decision

**Stay on `compileSdk` / `targetSdk` 36 and build Phase 2.** Upgrade afterwards.

There is no deadline pressure: Google Play's target-API requirement for Android 17 falls around
mid-2027, and this app is not published.

## The trigger

Revisit when **detekt is stable on AGP 9** — a released 2.x, not an alpha. That is the single blocking
condition; everything else on the list above is routine.

Two other items are queued behind the same upgrade and should be done in the same pass:

- **ADR-0010** — retry dependency locking. Its failure is a disagreement between two Gradle resolutions
  over variant selection, and a major version of Gradle and AGP is the most likely place for that to
  change.
- **PLAY-001 revalidation** against targetSdk 37's background-audio enforcement, which is a device test
  rather than a code change if Phase 2 is built to spec.

## Consequences

- Phase 2 is written against API 36 and will need one validation pass, not a rework, when the target
  moves.
- A reader wondering why a 2026 project targets 36 has this document.
- The upgrade has a named precondition rather than a vague "later", so it can be checked rather than
  remembered.
