<!-- Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V4 -->

# Compact series cards — 2026-10-04

Requirements: LIB-003, LIB-004, SET-002, product principle 2.10 and section 21.
Scope: existing series-detail cards; playback, routes, state ownership and general flat-list cards retain
their separate implementations. The owner requested more compact cards after observing debug2178.

The previous layout stacked a 72dp artwork/title section, membership, a separate 48dp status/Play section
and duration/progress, with 12dp section gaps. Two realistic short-metadata native fixtures measured
212dp finished and 220dp listening. Both new behavioral height checks failed before the layout changed.

The revised card uses one row: 56dp cover, weighted metadata and a separate 48dp Play target. Title,
author, selected membership and listening status sit together; finished/not-started duration flows beside
the status when it fits. In-progress position/remaining/duration/percent and progress remain under that
metadata. Padding and gaps are reduced. There is no fixed card height, line cap or ellipsis. The existing
completed-only green edge fades inward without an outside halo or checkmark.

Hallmark component scope: no page macrostructure, new palette/font or decorative motion. Cached
preflight follows the [original design review](2026-10-03-series-screen-hallmark.md): Material 3 system
typography, the user's GlassCard appearance and existing motion/focus/press owners. The native renders
cover normal/finished/listening and missing artwork; artwork loading/failure and Material interaction
states remain owned by the existing components. No unsupported domain states or fake error UI were added.

## Automated and rendered verification

- Red: both ordinary-row compactness checks fail the old layout (212/220dp); remaining 20 card cases pass.
- Green: all 26 series card/screen cases pass, including widths320/375/414/768dp at fonts1.0/1.3/2.0,
  text-layout bounds/full-text guards, listening states, inward glow and separate screen navigation/Play.
- Native synthetic 375dp/1.0 captures show approximately98dp finished and124dp listening cards. The
  320dp/2.0 long-metadata capture was inspected: all text wraps and remains inside the card.
- Caller review: SeriesScreen reaches SeriesBookCard and keeps selected membership and separate callbacks.
- Formatter and full verifyDebug with warnings-as-errors pass in4m10s (1,019 tasks), including547 app
  tests with zero failures/errors/skips. No classpath changes; a forced dependency rerun is not needed here.
- No endpoint/response/permission/schema/dependency changes; API compatibility entries are unchanged.

Evidence: [finished row](evidence/series-compact-finished-375dp-font1.png) and
[listening row](evidence/series-compact-listening-375dp-font1.png); additional native PNGs in
app/build/series-screen-evidence; red JUnit/log and green/full logs under ignored
build/compact-series*. The initial PowerShell argument quoting and test bounds compile errors were
corrected before the meaningful two-failure red run; they are not regression evidence.

## Device verification required

Install the exact verified signed candidate without clearing data; record code/source/signer/hash.
Repeat the supplied series in dark Norwegian portrait100% and130/200% text, landscape, finished/listening/
not-started rows and final-row clearance with the mini-player. Compare light/AMOLED/dynamic/artwork,
English, missing artwork/multiple memberships and TalkBack when available. Row tap must open details;
Play must start only that book. Offline/active-audio navigation must preserve playback and progress.
Restore position, paused/timer-off state and original phone settings after tests.

The prior [2178 acceptance](../testing/2026-10-04-phone-2178.md) establishes the earlier implementation's
behavior only; its appearance checks do not automatically accept this changed layout. Real bedside
shake, headset/car, reboot/power loss and controlled transfer races remain in the existing test matrix.

## Connected continuation — 2026-10-04

The [2179 phone log](../testing/2026-10-04-phone-2179.md) records the later connected results and each missing case. The compact series/glow/large-text/last-row subset passes on signed2179. All27 storage/security tests and eight benchmark executions pass. Startup meets the fixture target; scrolling misses its P95 budget. The generated profile is retained as a measured experiment outside production; no performance gain is claimed. Earlier failed or NOT RUN cases keep their dated scope.
