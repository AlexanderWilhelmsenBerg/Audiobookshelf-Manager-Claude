# BookWave roadmap

**Classification:** Active plan — canonical sequencing authority.

This is the only document that answers **“what should BookWave work on next?”** Detailed issue bodies, accepted ADRs, architecture documents, risks, reviews and experiments supply evidence and implementation detail, but do not independently change sequence.

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

## Now — live library and progress consistency

The playback/lifecycle correctness chain that previously preceded this section is complete on `main`: #142, #115, #141 and #139 closed through PRs #146, #149, #150 and #152. The next correctness work is therefore the server-derived Room projection below. These items do **not** change which book the device remembers or where active playback resumes.

### 1. Issue #133 — own one foreground realtime progress connection

**User problem/value:** accepted progress changed in another Audiobookshelf client should appear in BookWave while the app is foregrounded, even when Home is not the visible screen.

**Owner/boundary:** exactly one realtime connection/collector for the active authenticated profile while BookWave is foregrounded. Events continue through `LibraryRepository.writeProgress(...)`; Room remains the UI source of truth and the existing conflict boundary remains authoritative.

**Prerequisites:** the playback/cold-start correctness chain (#142, #115, #141 and #139) is completed on `main` through PRs #146, #149, #150 and #152. PR #93 remains the shared resume-freshness owner.

**Explicit non-goals:** no permanent background websocket; no direct seek of active playback from a socket event; no bypass of local-unsynced conflict protection; no second durable progress database.

**Automated proof:** Home and non-Home foreground ownership, exactly one collector, profile A -> B cancellation, late A event isolation, background disconnect, foreground reconnect/reconciliation, accepted newer remote progress, rejected conflicting local-unsynced progress, and no player seek.

**Device/platform acceptance:** while BookWave is foregrounded on multiple screens, change progress in the Audiobookshelf web client and verify Room/UI follows without a full library refresh.

**Effort / risk:** Medium / Medium because lifecycle ownership and profile isolation are correctness boundaries.

**Sequence:** first active correctness slice.

### 2. Issue #134 — hydrate a bounded recent-book hot set before bulk expansion

**User problem/value:** on large libraries, a remotely recent book can remain unavailable or stale until expensive catalogue expansion reaches it, delaying the book a listener is most likely to use.

**Owner/boundary:** use a bounded, deduplicated set of recent IDs as priority hints, prove active-profile visibility, then hydrate through the existing targeted fetch path. The normal full refresh remains authoritative for complete visibility, deletion and reconciliation.

**Prerequisites:** #133 first so foreground freshness ownership is settled. Keep the remembered-book owner established by PR #149 independent: recent server activity may improve Room/library usefulness but must never replace the device-local remembered book.

**Explicit non-goals:** no second refresh architecture; no use of listening-session timestamps as another progress source; no assumption that a recent-session ID is permission evidence; no new `/api/me/items-in-progress` contract without a supported-server capture/fixture and compatibility proof.

**Automated proof:** bounded request count, deduplication, item/tag visibility filtering, local-unsynced protection, skip already-current/current item work, no redundant later expansion, failure isolation, and final full-refresh reconciliation.

**Device/platform acceptance:** on a realistically large library, advance/play another book in the web client, foreground BookWave, and verify that useful book becomes current/usable materially before bulk expansion completes.

**Effort / risk:** Medium–High / Medium–High because latency optimization must not weaken profile visibility or conflict safety.

**Sequence:** after #133.

## Then — Android Auto and audio-routing hardening

PR #78 and [ADR-0029](adr/0029-android-auto-is-a-stable-audiobook-surface.md) define the settled Android Auto product/routing baseline. PR #93 owns resume freshness across all Play surfaces. Do not resurrect the withdrawn secondary-slot experiment or the old inference-based car-arrival continuity implementation as roadmap work.

The playback/cold-start prerequisite is now satisfied. After the current live library/progress work above, perform one consolidated DHU/real-car evidence pass for #126/#127/#128 before implementation in this lane.

### Evidence pass — issues #126, #127 and #128

**Value:** separate genuine BookWave defects from Android Auto host limitations before spending another implementation cycle on guesses.

**Owner/boundary:** measure existing redaction-safe route/button diagnostics, actual host rendering, pause cause and timing. Test evidence and head-unit evidence must remain distinct.

**Required evidence:**

- **#128:** capture `PLAY_WHEN_READY_CHANGE_REASON_*`, whether the pause is becoming-noisy or audio-focus loss, interval to car binding, active/selected output before/after, headset preservation and prior deliberate-pause state.
- **#126:** capture API level, car-bound state, route-known state, output roles/active output and computed `onCar`/`onHeadset`; change app code only if those facts prove an app-side state/publication defect.
- **#127:** confirm what the projected host actually renders and whether the shipped description-link-to-History affordance appears/works. Do not turn Media3's player queue/timeline into History.

**Effort / risk:** Small–Medium evidence effort / High risk of wasting work if skipped.

### Issue #100 — replace inferred `HeadsetHold` with route-heard ownership

**User problem/value:** a merely connected or formerly heard headset must not be resurrected after the listener deliberately moved playback elsewhere.

**Owner/boundary:** one generation/book-bound record of the route actually carrying BookWave playback, with explicit listener output choice outranking inferred system-policy state until stronger evidence exists.

**Prerequisites:** evidence pass; stable Step-2 ownership on `main`.

**Non-goals:** do not relabel ambiguous classic A2DP as headset/car to simplify tests; do not add another sticky inference boolean; Car remains Automatic rather than a guessed dashboard endpoint.

**Proof/acceptance:** pure precedence/generation/disconnect tests plus physical headset -> speaker -> car, merely-connected headset, explicit headset choice and multiple-candidate routing sequences.

**Effort / risk:** Medium–High / High.

**Sequence:** first Android Auto/routing implementation after evidence.

### Issue #128 — preserve intentional headset playback when the car arrives

**User problem/value:** connecting Android Auto must not stop a book that should continue through the headset.

**Owner/boundary:** handle the measured pause cause using trustworthy route-heard ownership from #100 or equivalent current-main evidence.

**Prerequisites:** #100 and measured pause cause/timing.

**Non-goals:** never auto-resume onto phone speaker or car; never resume a deliberately paused book; do not restore the withdrawn `CarArrivalContinuity` implementation wholesale.

**Proof/acceptance:** regression must fail when continuity handling is removed; physical headset + car sequence required.

**Effort / risk:** Medium / High because an incorrect auto-resume is worse than remaining paused.

### Issue #99 — make Android Auto browse invalidation shape-aware

**User problem/value:** meaningful browse membership changes must refresh without stale profile content or N-full-library-read fan-out.

**Owner/boundary:** one profile-bound browse snapshot per invalidation sweep; compare shape/membership, including same-count/different-member cases.

**Prerequisites:** PR #78 is merged; preferably after routing work so `PlaybackService` changes do not overlap unnecessarily.

**Non-goals:** no root redesign, routing redesign or resume-freshness policy.

**Proof/acceptance:** one-snapshot orchestration, dynamic series/author nodes, Downloads, Recently added, Listen again, Discover, Continue, same-count membership changes and hard profile-switch invalidation; DHU/real host verifies visible refresh separately.

**Effort / risk:** Medium / Medium.

### Issue #130 — phone-versus-car media-button priority

**User problem/value:** skip controls should remain primary on the phone, while Car/Headset deserve primary slots only while an Android Auto controller is actually bound.

**Owner/boundary:** media-button layout based on real car-controller binding state, not presence of a car-like audio route.

**Prerequisites:** settled Android Auto service path after the earlier routing work.

**Non-goals:** no secondary-slot experiment; keep `SLOT_BACK` occupied in every supported combination.

**Proof/acceptance:** layout matrix across car-bound/unbound and action visibility combinations; phone notification plus DHU/real-car confirmation.

**Effort / risk:** Small–Medium / Medium.

### Conditional closure — issues #126 and #127

- **#126:** if BookWave computes and publishes the correct Car state but the projected host still ignores/caches/renders the icon indistinguishably, treat it as a measured host limitation. Do not accumulate workarounds merely to force a visual toggle.
- **#127:** if projected Android Auto does not expose the History affordance, record the platform/product limitation and keep an honest browse/navigation path. Never fake History into Media3's queue or redefine `seekToDefaultPosition()` as navigation.

## Download reliability and recovery lane

The audit (#104) is complete and BW-DL-02/#107 landed through PR #136. The active implementation sequence starts from the concrete recovery issues rather than reopening the audit.

This lane may proceed independently when it does not collide with playback/service ownership work.

1. **#108 — correct Pause / Resume / Retry actions.** User value: each recovery row action must match what will actually happen. Depends on completed #107. Test the presentation-state/action matrix and verify partial data is preserved. **Effort/risk:** Small / Low–Medium.
2. **#109 — project WorkManager waiting/retry state into Downloads UX.** User value: distinguish automatic retry/waiting from terminal failure. Depends on #107 and is recommended after #108. WorkManager remains transient execution truth; do not mirror it into Room. **Effort/risk:** Medium / Medium.
3. **#112 — explicit discard-partial recovery.** Eligible after #108 and can proceed independently of #109. It is secondary, destructive and confirmed; Retry/Resume preserves partials by default. **Effort/risk:** Small–Medium / Medium.
4. **#120 — live queue/progress in Downloads and notifications.** Depends on #107-#109 so screen/notification states share the same truthful presentation model. Test multiple active downloads, process recreation and privacy-safe notification behavior. **Effort/risk:** Medium / Medium.
5. **#110 — removable/secondary storage correctness.** Keep after the core recovery/execution model so volume loss/return is expressed through stable ownership rather than another state machine. Physical removable-storage evidence is required. **Effort/risk:** High / High.
6. **#111 — device-wide destructive removal semantics.** Do only after the physical-copy/profile-owner product decision is explicit. Destructive operations must not mistake one profile's request for permission to destroy another profile's usable device copy. **Effort/risk:** Medium–High / High.

## Android system surfaces

Start these only after playback ownership is stable enough that every surface can delegate rather than copy policy.

1. **#114 — semantic Android action contract.** Establish stable actions/deep links that delegate Continue/resume to the remembered-book owner from PR #149 + the PR #93 resume-freshness owner. No credentials/server addresses in external intents. Test cold/warm launch, malformed/unauthorized IDs and delegation. **Effort/risk:** Medium / Low–Medium.
2. **#117 — home-screen widget** and **#118 — Quick Settings tile.** Build as projections/controllers over #114 and existing durable owners; neither gets an independent socket or resume algorithm. Test process death, profile/privacy state and Android surface lifecycle. **Effort/risk:** Medium each.
3. **#116 — wired/Bluetooth headset automation.** Follow #114 and the routing hardening where relevant so automation acts through settled playback/routing semantics rather than becoming another owner. Physical headset acceptance required. **Effort/risk:** Medium–High / High.
4. **#124 — scheduled automatic sleep.** The #139 prerequisite is satisfied by merged PR #152; preserve one sleep-timer owner and test time windows, restart, timezone/DST and manual precedence. **Effort/risk:** Medium / Medium.
5. **#119 — Garmin evaluation/custom surface.** First validate the built-in Control Phone path. Add custom Garmin work only for a demonstrated gap, using #114 where semantic actions are needed. **Effort/risk:** investigation first; implementation risk depends on evidence.

## Maintenance and non-sequencing backlog

- **#101** remains open low-risk display cleanup: centralize the existing `Series #sequence` label formatting shared by Android Auto browse rows and playback-session metadata without changing series ownership or ordering.
- **#135 / BW-DEP-01 is open** and is owned separately by the Build & Dependencies Agent. `docs/latest-stable-upgrade-plan.md` remains its detailed execution plan. Isolated migration slices have already landed through PRs #154–#158; the issue stays open until the staged migration is complete. Dependency novelty does not outrank the correctness sequence above.

## Historical boundaries — completed, not active work

These are retained because they define ownership or explain why older roadmap wording must not return:

- **PR #78 — merged:** Android Auto/routing finalization and ADR-0029. The secondary-slot experiment and inference-heavy car-arrival continuity attempt are historical evidence, not future roadmap items.
- **PR #93 — merged:** one shared resume-freshness owner: standard Media3 Play -> `ResumeFreshnessPlayer` -> `ResumeFreshnessCoordinator` -> service-owned player. Do not create parallel phone/headset/Android Auto position policy.
- **PR #98 — merged:** playback settings UI refresh; automatic sleep scheduling remained intentionally separate as #124.
- **PR #113 / issue #103 — merged/completed:** established the canonical documentation/roadmap authority. The old “committed PR chain” from that snapshot is no longer active.
- **PR #131 — merged:** Codex/build compatibility preparation and staged dependency plan.
- **PR #136 / issue #107 — merged/completed:** safe download recovery presentation state. #108/#109 now build on it.
- **PR #137 — merged:** Phase 0 dependency compatibility inventory for #135 only. It changed documentation, not dependency/toolchain versions, and did not complete #135; the execution issue remains open under the Build & Dependencies Agent.
- **PR #140 / issue #138 — merged/closed:** cold Media3 playback resumption now lets the first loaded-item Play pass through the existing shared freshness owner. Do not schedule #138 as active work or introduce another cold-resume algorithm.
- **PR #146 / issue #142 — merged/completed:** shutdown now captures the final session snapshot before player detachment.
- **PR #149 / issue #115 — merged/completed:** one durable per-profile, device-local remembered audiobook identity now owns which book this device remembers.
- **PR #150 / issue #141 — merged/completed:** cold/background Android Auto can preserve and resolve remembered media without inventing a second playback-position owner.
- **PR #152 / issue #139 — merged/completed:** History projects one clear stop marker for sleep-timer expiry while preserving ordinary Pause semantics.
- **PR #143 — merged after #140:** Loopbound/manual-APK build packaging only; it did not implement the correctness issues that followed.
- **PR #144 — merged:** reconciled the canonical roadmap with the repository state at that time and retired the already-settled Android Auto secondary-slot experiment from future work.
- **PR #153 / issue #132 — merged/completed:** refreshed the roadmap after the playback correctness chain completed and closed the documentation-reconciliation follow-up. Do not restore #132 as active backlog.
- **Issue #104 — completed:** download/offline recovery audit; its concrete child issues now own implementation.

## Last — iOS, deliberately after Android correctness

Do not pull iOS work forward to avoid Android lifecycle, routing, library or download correctness. Shared code is justified only where it preserves a proven behavioral contract without forcing shared UI or platform adapters.

1. **#121 — narrow Kotlin Multiplatform portability spike.** Prove a small model/pure-domain slice on JVM + iOS simulator; no wholesale KMP conversion and no shared UI.
2. **Native iOS shell and authentication.** SwiftUI shell, Audiobookshelf sign-in, secure credentials and profile/account switching; no playback yet.
3. **Read-only library.** Books, authors, series, shelves, details, search, artwork and useful caching. Use this stage to re-evaluate whether shared code is actually reducing duplicated meaning.
4. **Native Apple playback.** AVFoundation/native Apple audio session, background audio, Now Playing/remote commands, chapters/seek/speed/interruption handling.
5. **Progress/session correctness.** Deliberately port BookWave's proven ownership, acknowledged progress, sync, resume freshness, intentional rewind and offline behavior as product contracts rather than Android implementation details.
6. **Downloads/offline.** Native iOS storage/background transfer while preserving authorization versus physical-file ownership semantics.
7. **Apple system integrations.** App Intents/Shortcuts, WidgetKit, Spotlight/Siri-facing actions where useful, all delegating to native/shared owners rather than duplicating playback policy.
8. **#123 — purposeful Live Activity only where it solves a distinct user problem.** Ordinary audiobook playback already has system Now Playing.
9. **#122 — CarPlay only after native playback and progress correctness are proven.** Build a platform-native CarPlay product surface; do not mechanically reproduce Android Auto.
10. **Parity and polish.** Pursue value-based parity, accessibility, performance and platform fit only after the native correctness layers are trustworthy.
