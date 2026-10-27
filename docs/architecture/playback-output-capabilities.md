# Playback output capabilities and evidence

**Classification:** investigation and implementation boundary for PLAY-002 / ROUTE-001/002.
**Baseline:** GitHub main `acce406c8ffe`, playback PR #294 `8425e9e4ade8`; 27 October 2026.

The ownership/lock/handoff execution plan is recorded in
[issue #289](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6445791504).
This document distinguishes capabilities used by the first route-loss safety slice from capabilities
that still require device investigation. It does not certify physical output or button attribution.

## Capabilities available in production

| Observation or request | What it establishes | What it cannot establish |
| --- | --- | --- |
| A unique current `LiveOutputEndpointRegistry` token | One observed live Android output appearance | Persistent physical identity after disappearance or process recreation |
| `AudioTrackRouteMonitor.currentRouteIds()` for the current playing track | Exact mapped endpoint when the result has one member | Human-heard sound, the identity of an unmapped remote mix, or a paused track's future destination |
| Framework media-policy route | Android's predicted media destination | This playing AudioTrack's sink |
| Requested preferred device / selected output | Requested destination and listener intent where explicitly supplied | Successful routing or permission to unmute an unverified transfer |
| Trusted car-controller request | Which controller issued the command | Whether a physical dashboard gesture or host startup behavior caused it |
| Virtual/system media key, including input ID -1 | Transport command and its order | Which headset, car or system surface physically produced it |
| Matching input/output display names | A diagnostic candidate | Physical identity or authority to transfer playback |

The route observer is installed through `providerForPlayer()` in normal playback. Its route callback
and in-memory route query are available when diagnostic recording is off. Recording controls export
and logging, rather than authorizing route-safety behavior. Media3's provider still creates the output;
the observer creates no extra AudioTrack or player and requests no audio focus.

`currentRouteIds()` distinguishes no measurable track (`null`) from a playing but unresolvable,
conflicting or multiple route (an empty set). Neither result proves a destination. The observer epoch
rejects a measurement made against a track that was replaced while the query was in progress.

`LiveOutputEndpointRegistry.reconcile()` retires an absent appearance and creates a new token when it
returns. Device names, iteration order and a sole A2DP entry must not bridge that identity boundary.
Persisted name-based known-device policy is a separate concept; see
[`playback-route-identity.md`](playback-route-identity.md).

## Build 2241 regression evidence

The retained diagnostic identifies combined source `476d53e1f6f0` (playback #294 plus auth #296).
The listener confirmed pressing **Pause then Play on the headset**, rather than powering it off/on.
The sequence is:

1. An actual playing track uses one mapped Bluetooth endpoint while selection is Automatic.
2. That endpoint disappears; replacement appearance tokens arrive and disappear.
3. The observer reports a playing speaker route before the noisy-event pause input, about 1.13 seconds
   apart. This is potential speaker spill; the duration of human-heard speaker audio was not reported.
4. Noisy/focus recovery rejects with `NoQualifyingHeadset`; first car bind has `NoPendingFocusLoss`.
5. External Pause/Play is accepted, but the playing track uses unmapped `RemoteSubmix` under projection.

The earlier successful build 2240 arrival retained its live endpoint. The route owner's retirement and
recovery gate's positive-non-headset clearing policy are unchanged between those playback revisions.
A new refactor regression therefore remains unproven; the observed safety/ownership gap is real.
Raw diagnostics, device identifiers, server data and media metadata are not repository fixtures.

## Safety slice and later product work

The first slice protects continuity of an exact **measured external endpoint**. It must not claim that
an Ambiguous A2DP category is a proven physical headset, or assign a media key to that endpoint.
It must not wait until car binding to protect against an already-observed route loss. Current actual
track evidence, player attachment and newer intent must be checked at the effect boundary.

The initial slice retains the existing guard's API 33+ boundary and wearable/A2DP-candidate eligibility.
It does not extend that policy to every USB/HDMI/dock output or certify older-platform routing. Those
platforms keep their existing Media3 focus/noisy behavior and remain in the broader acceptance matrix.

Existing components retain their responsibilities:

- The registry owns live appearances; the monitor owns actual-track measurement.
- Router/preference effects and `HeadsetRouteGuard` own serialized physical requests, pause and probe
  effects. Healthy repeated callbacks must not recreate listener intent or continually reassert a route.
- `RouteHeardOwnership` retains evidence provenance and book-generation scope. The car coordinator owns
  recovery eligibility and one-shot episodes, rather than another route-selection authority.
- #276 adds a user-facing verified lock; #277 adds explicit verified transfer and rollback. The safety
  slice alone is neither a persistent lock nor headset-button destination handoff.

An explicit Car/Automatic/other-output choice must supersede older continuity work. A fresh appearance
cannot silently inherit the old target; safe pause is preferable to an unsupported rebind. Retain one
Media3 player/session, Media3 focus, and existing progress/history/sleep/profile owners.

The temporary protection record is an effect-scope stamp using the existing book generation, selection
sequence and preference epoch; it is not a second listener-intent counter. Book/queue changes and player
reattachment retire it. The existing profile handover (`PlaybackController.handOver` / `stopAndFlush`)
pauses, flushes, stops and clears the queue before changing account context; that queue boundary retires
the book claim even when the incoming account selects the same book. This slice does not add another
profile authority or support continuing playback across an account switch.

## Investigation and physical acceptance still required

| Case | Current evidence | Required check |
| --- | --- | --- |
| Original headset remains live during Auto arrival | Earlier configuration-specific 2240 success | Retest current exact source, active and paused, including host autoplay on/off |
| Endpoint disappears/re-enumerates | 2241 failure with actual speaker/mix observations | Repeated stationary capture of route callback, safety pause, actual sink and human-heard result |
| Stable output association across appearances | No trustworthy association demonstrated | Test supported public APIs and permission denial; no name-based or address-export shortcut |
| Physical headset key attribution under projection | Virtual/system events in supplied capture | Compare real device events if available; leave Unknown when the platform supplies no identity |
| Dashboard gesture vs host-generated Play | Controller identity available, gesture provenance absent | Verify Auto behavior; explicit destination actions remain distinct from generic transport |
| Two same-name headsets | Registry unit coverage; physical acceptance pending | Confirm no stale/duplicate identity, unintended transfer or inherited autoplay |
| Guard with recording off | Normal observer integration exists | Runtime regression plus device check without recording; export remains opt-in |

No permission expansion, persistent identifier migration or physical identity claim is authorized by
the existence of an Android device address field. Such an extension needs a concrete capability result,
privacy/permission design and the existing repository/settings seam. The current safety slice introduces
none of those changes.

S01–S22 remain the acceptance ledger in
[`../testing/playback-scenario-coverage.md`](../testing/playback-scenario-coverage.md).
The new 2241 sequence must exercise production paths, first fail against the old behavior, then pass
with the guard; a policy test does not certify audible hardware behavior. #295 cold-start variants and
#288 expired-token recovery remain separate device/server acceptance work.
