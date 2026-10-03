# CI timing samples — 2026-10-03

**Classification:** Observations for #188, not a controlled performance comparison.
**Source:** GitHub Actions job/step timestamps retrieved with `gh run view --json createdAt,jobs`.

| Successful sample | Wait after preflight | Verify job wall time | Gradle setup, including restore | Verification step | Gradle post step, including save |
| --- | --- | --- | --- | --- | --- |
| [#212, run 37114904505](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37114904505) | 3s | 4m54s | 13s | 2m44s | 46s |
| [#213, run 37115894959](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37115894959) | 2s | 10m59s | 17s | 7m14s | 57s |
| [#214, run 37117872468](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37117872468) | 3s | 15m13s | 20s | 12m20s | 59s |

The wait column is the gap from preflight completion to verification-job start, a scheduling proxy once
its dependencies have completed. It excludes time spent in identity/preflight and the initial workflow
queue. Setup/post durations include Gradle action work as well as cache transfer; they are not isolated
transfer measurements. The verify job also includes container provisioning, checkout, environment setup,
diagnostics and uploads. GitHub reports these timestamps to whole seconds.

These runs have different code, task graphs, rerun requirements and cache history. The #212/#213 samples
precede the debug-only coverage integration; #214 changes the classpath and forces the graph to execute.
Shared runner/network conditions are uncontrolled. A faster total here does not establish an improvement
caused by either PR, and local build duration cannot supply the missing cloud comparison.

After #214/#212 merge, record the first trusted main seed and release result, then compare repeated PR runs
with the same graph under known cold/warm cache conditions. Keep queue, verification and restore/save
measurements separate. Existing `ci-telemetry/metrics.json` reports task/cache counts, runner CPU count and
test totals; capture it alongside timing, APK/head SHA and run links. Detailed timing automation and isolated
transfer/hit measurements remain #188 follow-up work.

The Silo pilot remains deferred pending the owner's separate implications research. These observations
introduce no remote-cache infrastructure, credentials or task-output exclusions.
