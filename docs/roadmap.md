# BookWave roadmap

**Classification:** Active plan — canonical sequencing authority.  
**Roadmap issue:** #103 (`BW-DOC-01`).

This is the only document that answers **"what should BookWave work on next?"** Detailed child plans, ADRs, risks, reviews and experiments provide evidence and implementation detail but do not independently change priority.

## How to use this roadmap

Each implementation item states the problem/opportunity, user value, scope, exclusions, dependencies, architecture seams, server/API assumptions, storage implications, privacy/security, automated test level, device/platform acceptance, effort, risk and sequence.

A fresh implementation worker should be able to start from an item ID, read the linked ADR/risk/issue material, and implement that slice without rediscovering the project's history.

## Now — correctness, regressions and current commitments

### Committed PR chain

These are near-term committed implementation, not speculative roadmap work and not invitations for competing implementations:

1. **PR #78 — Android Auto/routing finalization**
2. **PR #93 — unified resume freshness**
3. **PR #86 — Appearance inline controls**
4. **PR #98 — Playback settings UI refresh**

Planning work may run alongside them, but must distinguish what exists on `main` from what exists only on an open PR.

### BW-DOC-01 / issue #103 — Establish canonical documentation index and roadmap

**Problem/opportunity:** detailed historical documents are still easy to mistake for current architecture or backlog.  
**User/project value:** a fresh worker can identify current contracts and executable work without chronological archaeology.  
**Scope:** documentation index, this roadmap, current playback architecture summary, status/authority banners, architecture corrections, risk-registration cleanup and historical classification.  
**Out of scope:** runtime behavior, feature implementation, deleting useful engineering history.  
**Dependencies:** none.  
**Architecture seams:** documentation of all modules and accepted boundaries only.  
**Server/API assumptions:** unchanged.  
**Storage/migration:** none.  
**Privacy/security:** preserve current threat-model and redaction reasoning.  
**Automated test level:** docs/link/current-state review; repository docs checks if present.  
**Device acceptance:** none.  
**Effort:** Medium.  
**Implementation risk:** Low runtime / medium documentation risk.  
**Sequence:** active now.

### BW-DL-01 / issue #104 — Audit actionable download and offline recovery UX

**Problem/opportunity:** download failures, pause, storage/network constraints and repair states have accumulated incrementally; before adding widgets or more automation, BookWave needs a truthful user-facing recovery model.  
**User value:** offline listening failures become understandable and safely actionable without destroying partial or valid media.  
**Scope:** audit current download state owners and UX for failed, paused, constrained, storage-unavailable, partial, verification-failed, manifest/file disagreement, authentication/server, process-death and missing-file states; split findings into independent implementation slices.  
**Out of scope:** production behavior changes in the audit; download widget implementation; broad coordinator rewrite without evidence.  
**Dependencies:** none for audit.  
**Architecture seams:** download manifest, WorkManager/coordinator, storage volume, network policy, profile authorization, local availability, UI actions.  
**Server/API assumptions:** Audiobookshelf remains source for authorization/library/media URLs; local repair cannot assume server capabilities it does not expose.  
**Storage/migration:** audit must state migration implications for any future state addition; never delete files merely because a newer UI cannot classify them.  
**Privacy/security:** physical-file existence does not grant profile authorization; diagnostics/system surfaces follow current privacy rules.  
**Automated test level:** map future slices to domain, storage/manifest, WorkManager, repository and UI tests.  
**Device acceptance:** removable storage, offline/airplane mode, network policy, process death, low storage and offline playback as applicable.  
**Effort:** Medium audit.  
**Implementation risk:** Low audit / Medium–High future repair behavior.  
**Sequence:** active now in parallel with BW-DOC-01.

## Next — small high-value work on settled architecture

### BW-PLAY-01 — Persist per-profile local remembered audiobook identity

**Problem/opportunity:** current remembered-book selection is derived from the most recently updated unfinished server progress row. A remote device can therefore change which book this phone thinks it last listened to.  
**User value:** Continue/resume restores the book this phone actually used, surviving remote activity, restart, profile switching and offline operation.  
**Scope:** one durable local remembered-book identity per profile; update it only from unambiguous local playback ownership; use it for startup/profile restore and system resume surfaces.  
**Out of scope:** resume-position freshness; PR #93 owns the position decision. No server-timestamp fallback that pretends remote recency is local ownership.  
**Dependencies:** PR #93 should merge first so "which book?" and "which position?" remain separate contracts.  
**Architecture seams:** domain remembered-book policy, profile restore, playback service/session ownership, Android Auto recent/Continue, future system actions/widgets.  
**Server/API assumptions:** none; deliberately independent of server progress timestamps.  
**Storage/migration:** durable profile-keyed local ID in existing device storage. Do not backfill from `progress.updatedAt`; null is safer than invented ownership.  
**Privacy/security:** store opaque local identity only; no title/server host required. Respect profile removal.  
**Automated tests:** pure ownership policy, persistence/migration, process-death, profile-switch and offline tests.  
**Device acceptance:** listen to A locally, advance B remotely, restart/offline/profile-switch and confirm A remains this device's remembered book until local playback changes it.  
**Effort:** Medium.  
**Risk:** Medium–High because it changes restore ownership.  
**Sequence:** first playback-correctness slice after #93.

### BW-AUTO-01 — Derive and diff Android Auto browse-tree shape

**Problem/opportunity:** profile-boundary invalidation must remain safe, but invalidating/rereading the entire tree for every library change would waste work and can cause repeated complete library reads for dynamic series/author nodes.  
**User value:** Android Auto updates correctly after meaningful library changes without stale profile content or unnecessary database work.  
**Scope:** derive one immutable browse-tree shape per sweep; diff old/new; invalidate only affected parents; snapshot the accessible library once per sweep; handle dynamic series/author nodes and fixed shelf availability.  
**Out of scope:** redesigning the stable Android Auto root; resume-freshness logic; changing ADR-0029 product decisions.  
**Dependencies:** PR #78.  
**Architecture seams:** `MediaLibrarySession`, `AutoLibrary`, library/shelf derivation, profile boundary.  
**Server/API assumptions:** no server changes.  
**Storage/migration:** none; derived in memory.  
**Privacy/security:** profile switch must clear/invalidate old-profile dynamic nodes atomically enough that a cached head unit cannot continue exposing another profile's titles.  
**Automated tests:** shape diff for empty/non-empty, series and author add/remove, Downloads, Recently added, Listen again, Discover, Continue and profile switch. Assert one library snapshot per sweep in the orchestration test.  
**Device acceptance:** DHU and real car verify visible changes without reconnecting the controller.  
**Effort:** Medium.  
**Risk:** Medium.  
**Sequence:** after #78; can proceed independently of widgets/iOS.

### BW-SYS-01 — Define BookWave system action/deep-link contract

**Problem/opportunity:** launcher shortcuts, widgets, App Actions, notifications and future iOS system surfaces need common conceptual destinations. Independent private routes would duplicate navigation and eventually playback/resume policy.  
**User value:** system integrations behave consistently and future surfaces are cheaper to add.  
**Scope:** define semantic actions/destinations such as ContinueListening, OpenCurrentBook, OpenBook, OpenLibrary, OpenSearch, OpenDownloads, OpenHistory, OpenPlayer and future sleep-timer actions; add one Android action router/deep-link boundary.  
**Out of scope:** implementing every system surface; custom voice parsing; moving #93 resume policy into UI routing.  
**Dependencies:** #93 and preferably BW-PLAY-01 for truthful Continue ownership.  
**Architecture seams:** app navigation, playback controller/session, domain remembered-book/resume policy.  
**Server/API assumptions:** none for internal actions. Public web/App Links can be added later without changing semantic action names.  
**Storage/migration:** none.  
**Privacy/security:** no credentials/server addresses in URIs, shortcuts or external intent payloads; validate externally supplied identifiers against current profile access.  
**Automated tests:** action parsing/routing, malformed/unauthorized IDs, cold-start routing, resume action delegates to playback owner.  
**Device acceptance:** cold/warm app launch from shortcuts/deep links and locked-profile behavior.  
**Effort:** Medium.  
**Risk:** Low–Medium.  
**Sequence:** before shortcuts/widgets/App Actions.

### BW-SYS-02 — Add launcher shortcuts

**Problem/opportunity:** frequent destinations require opening the app and navigating several taps.  
**User value:** fast access from the launcher.  
**Scope:** initial stable shortcuts: Continue Listening, Search, Downloads, History. Keep the action stable even when the underlying remembered book changes.  
**Out of scope:** dynamic title-bearing shortcut by default; widget; custom Assistant architecture.  
**Dependencies:** BW-SYS-01; Continue should use BW-PLAY-01/#93 policy rather than deriving its own state.  
**Architecture seams:** launcher shortcut metadata → action router.  
**Server/API assumptions:** none.  
**Storage/migration:** none.  
**Privacy/security:** avoid current book/title in launcher metadata by default; locked-profile action must not leak content.  
**Automated tests:** shortcut intents map to semantic actions; static XML/resources validate.  
**Device acceptance:** at least one Pixel/AOSP-style launcher and Samsung launcher; cold and warm launch.  
**Effort:** Small.  
**Risk:** Low.  
**Sequence:** after BW-SYS-01.

### BW-WIDGET-01 — Minimal Resume / Now Playing widget

**Problem/opportunity:** the highest-value Home-screen surface is a one-glance way to continue the current/remembered audiobook.  
**User value:** resume and inspect core playback state without opening the app.  
**Scope:** one Jetpack Glance widget with compact and normal responsive layouts; cover/title/author/progress where privacy permits; play/resume and open-book/player actions. State is a passive projection of existing durable/domain state.  
**Out of scope:** Continue multi-book shelf, sleep widget, download-count widget, independent resume freshness/network socket.  
**Dependencies:** BW-SYS-01, BW-PLAY-01 and #93.  
**Architecture seams:** durable playback/library projection, Glance receiver, system actions.  
**Server/API assumptions:** widget shows best locally known state; no dedicated network correctness owner.  
**Storage/migration:** may introduce a small durable widget projection if repository reads are unsuitable, but it cannot become playback source of truth.  
**Privacy/security:** locked profile renders generic locked state without title/cover/progress; profile changes remove old-profile content immediately.  
**Automated tests:** projection logic, process death, profile switch, offline, downloaded/not-downloaded, locked profile, stale/updated durable state.  
**Device acceptance:** resize and update behavior on at least Pixel/AOSP and Samsung launchers; process killed; offline; profile switch.  
**Effort:** Medium.  
**Risk:** Medium.  
**Sequence:** first and only initial widget.

### BW-SLEEP-01 — Automatic sleep schedule

**Problem/opportunity:** PR #98 deliberately excludes automatic nightly sleep behavior. Implementing platform scheduling before product rules would make ambiguous behavior permanent.  
**User value:** listeners who routinely fall asleep to audiobooks get their normal timer automatically during a chosen nightly window.  
**Scope:** product state machine first: enabled, local start/end, eligibility window, automatic/manual timer precedence, manual cancel suppression for current window, restart/reboot/timezone/DST reconciliation. Implement Android mechanism only after state-machine tests pass.  
**Out of scope:** #98 UI refresh; second competing timer; waking an idle app nightly when nothing is playing.  
**Dependencies:** #98 for settings UI stability; existing sleep-timer domain.  
**Architecture seams:** playback service, sleep-timer owner, settings repository, clock/time-zone abstraction.  
**Server/API assumptions:** none.  
**Storage/migration:** schedule settings plus durable suppression/window identity if required. Sleep schedule remains device-wide like current sleep settings.  
**Privacy/security:** none beyond existing notification/lock-screen rules.  
**Automated tests:** disabled/normal/overnight windows, before/inside/after, crossing start while playing, near-end start, manual replacement/cancel, next-window reset, expiry + explicit replay, timezone/DST gap/overlap, process restart.  
**Device acceptance:** background/screen-off, process death, timezone change where practical, Bluetooth playback and normal sleep notification.  
**Effort:** Medium.  
**Risk:** Medium.  
**Sequence:** after #98; can run after the action foundation if a future widget/tile should control it.

## Later — larger product/platform integrations

### Richer History navigation and search

**Problem/value:** History is more useful to BookWave users as a way back to meaningful listening positions than as a raw audit log. Improve filtering, search/book navigation and deep-link entry points.  
**Dependencies:** BW-SYS-01 helpful.  
**Risk/effort:** Low–Medium / Medium.  
**Platform:** mostly platform-neutral domain/UI concept.

### Download/offline recovery implementation slices

Created only after BW-DL-01 completes. They must remain smaller than a general "downloads redesign" and preserve physical-file/profile-authorization semantics.

### Continue Listening multi-book widget

Consider after BW-WIDGET-01 proves process-death/profile/privacy behavior. A short active-profile shelf is the first expansion candidate; fixed-profile configurable widgets come later if privacy rules are clear.

### Search improvements

Evaluate unified title/author/series search, deep-linkable results and offline cached behavior. Do not invent server-side guarantees where local search is sufficient.

### Tablet/foldable layouts and accessibility

Treat adaptive layout and accessibility as product-quality work, not novelty features. Prioritize evidence from actual large-screen/large-font/TalkBack use over decorative responsive changes.

### Wear OS companion

Candidate only after the action/playback contracts are stable. Likely value: Continue/current-book controls and sleep timer; avoid a miniature library app unless usage evidence supports it.

## Foundations for iOS — deliberately slow

Android correctness remains ahead of the iOS port.

### BW-IOS-00 — KMP portability feasibility spike

**Problem/opportunity:** `:core:model` is dependency-free and much of `:domain` is Android-free, suggesting useful reuse; other modules contain Android/Hilt/Retrofit assumptions. We need evidence before committing to a shared-core strategy.  
**User/project value:** reduce duplicated correctness policy without destabilizing Android or forcing shared UI.  
**Scope:** compile a very small model/domain slice for JVM and iOS simulator, preferably model/value objects plus one pure playback policy such as resume freshness. Define portable interfaces only where required by the experiment.  
**Out of scope:** iOS product app, shared UI, wholesale KMP conversion, moving Media3/network/storage adapters.  
**Dependencies:** Android playback contracts (#93 and BW-PLAY-01) should be stable first.  
**Architecture seams:** `core:model`, selected pure domain policy, clocks/errors as needed.  
**Server/API assumptions:** none beyond existing domain contracts.  
**Storage/migration/privacy:** none for spike.  
**Automated tests:** same policy tests on JVM and iOS simulator target.  
**Acceptance gate:** Android unchanged; Swift can consume the shared API without unreasonable interop; shared code removes duplicated meaning rather than merely moving files.  
**Effort:** Medium.  
**Risk:** Medium architecture risk if over-expanded; keep spike narrow.

### iOS Stage 1 — Native SwiftUI shell and sign-in

Launch, connect/authenticate to Audiobookshelf, securely store credentials, switch profiles/accounts and show server/library identity. No playback.

### Stage 2 — Read-only library

Books, authors, series, shelves, details, search, cached data and artwork. This stage is the decision gate for whether shared API/domain code is actually saving work.

### Stage 3 — Native Apple playback

Use native Apple playback/audio-session/Now Playing/remote-command integration. Acceptance: stream, chapters, seek, speed, background audio, lock-screen/Control Center, interruptions. No clever cross-device reconciliation yet.

### Stage 4 — Progress/session correctness

Port BookWave's behavioral contracts deliberately: session ownership, acknowledged progress, server sync, resume freshness, intentional remote rewind, realtime evidence and offline handling. Android tests/ADRs become behavioral specifications where portable.

### Stage 5 — Downloads/offline

Native iOS storage/background transfer. Preserve profile authorization ownership and safe one-copy semantics where applicable; do not port WorkManager concepts mechanically.

### Stage 6 — Apple system integrations

App Intents/Shortcuts, WidgetKit, Siri-facing actions, Spotlight where valuable and system Now Playing. Conceptual actions align with Android but implementations remain platform-native.

### Stage 7 — CarPlay

Own product surface using Apple's current templates/entitlements and BookWave product lessons. Do not mechanically recreate the Android Auto tree.

### Stage 8 — Parity and polish

Pursue value-based parity only after core reliability.

### What may be shared

- model/value objects;
- pure domain policies and sorting/search rules;
- resume freshness and remembered-book policy;
- progress/session behavioral rules;
- smart-download policy where platform-independent;
- API-facing interfaces and possibly DTO/serialization later if proven worthwhile;
- clocks/time/logging abstractions where clean.

### What stays native

- Android Compose, Media3/ExoPlayer, MediaSessionService, Android Auto, Glance, WorkManager, Android routing/storage/credentials;
- SwiftUI, AVFoundation/Apple media session surfaces, WidgetKit, App Intents, CarPlay, native downloads/storage/Keychain.

## Experimental — evidence before product commitment

### BW-AUTO-02 — Observe the route actually carrying playback audio

Research/prototype a route-observation seam tied to genuine audio playback rather than adding more `HeadsetHold` inference flags. Distinguish transport from semantic role and allow `Unknown`.

Measure API 26–32, 33–35 and 36 behavior where practical for wired/headset, classic A2DP, speaker and projected car. No production routing redesign until the experiment demonstrates a trustworthy observation source.

### BW-AUTO-03 — Android Auto metadata/progress/head-unit experiments

Use `docs/android-auto-player-opportunities.md` as experiment evidence, not roadmap authority. Measure secondary action slots, session extras, completion metadata, MediaMetadata extras forwarding, richer series/chapter presentation and browse artwork/content-provider value on DHU and the real car.

A JVM test proves BookWave built metadata. It does not prove the car drew it.

### Quick Settings

Playback Play/Pause tile is currently low priority because Android's media surface already owns the job well. Re-evaluate a sleep-timer tile after BW-SLEEP-01 if it can display/control meaningful timer state faster than the notification.

### App Actions / Assistant

Bind stable BookWave actions to supported Android capabilities after BW-SYS-01 where locale/platform support makes them useful. Do not make Assistant capability coverage a dependency of the action architecture.

### Live Activities / Apple widgets in CarPlay

Research only after native iOS playback/system integration exists. Ordinary audiobook playback already has system Now Playing; a Live Activity needs a distinct user problem before becoming a feature.

## Active child plans

- [`dependency-upgrade-plan.md`](dependency-upgrade-plan.md) — dependency/Gradle upgrade sequencing. It remains active but does not supersede this roadmap's product/correctness sequence.
- [`android-auto-player-opportunities.md`](android-auto-player-opportunities.md) — experiment/research backlog; implementation requires roadmap promotion or explicit owner request.

## Completed and historical planning

`handover.md`, `closeout.md`, `gaps.md`, dated reviews, bug investigations and version-specific device tests contain important evidence but are not independent current backlogs. Their lasting rules should be represented by current architecture/ADRs/risks or promoted here when still actionable.
