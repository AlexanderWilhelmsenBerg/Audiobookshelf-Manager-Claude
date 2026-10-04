# API-36 benchmark process discovery repair

**Classification:** Dated evidence snapshot; original source/build findings and results are retained.
Use [the current roadmap](../roadmap.md) for present delivery state and sequencing.

Date: 2026-10-04. Requirements: PRODUCT_SPEC 16.1 and 17.3; ADR-0025; risk R-25.

The 2026-10-03 SM-S928B / Android 16 / API-36 trial failed five cases before producing measurements.
Benchmark 1.3.4 filtered truncated process names against the full package, leaving launch/frame-stat
confirmation with `[]`. Those failures remain recorded in [the runbook](../benchmark.md).

## Change and source review

Advance only `androidxBenchmark` from 1.3.4 to stable 1.5.0. This is a focused Phase-7 compatibility
repair; Phase 6 remains the next broad dependency lane. Only `benchmark/build.gradle.kts` consumes the
catalog alias. The device harness does not ship in the application. App schemas, server contracts,
production dependencies and baseline-profile assets are unchanged.

[Official release notes](https://developer.android.com/jetpack/androidx/releases/benchmark) list 1.5.0
as released on 2026-09-09 and document the API-36 full-command-line fix introduced in 1.4.0-alpha09.
The [published 1.5.0 source archive](https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-common/1.5.0/benchmark-common-1.5.0-sources.jar)
was inspected directly: `Shell.pgrepLF` adds `-a` on SDK >= 36, before the package-name filter.
Source archive SHA-256: `8089be7d9a90548ed36b4e5f33579aafbfeeff7acb750d2050a593db78526233`.

The artifact metadata requires Kotlin stdlib 2.1.20 and UiAutomator 2.3.0; the existing compiler 2.2.0
and UiAutomator 2.4.0 satisfy those requirements. AGP, SDK and other direct pins remain unchanged.
The new verification entries cover 12 components / 24 artifacts: Benchmark and its trace processor
1.5.0, ProfileInstaller 1.4.1, Perfetto tracing 1.0.1, Wire 6.4.0 and coroutines-android 1.9.0.
Every new SHA-256 was independently compared with Google Maven or Maven Central using published module
filenames. All matched; all existing entries remain unchanged. Dependency verification stays enabled.

## Verification

- Metadata generation and both benchmark APK assemblies: PASS. This run alone is not the strict gate.
- Formatter, forced `verifyDebug` with warnings as errors, and strict harness/target assembly: PASS.
  Command: `ktlintFormat verifyDebug :benchmark:assembleBenchmark :app:assembleBenchmark
  -Pshelfplayer.warningsAsErrors=true --rerun-tasks --max-workers=4`; strict dependency verification enabled.
- Physical API-36 rerun: NOT RUN. `adb devices` has no device on 2026-10-04.

The source repair and compilation do not establish performance acceptance. No measured number or
generated baseline profile is claimed. R-25 remains open.

## Remaining device checks

Follow [the benchmark runbook](../benchmark.md), using a disposable unsuffixed benchmark installation;
preserve any existing unsuffixed installation containing real data. Debug delivery uses `.debug`.
Record device/API, source SHA, compilation mode, fixture size, thermal state, raw reports and outcomes.

| Check | Current outcome / required evidence |
| --- | --- |
| `StartupBenchmark#startupNoCompilation` | Historical FAIL; repeat 10 iterations and record full-display timings. |
| `StartupBenchmark#startupFullCompilation` | Historical FAIL; repeat 10 iterations and record timings. |
| `LibraryScaleBenchmark#scrollBooksList` | Historical FAIL; repeat on the 2,000-book fixture and retain frame metrics. |
| `LibraryScaleBenchmark#homeMemoryAtScale` | Historical FAIL; repeat and retain memory metrics. |
| `BaselineProfileGenerator#generate` | Historical FAIL; repeat and inspect the generated profile before shipping it. |
| `StartupBenchmark#startupBaselineProfile` | NOT RUN; requires an actual generated, shipped profile first. |
| Cached local player startup | NOT RUN; real downloaded book, PLAY-006 diagnostics, median under 1 s. |
| Concurrent download/playback stress | NOT RUN; real server/downloads, retained progress and no ANR. |
| Compact series cards on supplied phone | NOT RUN; verify normal/large font, complete metadata, inside completion glow and 48 dp Play target. |

The earlier failures must only be superseded by actual successful device runs.

## Connected continuation — 2026-10-04

The [2179 phone log](../testing/2026-10-04-phone-2179.md) records the later connected results and each missing case. The compact series/glow/large-text/last-row subset passes on signed2179. All27 storage/security tests and eight benchmark executions pass. Startup meets the fixture target; scrolling misses its P95 budget. The generated profile is retained as a measured experiment outside production; no performance gain is claimed. Earlier failed or NOT RUN cases keep their dated scope.
