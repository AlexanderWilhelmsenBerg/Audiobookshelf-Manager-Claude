# Remaining Android UI work — 2026-10-03

**Classification:** Source reconciliation and future acceptance plan, not rendered acceptance.
**Source snapshot:** GitHub main `81a06e1`; this table is dated source evidence.
**Reconciled:** 2026-10-04 through main `a20bb5b9`. [The roadmap](../roadmap.md) owns sequencing.
PRs #220/#222 have delivered the shared series/card slice; [2179 phone evidence](2026-10-04-phone-2179.md)
accepts selected compact/glow/large-text subcases. It does not close the broader #194/#195 audits or
#187's all-card-family scope. Inspect current callers before implementing a remaining proposal.
**Owner:** UI & Experience, with Test & Acceptance review.

Critical playback/progress/privacy defects take precedence. Under the owner's 2026-10-04 ordering, fix
the gesture/count bugs next while hardware acceptance remains pending, then return to performance/download
work. The author page is the first larger remaining UI slice. Preserve Material 3, repository-backed state,
remembered-book ownership, author-before-title shelf ordering and existing destinations.
The proposals in #194 do not authorize an authentication/navigation rebuild or changes to other Settings tabs.

## Owner-requested child issues — 2026-10-04

These began as planned scopes. The [browse delivery report](2026-10-04-browse-selection-counts.md) now records #227/#228 implementation and scoped results; #229 remains planned. PD-006 adds the browse/navigation behavior to LIB-002.
The original registration included no runtime changes; the subsequent browse fix adds no endpoint or schema change.

| Issue | Current source / report | Delivery boundary and test log |
| --- | --- | --- |
| [#227 gesture highlighting](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/227) | Owner: Books → Series → Books leaves Series highlighted although the pill returns to Books. The real stable-callback route confirmed that the settled-page listener captured the initial axis; PR #230 reads the current axis/callback. Controlled physical return/rapid/mixed-intent checks pass. | First small bug. Guard the actual pager/ViewModel callback path; keep taps/cancelled gestures/recreation/settled semantics and playback consistent. U-06-01–06: scoped implementation evidence in the dated report; incomplete physical criteria remain pending. |
| [#228 browse entity counts](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/228) | Main originally flattened represented books and used book plurals for every axis. PR #230 counts distinct displayed entity identities and localizes each noun; populated phone counts match the authorized cached scope. | Second small bug. Count books/series/authors/genres for the displayed authorized scope; retain uncapped source totals and partial/loading status. U-07-01–05: scoped implementation evidence in the dated report; incomplete physical criteria remain pending. |
| [#229 grouped author page](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/229) | Authors cards narrow Home to Books; existing AuthorRoute from Book details renders a flat author shelf. | First larger UI slice after performance/download work. Reuse the destination, add Series/Standalone projections and truthful completion, preserve genre focus and Back/privacy/offline boundaries. U-08-01–07, all NOT RUN. |

The author page changes the deliberate earlier in-place Authors behavior; its old source comments are
implementation history to reconcile in that future feature PR. Completion remains based on authoritative
progress and full accessible series membership, not a filtered/author-only subset. Preserve PD-005's subtle
inward green cue without checkmarks and provide readable/spoken completion information.

## Reconcile the first audit before implementing it again

| Issue / finding | Current source evidence | Next concrete slice and acceptance |
| --- | --- | --- |
| #195 accent contrast | `Theme.kt` still selects its foreground using a luminance midpoint. This identifies a helper weakness; it does not prove which shipped palettes fail. | Compare actual foreground/background contrast, cover primary/container roles and text-contrast settings, then render effective glass/artwork backgrounds. Keep palette semantics intact. |
| #195 compact player and motion | PR #200 already changed mini/full player presentation and motion (#182/#183 and parts of #177/#178). | Reproduce remaining clipping/clearance at the widths and font scales below before another layout change. Validate reduced motion and complete title access. |
| #195 download recovery | The state-owned actions and execution observer already landed through #108/#109/#120 and PRs #207/#209. PR #215 adds the missing Book observer/Pause wiring tests. | Finish R-119's physical Pause/restart/sharing/TalkBack checks; do not create another execution-state owner. |
| #195 connection status | `ServerStatusDot` still uses one circular shape and chooses reachable/unreachable colors with `isSystemInDarkTheme()`. | Add a visible cue beyond color and resolve status colors against the active app appearance. Verify offline/unknown/reachable/unreachable and spoken detail. |
| #195 whole-book seek meaning | `FullPlayer.kt` now supplies `player_book_progress` with elapsed/remaining values to the whole-book `ThinSlider`; chapter semantics are also present. | Retain the implementation and validate actual TalkBack speech, focus and seek behavior. No duplicate label implementation is needed. |
| #195 no-results recovery | `AxisEmptyState` still renders its constrained-results message without an action. | Add recovery appropriate to the active query/filter/focus, preserving the chosen library. Verify empty/offline/error branches remain distinct. |
| #176 pushed Sign in | `SignInRoute` has no navigation capability argument; `SignInScreen` has a title-only toolbar. The form's Back to server is a separate wizard action. | Pass explicit root/pushed context from NavHost. Root has no arrow; Add Profile/Sign In Again return to Profiles. Match toolbar/system/predictive Back and preserve drafts, cancellation and successful stack cleanup. |

These are child-sized delivery slices under their existing issues, not duplicate audit issues. An existing
implementation stays pending physical acceptance when its evidence is only source/JVM review.

## Reconcile the deeper Home/profile/Appearance proposal

Work #194 in the following order, keeping each change reviewable:

1. Reproduce the fixed-height flat-list risk (`BookListUi.kt` still uses `Modifier.height(ROW_HEIGHT)`),
   profile content behind the player, and the pack/light-dark preview discrepancy. Use minimum/content-driven
   geometry where reproduction supports it; ensure preview and actual theme use the same resolution owner.
2. Compare representative prototypes for Continue listening, quieter flat rows, narrower collection artwork
   and profile action hierarchy against the current presentation. Use real state and metadata; retain the
   settled details-versus-play distinction, current/security status, switching feedback and destructive copy.
3. Refine Appearance's real-component preview, named color sources and comparison flow; then tabs, filtering
   versus sorting, and stable loading/missing/failed cover states. Preserve Books / Series / Authors / Genres,
   swipe/tap alternatives, optional glass/background choices and existing font/icon systems.

Profile/player overlap and preview resolution remain reproduction tasks here, not newly observed device
defects. Run the existing Hallmark review process when doing the actual design comparison.

## Device/render evidence required for each affected slice

Use 320/375/414/768 dp widths, font scales 1.0/1.3/2.0, portrait and landscape, long English/Norwegian labels,
and active playback. Include light/dark/AMOLED, dynamic color, background packs and older-platform fallback;
measure contrast on the effective background. Cover offline, locked/signed-out profiles, missing/failed
artwork, loading, empty and error states. Verify TalkBack order/labels/values, separate nested actions, 48 dp
targets, keyboard/system Back where relevant and reduced motion. Record APK commit and device configuration.

## Keep adjacent work separate

- #190 WebView flicker: on an affected device, record the provider/version and APK, bound the reproduction,
  compare standalone rendering and reduced motion, then try opaque-background/Haze isolation. The upstream
  explanation remains a hypothesis; choose a permanent mitigation only after the matrix identifies it.
- #101 series formatting: change display copy separately, preserving primary series selection and ordering.
- New Android system surfaces retain the roadmap's correctness gate; this UI audit does not advance them.

No render, TalkBack, physical WebView or device acceptance was performed for the original reconciliation.
The later [supplied-phone pass](2026-10-03-phone-acceptance.md), on main `8beec05c` / API 36, samples
landscape and 200% portrait player text and renders Loopbound's first page with WebView 153.0.8010.36.
Landscape text over bright enlarged artwork needs controlled contrast review under #194/#195. This does
not accept the complete width/theme/TalkBack matrix or #190's affected-device flicker/opaque/Haze checks.
