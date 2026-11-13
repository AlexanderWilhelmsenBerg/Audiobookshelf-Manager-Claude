# Bounded muted-route observation

Requirements: PLAY-001/002/004/008/009, ROUTE-001/002; preserve S01–S22 and PD-009.
Baseline: PR306 `076177467c907d1612394cb08643f018e48dd1c1`, APK2264, dependent on PR305.

## Evidence and scope

After completing Android Auto's first-connection setup, two USB DHU cycles reached CONNECTED and
BookWave observed Projection. Arrival focus-loss-to-output-confirmation intervals were 2.212s and
2.020s; departure intervals were 0.790s and 1.028s. These are software observations, not acoustic
measurements. DHU does not reproduce the real car's Bluetooth endpoint switching.

Most arrival time preceded the first trusted controller bind (2.019s and 1.827s). Android Auto
requested full audio focus; Media3 delivered the required pause. Early provider reads still reported
NotConnected. No earlier callback may authorize recovery merely because it resembles a car arrival.

The departure probes compensated 416ms and 391ms of muted advancement and buffered during the
restoration seek. The router's existing 400ms fallback may have discovered a route that became
readable after the initial playing callback without another routing event. That explanation remains
a hypothesis until the new observation evidence is compared on device.

## Candidate behavior

Only an already-owned implicit headset probe receives a bounded burst of route observations after
playing is observed (five requests, nominally 20/50/100/200/300ms). Existing actual-track and playback
callbacks still run immediately. The burst
does not issue Play, request focus, change preference, increase timeouts, or bypass the transaction.
It cannot restart on duplicate callbacks or migrate to another probe/player/selection/context.
Explicit transfers retain their current observation behavior.

Fresh exact output, post-restoration proof, the existing 0–50ms position bound and one restoration
remain mandatory. Unknown evidence stays muted; wrong or multiple output fails closed. New Pause,
Stop, selection, book/profile change, replacement and teardown retain precedence. Normal healthy
playback has no observation loop. Media3 remains the only focus owner and there is one player/session.

Connection-entry diagnostics record classified service bind, session lookup and controller
connection-attempt timing. They neither create a car boundary nor change controller trust. They do
not record raw intents, extras, package/device names, addresses, media metadata or stable identifiers.
Use the monotonic timestamp to compare entries preceding a trace with its focus-loss opening.
These entries, trace openings and finite observation fields also survive the incident export's
privacy allow-list; raw controller/device fields remain excluded.

## Verification and acceptance

Regression coverage must demonstrate a late measurable route without a callback completes before the
400ms fallback; wrong/multiple/unknown routes, post-seek replacement, bounded duplicate observations,
and stale/cancelled work must remain safe. Confirm the baseline fails before applying the fix.

Baseline counterfactual: five new route tests ran against unchanged production code; three failed
at the expected late-route/position assertions. After implementation the focused route, position,
Media3 and reentrant suites passed. Additional tests exercise stale command/book/profile/attachment
and actual legacy bind/session-lookup diagnostics. The final 61-test focused run passed, including
replacement during a route read, explicit Car selection and private-field exclusion from export.
The final formatter, strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` and
`:playback:testReleaseUnitTest` passed: 3,009 debug/JVM tests and 1,173 release playback tests,
with no failures, errors or skips. Twelve regressions were added. Initial code-size and test-lambda
lint failures were corrected without changing limits or suppressions. Physical acceptance remains separate.

Exact-head normal PR CI
must pass before the requested debug APK build. Record results in the PR after actual completion.

Repeat DHU with setup already completed, then stationary real-car testing. Record heard gaps and
destination, compare diagnostics on/off, and test deliberate Pause during recovery, initially paused
arrival, car/headset buttons, explicit output icons, endpoint disappearance, notifications/calls,
book/profile changes, sleep expiry and non-1x speed. Check position, history and progress afterwards.
Subsecond recovery is a target to measure; zero silence and physical car acceptance are not claimed.
