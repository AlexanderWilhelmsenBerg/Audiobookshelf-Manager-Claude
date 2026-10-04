# Android profile experiment — PRODUCT_SPEC 17.3 / R-25

**Classification:** Dated evidence snapshot; original source/build findings and results are retained.
Use [the current roadmap](../roadmap.md) for present delivery state and sequencing.

The supplied API-36 phone completed five formerly failed benchmark cases after Benchmark1.5.0's
process-discovery repair. Startup meets the measured <1 s fixture target. Scrolling exceeds the
documented 16.7 ms P95 CPU comparison budget; memory has a recorded baseline and no invented threshold.

The generator reports stability at iteration15. Its unmodified11,185-line /326,833-byte profile,
SHA-256 `031234bff74ddda152492af66f4a138a373ff92af1530d2b1430b40da51a02cf`, is retained in
[`benchmark/profiles/2026-10-04-api36-baseline-prof.txt`](../../benchmark/profiles/2026-10-04-api36-baseline-prof.txt).
Producer: BaselineProfileGenerator#generate, main1e74bab2, SM-S928B / Android16, Benchmark1.5.0,
2026-10-04 11:00:27 UTC. The journey is startup, Home, list and scroll over2,000 synthetic English books;
no private host/media/covers. The file is outside the production profile consumer.

## Results and decision

| Case | Result |
| --- | --- |
| PROFILE-01 generation/exact copy | PASS; five test methods in890.559s; generator reports stable. |
| PROFILE-02 AGP consumer and benchmark/release packaging | PASS experiment; removing source removes the app MainActivity rule, restoring source restores it. Both APKs contain compiled .prof/.profm. |
| PROFILE-03 required-profile control | PASS with libraries only: ten samples, TTFD median680.555ms. Required profile does not mean an app-owned profile. |
| PROFILE-04 app-profile startup | PASS <1s in ten samples; median699.608ms. No demonstrated gain over control. |
| PROFILE-05 scrolling | Test methods PASS; target FAIL. CPU P95 is19.145ms before /20.560ms with app-profile candidate; positive frame overruns in both. |
| PROFILE-06 candidate gates | PASS formatter/full verifyDebug plus benchmark/release assembly,9m14s,1,402 tasks. Final retained-artifact gate recorded below. |

Retain the generated profile for analysis and keep it out of the production build. Sequential samples do
not establish a causal slowdown, but they do not support a benefit either. Existing Android library
profiles already ship: the earlier “file missing means no shipped profile” assumption was wrong. The
attempted required-profile red run actually passed; it is recorded as a control, not mutation proof.
Do not claim20–30% improvement or retire R-25. The next step is trace-led list/frame profiling, followed
by a bounded change and controlled comparison. Re-record the app profile when that journey changes.

The [phone log](../testing/2026-10-04-phone-2179.md) names every performed/missing test and links the raw
metric summaries. Manual cached-player latency/stress, other devices, car/headset, physical power loss,
bedside movement and full appearance/TalkBack acceptance remain pending. No dependency, runtime policy,
server API, schema or permission changes are needed to retain these experiment artifacts.

Final retained-artifact formatter/full verifyDebug with warnings-as-errors: PASS in 3m 44s, 1122 actionable tasks: 51 executed, 10 from cache, 1061 up-to-date. Both the 80% aggregate and merged 90% security rule execute. No dependency/classpath pins changed.
