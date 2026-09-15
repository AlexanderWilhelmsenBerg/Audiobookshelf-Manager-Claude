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
- treat physical-device / DHU / car evidence as required whenever the acceptance claim depends on Android system or host behavior;
- keep iOS work last until the Android correctness and ownership seams are stable.

## Now — live library and progress consistency

The playback/lifecycle correctness chain that previously preceded this section is complete on `main`: #142, #115, #141 and #139 closed through PRs #146, #149, #150 and #152. The next correctness work is therefore the server-derived Room projection below. These items do **not** change which book the device remembers or where active playback resumes.

### 1. Issue #133 — own one foreground realtime progress connection

**User value:** progress changes made on other Audiobookshelf clients should become visible in BookWave while it is foregrounded without requiring a full refresh or a second playback-position owner.

**Owner / boundary:** Library & Sync. The realtime connection projects server progress into the existing Room-backed library state. It does not replace `ResumeFreshnessCoordinator`, `PlaybackService`, the per-profile remembered-book owner, or the normal full-library refresh.

**Prerequisites:** the playback/cold-start correctness chain (#142, #115, #141 and #139) is completed on `main` through PRs #146, #149, #150 and #152. PR #93 remains the shared resume-freshness owner.

**Non-goals:** do not introduce a second WebSocket owner per screen, do not write direct socket state into Compose, and do not let a server progress event choose the device's remembered book.

**Automated proof:** one foreground connection per signed-in profile; lifecycle connect/disconnect; reconnect/backoff/cancellation; mapping/compatibility tests; Room projection tests; no duplicate collectors/writers.

**Device evidence:** background/foreground transitions, network loss/restore, profile switch, and playback continuing while remote progress changes arrive.

**Effort / risk:** Medium–High / High because realtime lifecycle bugs can create duplicate writers and cross-surface freshness races.

### 2. Issue #134 — hydrate a bounded recent-book hot set before bulk expansion

**User value:** Home and recent/continue surfaces should become useful quickly after startup even when the user's Audiobookshelf library is large.

**Owner / boundary:** Library & Sync. Hydrate a bounded recent set into the same Room/library model used by the rest of the app; do not create a special UI-only cache or second progress owner.

**Prerequisites:** #133 first so foreground freshness ownership is settled. Keep the remembered-book owner established by PR #149 independent: recent server activity may improve Room/library usefulness but must never replace the device-local remembered book.

**Non-goals:** not a replacement for the full library refresh; not permission to invent undocumented server fields/endpoints; not a playback-resume algorithm.

**Automated proof:** bounded request/selection behavior; deterministic merge into Room; missing/deleted/unauthorized item handling; cancellation/profile switch; ordering/freshness tests; fixtures for every server contract used.

**Device evidence:** cold start against a realistically large library and a server where recently active books changed on another client.

**Effort / risk:** Medium / Medium–High because the value is latency, but correctness still depends on merge order and profile isolation.

## Then — Android Auto and audio-routing hardening

PR #78 and [ADR-0029](adr/0029-android-auto-is-a-stable-audiobook-surface.md) define the settled Android Auto product/routing baseline. PR #93 owns resume freshness across all Play surfaces. Do not resurrect the withdrawn secondary-slot experiment or the old inference-based car-arrival continuity implementation as roadmap work.

Treat the next pass as an **evidence-first routing/system-surface lane**. Several issues were written before the final PR #78 layout and need a device/DHU pass before code is assumed necessary.

### Evidence pass — issues #126, #127 and #128

Run these together on the current merged implementation before opening three independent code lanes:

- **#126 — Car output selection indication.** Verify that the Car action visually reports selected/active state on the phone and car surfaces with the current route truth. If host rendering is the only mismatch, record that rather than inventing another selection state owner.
- **#127 — History link / player navigation.** Verify the shipped description-link metadata on DHU/vehicle. The old request that the host-drawn queue button itself become a History button is not an app-controlled contract; do not reimplement that assumption.
- **#128 — intentional headset playback when the car arrives.** Reproduce the current failure on PR #78-era routing before changing policy. The deprecated inference-heavy continuity attempt remains retired.

**Owner / boundary:** Android System & Auto for evidence and host adapters; Playback & Lifecycle only if the reproduction proves the underlying playback truth is wrong.

**Automated proof before any fix:** targeted Media3/session/routing policy tests plus a regression that fails when the guarded behavior is reverted.

**Device evidence:** physical phone + headset + car/Bluetooth where the issue depends on route arrival; DHU where host rendering/browse behavior is sufficient.

### Issue #100 — replace inferred `HeadsetHold` with route-heard ownership

**User value:** unplugging or disconnecting a headset should not leave BookWave in a sticky inferred state that later blocks or redirects playback incorrectly.

**Owner / boundary:** Android System & Auto with Playback & Lifecycle review. Replace inference with explicit route-heard/route-left events feeding one routing owner; do not add a competing playback owner.

**Prerequisites:** reproduce #128 first because both touch car/headset arrival semantics. If #128 is already fixed by current route truth, #100 can be narrowed accordingly.

**Non-goals:** no automatic car-arrival playback policy beyond the accepted product contract; no second session/player.

**Automated proof:** route event state machine, repeated connect/disconnect, process recreation, and stale-route cleanup.

**Device evidence:** wired/Bluetooth headset and car connection permutations.

**Effort / risk:** Medium–High / High due to hardware/lifecycle behavior.

### Issue #128 — preserve intentional headset playback when the car arrives

After the evidence pass, implement only the smallest policy change needed for a confirmed current failure. The accepted direction is not “guess that the user is in the car”; it is “do not destroy intentional playback merely because another route appears.”

**Prerequisites:** evidence pass and #100 ownership decision.

**Automated proof:** current failing route sequence reproduced; regression around headset-playing + car-arrival; no auto-play from silence.

**Device evidence:** physical headset + vehicle/Bluetooth is required for acceptance.

**Effort / risk:** Medium / High.

### Issue #99 — make Android Auto browse invalidation shape-aware

**User value:** the car library should refresh when browse structure changes without rebuilding/invalidating it on every ordinary progress update.

**Owner / boundary:** Android System & Auto. Observe stable library/repository state and invalidate browse nodes based on structural snapshots; do not move library ownership into `PlaybackService`.

**Prerequisites:** PR #78 is merged; preferably after routing work so `PlaybackService` changes do not overlap unnecessarily.

**Automated proof:** no invalidation for progress-only changes; correct invalidation for add/remove/rename/reorder/visibility changes; profile isolation.

**Device evidence:** DHU browse tree refresh while library content changes.

**Effort / risk:** Medium / Medium.

### Issue #130 — phone-versus-car media-button priority

**User value:** when no car is connected, phone/notification controls should keep the expected skip actions; when a car is connected, the accepted output-action priority can take the constrained slots.

**Owner / boundary:** Android System & Auto. Treat this as session-layout projection, not a new playback policy.

**Prerequisites:** #126 evidence first because host rendering determines what is actually missing; keep PR #78's safety invariant that the back slot is never accidentally exposed as “restart the whole book.”

**Automated proof:** layout for car present/absent; notification/phone button preferences; command availability invariant.

**Device evidence:** notification/lock screen and DHU/vehicle.

**Effort / risk:** Medium / Medium–High because one shared MediaSession layout influences several surfaces.

### Conditional closure — issues #126 and #127

If current device/DHU evidence proves the merged implementation already satisfies the app-controlled part of either issue, close the issue with evidence rather than manufacturing code. If a host limitation remains, preserve it as a documented platform limitation/risk.

## Download reliability and recovery lane

The audit (#104) is complete and BW-DL-02/#107 landed through PR #136. The active implementation sequence starts from the concrete recovery issues rather than reopening the audit.

1. **#108 — correct row actions.** Make Pause / Resume / Retry reflect durable manifest + worker truth, not optimistic UI state. Add focused state-machine tests and verify process death / worker restart. **Effort/risk:** Medium / Medium–High.
2. **#109 — project WorkManager waiting/retry state.** Expose network/backoff/queued execution truth without replacing manifest/file truth. Test constraints, backoff and restart. **Effort/risk:** Medium / Medium.
3. **#112 — explicit discard-partial recovery.** Add the destructive recovery action only after retry/pause/resume semantics are correct. Test cancellation, cleanup and retry after discard. **Effort/risk:** Medium / Medium–High.
4. **#120 — active download queue and live progress.** Build on the settled execution projection so screen/notification surfaces observe one queue instead of inventing another. Test concurrent downloads, process death and notification permission states. **Effort/risk:** Medium–High / Medium–High.
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
- **#135 / BW-DEP-01 is open** and is owned separately by the Build & Dependencies Agent. `docs/latest-stable-upgrade-plan.md` remains its detailed execution plan. Several isolated migration slices have already landed (PRs #154–#158); the issue stays open until the staged migration is actually complete. Dependency novelty does not outrank the correctness sequence above.

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

These remain valid future product directions, but they should not start while Android correctness/system-surface ownership is still moving:

1. **#121 — staged iOS foundation / selective KMP boundary.** First isolate genuinely portable domain/data policy; do not force Android service/Media3/Room details into shared code. **Effort/risk:** High / High.
2. **#122 — native CarPlay audiobook surface.** Follow only after the portable playback/library contracts are stable; native host integration, not an Android abstraction transplanted to iOS. **Effort/risk:** High / High.
3. **#123 — Live Activity.** Useful only after sleep/download state is stable and intentionally shareable. **Effort/risk:** Medium–High / Medium–High.

The ordering rule is simple: finish Android correctness and establish stable portable seams first; then invest in the second platform.
