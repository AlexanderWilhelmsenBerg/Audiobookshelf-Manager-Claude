<!-- Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V4 -->

# Series screen: readable metadata and listening state

Reviewed 2026-10-03 from main `cc4642c00a5e2238e3f867b56683f807168c4830`, the owner's
supplied phone capture, and the current native Compose implementation. Ownership: UI & Experience,
implementation owner. Requirements: LIB-003, LIB-004, product principle 2.10 and section 21.

## Findings

The previous series screen reused `BookCard`, whose row is fixed at 132 dp. The cover fills that height,
the title reserves two lines, and author, series membership and progress are placed in the remaining
space. Larger text or wrapped metadata grows independently of the row. The supplied capture shows
the membership clipped at the bottom; the same geometry can hide the existing finished-progress text.
A two-line title limit also discards part of a long title.

The header displays the next book's title in a single-line, ellipsized button. That makes the main action
compact but prevents someone from reading the complete title there. The aggregate finished count is
useful; it does not identify which individual rows are finished.

These findings overlap the adaptive-row concern in GitHub #194. This change handles the requested
series-detail surface; the broader Home, flat list, profile picker and Appearance recommendations in
#194 and #195 remain separate work.

## Hallmark preflight and design direction

- Typography: existing Material 3 `Typography()` and system text scaling
  (`core/designsystem/.../theme/Type.kt:12`), retained.
- Palette: existing Material 3 semantic colors, shipped teal accent and user-selected appearance
  (`core/designsystem/.../theme/Theme.kt:24` and `:41`), retained. The owner's 2026-10-04 refinement adds
  a dedicated green completion edge using named light/dark status-color tokens; it does not recolor
  book text, playback actions or the rest of the theme.
- Framework: native Kotlin / Jetpack Compose Material 3. Existing `GlassCard` remains the containment
  surface (`app/.../ui/glass/GlassSurfaces.kt:91`); no new design system or web implementation.
- Spacing: existing four-dp rhythm. The series row uses 12-dp padding/gaps and 4-dp text spacing.
- Motion: no new reveal, marquee, status pulse or decorative transition; platform controls provide
  their normal press/focus feedback.

Audience: an audiobook listener checking where they are in a series. Main job: identify a book and
open its details or play it deliberately. Tone: utilitarian. This is a native screen/component revision:
page macrostructure, website navigation/footer and CSS export files are inapplicable.

## Implemented revision

`SeriesBooks` now calls a dedicated `SeriesBookCard`, allowing the series layout to expand with text
without changing general browsing cards. A stable 72-dp square cover sits beside the complete title
and author names. The selected series membership and listening progress use the available card width
below that row. Metadata wraps naturally; there is no fixed row height or title/author line cap.

Each card shows **Finished**, **In progress** or **Not started**, derived from the existing book progress.
The initial revision added a check-circle icon. After reviewing the rendered items, the owner asked on
2026-10-04 to remove that checkmark and replace it with a slight green glow on the **inside** border of
completed cards. The visible **Finished** text remains the non-color cue and TalkBack state information.
No duplicate playback state or server API is introduced. In progress means an unfinished progress
record has a positive position; a zero-position record is not presented as started.

The refinement uses a static rounded inner edge that fades toward the interior. There is no external
shadow/halo, animation, tinted metadata panel or change to card geometry. Light/dark green status
tokens follow the active Material surface luminance rather than the phone's system light/dark setting,
so the app's selected appearance controls the treatment. The color remains green even when a different
app accent is selected. The preserved Finished text carries the completion meaning independently.

The card still opens details. Its separate 48-dp, book-named Play control invokes the existing series
play callback and does not also open details. The label does not promise a restart policy that this
UI component does not own. Existing book-duration/progress formatting is shared with browsing.

The header now displays the complete next-book title above a short **Continue** / **Fortsett** action.
Its spoken name still identifies the target book. The cover is reduced to 80 dp, giving the series count
and duration more space. Finished-series behavior, canonical sequence order, Room-backed state,
navigation and mini-player clearance are preserved. Loading and missing-series surfaces are unchanged.

## Regression and acceptance evidence

Three regression cases ran with `SeriesBookCard` temporarily delegating to the original `BookCard`
under Robolectric **native** graphics, and all failed. The fixed source was then restored and its hash
checked against the saved source:

1. Long title/author/series/progress geometry at 320 dp and font scale 2.0 failed on actual
   `TextLayoutResult.didOverflowHeight` for the long title, rather than merely checking whether text
   existed in semantics.
2. A finished book had no visible standalone **Finished** label.
3. An unfinished book with listening position had no **In progress** label.

The valid red-run JUnit evidence is retained locally in ignored
`build/series-screen-evidence/series-native-red.xml`; it contains only synthetic fixture metadata.
The initial legacy-graphics run is superseded: its text metrics incorrectly reported a one-line,
44-pixel result for the 44-character title. That result is not used as geometry proof.

The final suite checks native text layout and unclipped bounds at 320/375/414/768 dp, each at font
scales 1.0/1.3/2.0, plus finished/in-progress distinction. Checks require no height overflow, no
ellipsized lines, the complete final character offset, drawn line edges within the integer pixel box
(one-pixel rounding tolerance), and unclipped text bounds within the card. The raw paragraph width
is the allocated layout track rather than the drawn text width, so it is not substituted for line edges.
Method annotations explicitly set doubled text where needed; a class-level 2.0 default would otherwise
prevent Robolectric's default-valued 1.0 method setting from overriding it.

Existing `SeriesScreenTest` checks series-count
arithmetic, target-book continuation, all-finished behavior, separate per-row playback, control labels
and minimum targets at doubled text size. Its per-row action check now scrolls the lazy list to the
target, accommodating content-driven row height.

**Initial revision validation:** native focused suite passed all 20 cases (16 card tests, four screen tests),
including the twelve width/text-scale combinations and two render captures. Replacing the row with
the old implementation failed all three guard cases, then the restored-source repeat passed all 20
again. Full integration `verifyDebug` remains the integration owner's final check.

Two synthetic native PNGs were generated using the real `ShelfPlayerTheme`, normal Surface/GlassCard
and the existing missing-cover placeholder, and visually inspected:

- [Finished row at 320 dp / doubled text](evidence/series-finished-320dp-font2.png): complete long title, author and series;
  finished treatment/text and duration; no clipped content.
- [In-progress row at 414 dp / standard text](evidence/series-in-progress-414dp-font1.png): compact cover/title/author arrangement,
  in-progress state, complete listening progress and progress bar.

These are Android Compose renders drawn directly onto a native bitmap canvas, not phone screenshots.
`captureToImage`/PixelCopy initially timed out waiting for a hardware window; direct Android View drawing
produced inspected renders without depending on that unavailable window. A JVM layout/render check
does not prove physical rendering, TalkBack navigation, audible playback continuity or effective glass
contrast across all appearance choices. The phone was reconnected for integration acceptance. Finished rows at 100% and 200% portrait text were inspected on debug 2177; see the [execution log](../testing/2026-10-03-series-history-sleep.md). The remaining physical matrix below is still required.

## 2026-10-04 completion-edge refinement verification

Four native tests extend the original geometry/state suite:

- Finished text aligns with the metadata's leading edge, without the space previously occupied by the
  completed-book checkmark.
- Light and dark completed cards have a visible green signal at the inner edge, fading before the text
  region. Samples immediately outside the card match the untouched background farther away, guarding
  the requested absence of an outside halo.
- An in-progress card has no green completion edge. Existing Finished/In progress/Not started text and
  independent Play/navigation tests remain in place.

Mutation verification restored the previous card: both completed-edge checks and the Finished-text
alignment check failed, while unfinished-card exclusion passed (three of four red). Restoring the refined
source passed all 24 series cases (20 card and four screen). The regenerated finished/in-progress native
PNGs were visually inspected: full metadata and Finished text remain readable, the completed green edge
is subtle and inward, and the in-progress card has no completion edge. Formatter and full verifyDebug warnings-as-errors passed in 1m 50s (1,119 tasks). The initial gate
caught five test-only Bitmap.getPixel UseKtx errors; those now use the required Kotlin extensions.
Prior phone acceptance of the initial checkmark presentation does not accept the refined appearance automatically.
The inner glow is supplemental decoration; visible text, accessible actions and adaptive metadata retain
their existing contrast and geometry obligations.

## Required physical checks

- Open a cached series with finished, in-progress and not-started books; confirm only completed cards
  have the inward green glow, no completed-book checkmark or outside halo, and visible state text,
  complete metadata and selected membership in the correct order.
- Repeat portrait/landscape, English/Norwegian and text scale 1.0/1.3/2.0; scroll the final row clear
  of the mini-player. Include missing artwork and long titles/authors/series names.
- Compare light, dark, AMOLED, dynamic accent and an artwork/background appearance. Check actual
  text/control contrast and the subtle completion edge on the effective surface; preserve user's
  appearance choices.
- Use TalkBack to read the book identity and state and reach the separate Play action. Confirm its
  tap starts only that book; a row tap opens details without starting playback.
- Repeat with playback active and with network unavailable. Navigation and rendering must preserve
  playback and use cached state. Check reduced-motion settings for absence of new decorative motion.
- Keep screenshots/recordings private when they contain library metadata; share synthetic or redacted
  visual evidence with the change.

## Remaining limits

The series model still has no genuine summary field; no description or listening metric was invented.
The broader flat-list fixed-height concern remains outside this series-only fix. Very large text makes
rows taller by design: more scrolling is preferable to silently discarding book information. Physical
contrast, TalkBack and real artwork acceptance remain explicit tests, not source-review claims.
