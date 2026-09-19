# BookWave roadmap

**Classification:** Active plan — canonical sequencing authority.

This is the only document that answers **“what should BookWave work on next?”** `docs/product-decisions.md`
records definitive owner-approved product/UX decisions but does not set sequencing. Detailed issue bodies,
accepted ADRs, architecture documents, risks, reviews and experiments supply evidence and implementation
detail, but do not independently change sequence.

This roadmap describes work that is still open on current `main`. Completed PRs and issues are retained only as historical boundaries where they explain why an owner or experiment must not be recreated.

## How to use this roadmap

For each active major item, follow the linked issue for implementation detail and use this document for ordering. The roadmap records the user value, owner/boundary, prerequisites, non-goals, expected automated proof, required device/platform evidence, and approximate effort/risk.

BookWave correctness work follows these standing rules:

- preserve one owner for each cross-surface correctness policy;
- do not make Android Auto, widgets, notifications or other system surfaces invent their own playback truth;
- keep device-local remembered-book identity separate from resume-position freshness and from server-derived library progress;
- prefer measured platform evidence over speculative routing or host workarounds;
- keep Android correctness and ownership contracts ahead of iOS expansion;
- when an issue body names an already-merged prerequisite, treat the merge as satisfied rather than preserving a stale blocker.

## Now — Android correctness on current `main`

The foreground realtime and recent-book hydration slices that previously led this roadmap are already on
`main`: Forgejo PR #5 implemented issue #40, and PR #57 implemented and closed issue #41. Issue #40 is
still open in the tracker even though its implementation merged; that tracker state is stale and must not
cause the foreground-sync work to be recreated.

The active Android correctness work is now the remaining system/Android Auto behavior below. An open pull
request is not completion: #34 and #35 remain active until their implementation is merged and the required
device/host evidence is recorded.

### 1. Issues #34 and #35 — Android Auto player state and History affordance

**#34 — Car selected state.** The issue remains open. Preserve ADR-0029's routing truth and prove on API 33+
with the real projected host whether the state BookWave publishes is rendered as intended. Do not infer a
host limitation before the app-side publication path is proven on the current build.

**#35 — History affordance.** The issue remains open. Media3's player queue/timeline is not a History
navigation surface and must not be repurposed as one. Keep any player-to-History affordance within supported
Media3 metadata/navigation contracts and verify what the projected host actually exposes.

**Owner/boundary:** Android System & Auto, with Playback & Lifecycle review wherever playback truth or route
ownership is touched.

**Sequence:** complete and accept these focused issues before starting another speculative Android Auto
player workaround. Their current implementation is being reviewed separately; this roadmap does not mark
them complete merely because a PR exists.

### 2. Issue #10 — make Android Auto browse invalidation shape-aware

**User problem/value:** meaningful browse membership changes must refresh without stale profile content or
N-full-library-read fan-out.

**Owner/boundary:** derive one profile-bound browse snapshot per invalidation sweep and compare actual
shape/membership, including same-count/different-member cases.

**Prerequisites:** ADR-0029's stable browse/product structure is settled, and the route-ownership work in
#11/#36 is already merged. Keep this slice independent of #34/#35 player rendering and of resume-freshness
policy.

**Non-goals:** no root redesign, routing redesign or independent resume-position owner.

**Proof/acceptance:** one-snapshot orchestration, dynamic series/author nodes, Downloads, Recently added,
Listen again, Discover, Continue, same-count membership changes and hard profile-switch invalidation;
DHU/real-host evidence verifies visible refresh separately from JVM proof.

**Effort / risk:** Medium / Medium.

### 3. Issue #6 — retest headset Back/Previous on the current media-button layout

Issue #38 is complete through Forgejo PR #56, so the prerequisite media-button layout change is no longer a
reason to defer #6. Retest the reported headset Previous/Back failure on current `main` first. If it still
reproduces, map the system/headset action to BookWave's configured relative seek-back policy without
changing notification/Android Auto slot policy or inventing a second seek owner.

Physical headset acceptance is required because a JVM test can prove command mapping but not which transport
command a particular headset actually sends.

## Merged implementation awaiting physical/device acceptance

These are **not active implementation slices** unless acceptance fails. Their code has merged; what remains
is evidence that automated tests cannot supply:

- **#36 / Forgejo PR #60 — car-arrival headset continuity:** issue closed and the narrow
  audio-focus-loss-to-first-car-bind gate is on `main`. `docs/risks.md` R-106 remains the acceptance
  boundary until the repeat physical headset + car drive passes. Do not broaden it into generic
  resume-after-focus-loss behavior.
- **#38 / Forgejo PR #56 — phone/car media-button priority:** issue closed and the state-dependent layout is
  on `main`; phone notification plus DHU/real-car rendering remains device/host evidence.
- **#7 / Forgejo PR #58 — shake-to-extend lifecycle:** issue closed and the lifecycle/settings race is fixed;
  deliberate-shake behavior still requires real accelerometer checks in foreground, background and screen-off
  playback.

## Download reliability and recovery lane

BW-DL-03 / **#18 is complete through Forgejo PR #55**. Do not recreate its Pause / Resume / Retry action
mapping. The remaining sequence starts from the transient execution-state projection:

1. **#19 — project WorkManager waiting/retry state into Downloads UX.** Distinguish automatic retry/waiting
   from terminal failure while keeping WorkManager as transient execution truth. #18 is satisfied.
2. **#22 — explicit discard-partial recovery.** Eligible after completed #18 and may proceed independently of
   #19. It is secondary, destructive and confirmed; Retry/Resume preserves partials by default.
3. **#29 — live queue/progress in Downloads and notifications.** Follow #19 so screen and notification states
   share the same truthful execution/presentation model.
4. **#20 — removable/secondary storage correctness.** Keep after the core recovery/execution model; physical
   removable-storage evidence is required.
5. **#21 — device-wide destructive removal semantics.** Do only after the physical-copy/profile-owner product
   decision is explicit.

This lane may proceed independently when it does not collide with playback/service ownership work.

## Android system surfaces

Start these after the active Android correctness work above so each surface delegates to settled owners
rather than copying playback policy.

1. **#23 — semantic Android action contract.** Define stable actions/deep links that delegate Continue/resume
   to the existing remembered-book and resume-freshness owners. No credentials/server addresses in external
   intents.
2. **#26 — home-screen widget** and **#27 — Quick Settings tile.** Build as projections/controllers over #23;
   neither gets an independent socket or resume algorithm.
3. **#25 — wired/Bluetooth headset automation.** Follow #23 and current routing ownership; physical headset
   acceptance is required.
4. **Forgejo PR #63 / #33 — scheduled automatic sleep.** Implementation is ready for review around the
   existing sleep-timer owner: local same-day/overnight windows, persisted manual-cancel suppression,
   explicit-replay handling after natural expiry, deterministic timezone/DST policy, full-player projection,
   and expanded/compact media-control projection. A schedule-created timer still active at the window end is
   cancelled without pausing playback; manual timers remain independent. Physical notification, screen-off,
   Bluetooth and process/service acceptance remains required before the Android surface is considered proven.
5. **#28 — Garmin evaluation/custom surface.** First validate the built-in Control Phone path. Add custom
   Garmin work only for a demonstrated gap.

## Maintenance and non-sequencing backlog

- **#12** remains open low-risk display cleanup: centralize the existing `Series #sequence` label formatting
  without changing series ownership or ordering.
- **#42 / BW-DEP-01** is the Forgejo migration issue for the staged dependency program. The tracker currently
  shows it closed even though the migration plan is not complete. Treat
  `docs/latest-stable-upgrade-plan.md` as the detailed execution plan: Phases 4 and 5 are complete at the
  current compatible frontier, **Phase 6 is the next executable dependency lane**, and the Gradle 9 / AGP 9 /
  API 37 foundation remains gated by ADR-0011. Dependency novelty does not outrank the correctness sequence
  above.
- **#47–#50** are open UI/design audit or proposal work. Their source findings are useful evidence, but they
  do not independently change this roadmap's ordering and several explicitly require rendered/device
  validation before implementation scope is treated as settled.

Tracker inconsistencies are not sequencing authority. In particular, #40 is still open despite merged PR #5,
while #42 is closed despite an incomplete staged migration. Reconcile those tracker states separately rather
than making roadmap readers infer work from open/closed badges alone.

## Historical boundaries — completed, not active work

These entries exist to prevent completed ownership work from being recreated:

- **Forgejo PR #5 / issue #40 — merged:** one foreground realtime progress owner now lives at application
  foreground lifecycle scope. The still-open issue is tracker drift, not active implementation.
- **Forgejo PR #55 / issue #18 — merged/closed:** Pause / Resume / Retry download row actions are settled.
- **Forgejo PR #56 / issue #38 — merged/closed:** phone skips versus car-bound output-action priority follows
  actual car-controller binding.
- **Forgejo PR #57 / issue #41 — merged/closed:** bounded recent-book hydration runs before ordinary full
  expansion without replacing the authoritative refresh.
- **Forgejo PR #58 / issue #7 — merged/closed:** shake-to-extend registration now follows active-timer +
  persisted-setting state; physical sensor acceptance remains separate.
- **Forgejo PR #59 / issue #11 — merged/closed:** generation-bound route-heard ownership replaced the
  `HeadsetHold` inference stack. Do not reintroduce sticky inferred ownership.
- **Forgejo PR #60 / issue #36 — merged/closed:** measured car-arrival audio-focus loss has a narrow continuity
  gate tied to #11 ownership; generic focus-loss resumption remains out of scope.
- **Pre-migration GitHub PR #78 — merged:** Android Auto/routing finalization and ADR-0029. The retired
  whole-list output cycle and secondary-slot experiment are historical evidence, not future roadmap items.
- **Pre-migration GitHub PR #93 — merged:** one shared resume-freshness owner. Do not create parallel
  phone/headset/Android Auto position policy.
- **Pre-migration GitHub PR #131 — merged:** established the staged latest-stable dependency plan.
- **Pre-migration GitHub PR #136 / current Forgejo issue #17 — completed:** safe download recovery
  presentation state is the foundation for the remaining download lane.
- **Pre-migration GitHub PR #137 — merged:** Phase 0 dependency compatibility inventory only; it is historical
  measurement evidence, not a second live version ledger.
- **Pre-migration GitHub PR #149 / current Forgejo issue #24 — completed:** one durable per-profile,
  device-local remembered audiobook identity owns which book this device remembers.
- **Pre-migration GitHub PR #144 and #153 / current Forgejo issue #39 — completed documentation boundaries:**
  earlier roadmap reconciliation snapshots are history; this file remains the live sequencing authority.

## Last — iOS, deliberately after Android correctness

Do not pull iOS work forward to avoid Android lifecycle, routing, library or download correctness. Shared code
is justified only where it preserves a proven behavioral contract without forcing shared UI or platform
adapters.

1. **#30 — staged iOS foundation and selective KMP boundary.** Prove narrow portable model/pure-domain seams;
   no wholesale KMP conversion and no shared UI.
2. **Native iOS shell and authentication.** SwiftUI shell, Audiobookshelf sign-in, secure credentials and
   profile/account switching; no playback yet.
3. **Read-only library.** Books, authors, series, shelves, details, search, artwork and useful caching.
4. **Native Apple playback.** AVFoundation/native Apple audio session, background audio, Now Playing/remote
   commands, chapters/seek/speed/interruption handling.
5. **Progress/session correctness.** Port BookWave's proven ownership, acknowledged progress, sync, resume
   freshness, intentional rewind and offline behavior as product contracts rather than Android implementation
   details.
6. **Downloads/offline.** Native iOS storage/background transfer while preserving authorization versus
   physical-file ownership semantics.
7. **Apple system integrations.** App Intents/Shortcuts, WidgetKit, Spotlight/Siri-facing actions where useful,
   all delegating to native/shared owners.
8. **#32 — purposeful Live Activity only where it solves a distinct user problem.** Ordinary audiobook
   playback already has system Now Playing.
9. **#31 — CarPlay only after native playback and progress correctness are proven.** Build a platform-native
   CarPlay product surface; do not mechanically reproduce Android Auto.
10. **Parity and polish.** Pursue value-based parity, accessibility, performance and platform fit only after
    the native correctness layers are trustworthy.
