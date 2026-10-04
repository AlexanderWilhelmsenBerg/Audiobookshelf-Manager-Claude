# Generated Android baseline profile — PRODUCT_SPEC 17.3 / R-25

The supplied Samsung SM-S928B / API-36 phone completed all five previously failed benchmark cases
with Benchmark 1.5.0 against merged source `1e74bab2`: no/full-compilation cold startup, warm scrolling,
peak Home/list memory, and baseline profile generation. The generator reported its profile stable at
iteration 15. This is the unmodified generated file, copied into `app/src/main/baseline-prof.txt` for
the existing AGP consumer. No new dependency/plugin, hand-written profile, metric suppression or
application behavior change. The captured journey is Home startup, switch to list, and scrolling of
2,000 synthetic English book rows; no private server, media or covers are present.

The profile also contains paths reached while the benchmark fixture seeds data. AGP removes references
unavailable in the production variant; verify both the benchmark and release compiled profile outputs.
The existing release ProfileInstaller dependency supplies installation. Caller/consumer checks include
the AGP merged/compiled ART profile tasks and the packaged `assets/dexopt/baseline.prof`/`.profm` files.
The required-profile startup case must pass before asserting that the profile is consumed on hardware.

Generated 2026-10-04 11:00:27 UTC: 11,185 lines / 326,833 bytes, SHA-256 `031234bff74ddda152492af66f4a138a373ff92af1530d2b1430b40da51a02cf`. Producer: `BaselineProfileGenerator#generate`, output `BaselineProfileGenerator_generate-baseline-prof.txt`.

## Verification

| Case | Result |
| --- | --- |
| PROFILE-01 generator and exact copy | PASS; all five tests completed in 890.559 s; file contains ART class/method rules only, copied byte-for-byte. |
| PROFILE-02 benchmark/release packaging | Pending build and APK inspection. |
| PROFILE-03 required-profile cold startup | Pending ten hardware iterations with BaselineProfileMode.Require. |
| PROFILE-04 scrolling with the packaged profile | Pending ten iterations; baseline P95 CPU19.145 ms exceeds the documented 16.7 ms comparison budget. |
| PROFILE-05 formatter/full verification | Pending current-head gate. |

The complete [phone continuation](../testing/2026-10-04-phone-2179.md) records each functional test,
benchmark method, measured threshold and remaining fixture. A passed measurement method does not
turn an exceeded performance target into a pass. Manual cached-audio startup and download/playback
stress, car/headset acceptance, other devices and the full appearance/TalkBack matrix remain pending.
Keep R-25 open for unaccepted targets and avoid an assumed percentage improvement from this profile.
Re-record when the startup/list journey changes materially; keep device-dependent generation out of CI.
