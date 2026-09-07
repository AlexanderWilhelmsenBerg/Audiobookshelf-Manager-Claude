# Architecture overview

**Classification:** Current contract.  
**Current as reviewed:** 2026-09-07.

This is the current architectural map of BookWave. Historical phase documents remain useful evidence, but
this file no longer describes only the original Phase 0 vertical slice.

## Shape

```text
Android/system surfaces
  :app UI/navigation/WorkManager wiring
  :playback Media3 service + Android Auto + routing + sleep timer
          │
          ▼
       :domain                     Kotlin/JVM policy + repository contracts
          │
          ▼
      :core:model                  Kotlin/JVM value/domain model, zero project deps

Repository implementations/adapters
  :data:auth       ─┐
  :data:library     ├─► :domain / :core:model
  :data:downloads   │
  :data:settings   ─┘
        │
        ├─► :core:network          Retrofit/OkHttp + Audiobookshelf DTOs/gateways
        ├─► :core:database         Room + migrations/schemas
        └─► :core:datastore        Proto/settings + secure local storage

Shared support
  :core:common       clock, dispatchers, redacted logging/event log
  :core:designsystem Material/Compose UI primitives and theme
  :core:testing      shared test doubles/helpers
```

See [`module-boundaries.md`](module-boundaries.md) for the dependency rules and exact boundary rationale.

## Rules that shape the application

### 1. Durable local state is the read source for cached product surfaces

Library/settings/download UI should observe repositories backed by Room/DataStore rather than bind itself to
one network response. Refresh/realtime work updates evidence/state through repository seams; a transient
network failure must not be allowed to blank already-valid local content merely because the last request
failed.

Not every live playback fact belongs in Room, but every system surface that must survive process death needs
a durable/reconstructable projection rather than an in-memory UI singleton.

### 2. Types and Gradle boundaries enforce dependency direction

- `:core:model` has no project dependencies.
- `:domain` is JVM-only and cannot import Android framework types.
- platform/storage/wire types are mapped before they cross into domain/UI contracts.
- Android Media3, WorkManager, Room and DataStore implementation concerns do not become domain policy.

This is why future iOS research can evaluate sharing selected model/domain policy without first rewriting the
Android application or sharing UI.

### 3. Policy has one owner

When a rule affects correctness across several surfaces, put it behind one named policy/use-case/service
boundary and make every surface enter there.

The playback architecture is the strongest example: app Play, notification, headset and car controls must
not grow separate resume algorithms. See [`playback.md`](playback.md).

The same principle applies to profile authorization, download ownership, sorting, cleanup and future system
actions.

### 4. Profile identity travels with work that owns it

An asynchronous operation may run after the active UI profile changes. Network/session/download/progress work
therefore carries the profile that authorized/owns the operation instead of resolving "the active profile"
late and silently crossing an account boundary.

Content visibility remains profile-filtered even where physical device resources are shared. Downloads are
the important example: one physical copy can be referenced by several authorized profiles, while progress
and visibility remain profile-specific.

### 5. Realtime/event sources are evidence, not uncontrolled authority

Socket events, REST responses, system callbacks and media-controller commands each report different facts.
Receiving a fact does not automatically authorize a destructive state change or seek.

The owning domain/service policy decides what the evidence means.

### 6. Privacy is structural where practical

Logging uses typed/redacted fields rather than relying on developers to remember which strings are safe.
Profile access is filtered before UI/media surfaces receive content. Secret/token implementations remain
behind auth/storage boundaries. A future widget, shortcut or car surface does not get an exception merely
because it is outside the normal app screen.

## Concern ownership

| Concern | Current home |
| --- | --- |
| Result/error/value models | `:core:model` |
| Clock, dispatchers, redacted logging | `:core:common` |
| Domain repository contracts and policy | `:domain` |
| Authentication/profile/token implementation | `:data:auth` + secure datastore/network seams |
| Library/progress/bookmark/history implementation | `:data:library` |
| Download manifest/files/verification/storage | `:data:downloads` |
| Playback/appearance/device settings implementation | `:data:settings` |
| Room schema/migrations | `:core:database` |
| Proto/settings/secure local persistence | `:core:datastore` |
| Audiobookshelf HTTP/wire mapping | `:core:network` |
| Media3 player/session/Android Auto/routing | `:playback` |
| Compose navigation and screen presentation | `:app` |
| Shared Compose theme/primitives | `:core:designsystem` |

## Room and identity

Remote identities are server-scoped, not globally unique. Persistent rows retain the server/profile context
needed to prevent a book, progress row or permission grant from one server/account being mistaken for
another.

Room schema exports and explicit migrations are part of the architecture, not release paperwork. A feature
that requires durable schema change must ship its migration/evidence with the change; destructive migration
is not an ordinary escape hatch.

## Downloads: device bytes, profile authorization

The download architecture intentionally separates:

- the **physical book copy** on this device, keyed by server/item;
- the set of **profiles that requested/are authorized to use** that copy;
- each profile's progress;
- WorkManager's transient execution state;
- the durable manifest/file state.

Future recovery UX must preserve those separations. In particular, WorkManager waiting/backoff should not be
persisted into the manifest merely so the UI can display it, and a file existing on disk does not authorize a
profile that cannot access that book.

## Playback

See [`playback.md`](playback.md). The lasting direction is:

- one logical book timeline;
- serialized session/profile ownership;
- realtime/REST/acknowledgements as evidence;
- one resume-freshness owner (pending PR #93);
- future local remembered-book identity separate from server progress recency;
- system surfaces call the playback owner rather than implementing playback policy.

## Android Auto

Android Auto is part of the playback/media-session architecture. The host draws its UI; BookWave supplies a
browse hierarchy, metadata and media-session actions. PR #78 is the current committed finalization and carries
ADR-0029 until it merges.

Car/head-unit presentation claims require DHU/real-car evidence. A JVM test of a `MediaItem` cannot prove a
vehicle rendered it.

## Feature UI modules

Feature UIs remain packages in `:app`. This is a current choice, not unfinished scaffolding. Create another
Gradle module only when present coupling/ownership/build evidence makes the boundary valuable.

## Future portability

The roadmap's iOS foundation starts by testing whether `:core:model` and selected pure domain policy provide
real Kotlin Multiplatform reuse. It explicitly does **not** assume shared UI, Media3, WorkManager, Room,
Android routing or Apple playback should be made common.

See [`../roadmap.md`](../roadmap.md) for sequencing rather than using this architecture overview as a backlog.
