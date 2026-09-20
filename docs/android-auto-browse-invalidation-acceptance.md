# Android Auto browse invalidation acceptance — BW-AUTO-01 / issue #10

This runbook separates what BookWave can prove in JVM/CI from what only Android Auto DHU or a real projected
head unit can prove. Do not mark host rendering accepted from automated tests alone.

## Scope

Issue #10 changes browse invalidation only. The visible Android Auto hierarchy remains the current runtime
contract until issue #65 replaces it with the accepted PD-001 root. These checks therefore test refresh and
profile eviction without treating today's Library subtree as permanent architecture.

## Automated evidence expected before device work

The focused tests and repository PR gate must prove:

- one candidate invalidation sweep is derived from one profile-bound accessible-book snapshot;
- empty/non-empty root transitions and optional-destination appearance/disappearance are detected;
- Series, Authors and Continue compare ordered opaque child identity rather than child count alone;
- unchanged exposed membership does not produce notifications;
- ordinary changes notify only affected parents;
- a profile-generation change invalidates profile-scoped static parents even when counts match;
- emitted dynamic Series/Author parents from the old profile are explicitly evicted;
- no private title or media metadata is required in the shape fingerprint.

## DHU / projected-car procedure

Use a non-production test library/profile where practical. Record app commit/PR, Android version, host/DHU
version and whether the run is DHU or physical Android Auto.

1. **Baseline.** Connect Android Auto and browse the current root once. Open at least one Series and one Author
   so dynamic parents have actually been emitted/subscribed. Record the visible rows before mutation.
2. **Optional destination.** Add or remove a download, or another currently exposed optional Library
   destination. After normal cache refresh, confirm it appears/disappears without reconnecting and unrelated
   root destinations do not visibly churn.
3. **Series / Author membership.** Add/remove visible Series/Author membership, then repeat with a replacement
   that keeps the same count but changes identity. Confirm the relevant parent refreshes without reconnecting.
4. **Continue.** Change progress so Continue gains, loses or replaces a book. Confirm it refreshes. Also make a
   progress mutation that leaves exposed membership/order unchanged and confirm there is no visible browse churn.
5. **Profile boundary.** While still connected, switch profile A -> B through BookWave's existing atomic profile
   switch. Prefer equal Series/Author counts. Confirm no A title remains in root descendants, Continue, Series,
   Authors, Chapters/History, Downloads or other profile-scoped nodes. Re-open Series/Author nodes visited under
   A and confirm cached A children are gone without an Android Auto reconnect.

## Evidence to capture

Record pass/fail plus a short screen recording or screenshots where practical. BookWave logs may correlate that
an invalidation sweep happened, but logs are not proof that the host redrew it.

Issue #65 can reuse this runbook after changing the visible hierarchy: update destination-specific steps, not
the profile-boundary and same-count membership assertions.
