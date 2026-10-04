# Enforce the existing security coverage rule — R-125

**Classification:** Dated evidence snapshot; original source/build findings and results are retained.
Use [the current roadmap](../roadmap.md) for present delivery state and sequencing.

Requirements: PRODUCT_SPEC 14.4/14.5 and 17.3. The root 80% domain/core aggregate was green while
`:core:common`'s separate 90% redaction rule had no caller from ordinary verification. A fresh isolated
measurement was **84.43%**, below the required security threshold. The earlier 92.7% note was stale.

`core:common:verifyDebug` now depends on that module's existing `koverVerify`; root verification already
fans out to module verification. No threshold, package filter, exclusions, dependency or report variant
changes. The 80% aggregate still runs separately. This prevents green CI with an unexecuted security rule.

Four EventLog tests exercise the user-visible diagnostics sink: typed private fields and exception
messages remain redacted through RedactingLogger; the newest 500 events retain chronological order;
clear publishes an empty/fresh ring without changing prior snapshots; warning/error filtering preserves
severity. The existing typed redactor/logger tests remain intact. The same unchanged filtered report is
now **118 covered / 4 missed lines = 96.72%**.

Caller inspection confirms LoggingModule binds RedactingLogger, FanOutLogSink supplies EventLog, and
EventLogViewModel/EventLogSheet expose that redacted state from SettingsScreen. The added tests cover
the actual logging path used by diagnostics, not an unused alternative component.

## Verification

| Case | Evidence | Result |
| --- | --- | --- |
| SECURITY-01 pre-change threshold | Existing isolated koverVerify, 84.426200% <90%. | FAIL, reproduces missing coverage |
| SECURITY-02 new tests and threshold | Four EventLog cases plus existing core tests; unchanged package/filter, 96.72%. | PASS |
| SECURITY-03 ordinary graph without hook | Guard asserts root/module verifyDebug reaches core:common:koverVerify. Remove the hook after adding it; root dry-run fails with the specific R-125 message. | FAIL as expected |
| SECURITY-04 restored hook | Restore identical source bytes; root dry-run includes core:common:koverVerify. | PASS |
| SECURITY-05 full gate | Formatter and verifyDebug with warnings-as-errors, including classpath-forced rerun. | PASS: final full gate 10s, 1,122 tasks (13 executed / 1,109 up-to-date), after all-task forced run and local Lint refresh |

The initial forced run executed all 1,122 tasks but failed Android Lint on the ignored local.properties
SDK path's Windows colon escaping. The path was corrected without suppressing Lint or changing its
baseline. Cached local analysis was explicitly refreshed before rerunning the full gate. Initial test
compilation also required value equality for java.time.Instant under the JDK-21 compiler warning policy.
These setup/compile failures are retained separately from the purposeful graph/threshold regression proof.

No application runtime, endpoint/response, Room schema, credential format or permission changes.
The change enforces an existing requirement; it does not accept pending phone/car/server release cases.
Ignored evidence: build/r125-security-tests.log, r125-hook-reverted.log, r125-hook-restored.log,
r125-full-verify.log, r125-local-lint-refresh.log and the final verification log.

Fresh forced-run JVM/debug JUnit reports contain 2182 tests, 0 failures, 0 errors and 0 skips; core:common contributes 26 cases including the four EventLog tests. Final full verification reaches both coverage gates and assembly. No dependency pins changed.
