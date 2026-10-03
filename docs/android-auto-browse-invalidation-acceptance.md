# Android Auto browse invalidation acceptance — BW-AUTO-01 / GitHub #99 (historical #10)

This runbook separates what BookWave can prove in JVM/CI from what only Android Auto DHU or a real projected
head unit can prove. Do not mark host rendering accepted from automated tests alone.

## Scope

PD-001 is implemented: the root is exactly Continue, Series, Authors, Profiles. GitHub #99 (historical #10)
checks refresh and profile eviction within that hierarchy. Library, History and Downloads are not car root
destinations. Record each case in the [verification register](testing/roadmap-verification-register.md).

## Automated evidence expected before device work

The focused tests and repository PR gate must prove:

- one candidate invalidation sweep is derived from one profile-bound accessible-book snapshot;
- empty/non-empty child transitions are detected while the PD-001 root stays stable;
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
2. **Empty/non-empty children.** Change cached membership so Continue, a Series or an Author gains or loses
   its last accessible book. After normal refresh, its children update without reconnecting; the four root
   destinations remain stable and unrelated parents do not visibly churn.
3. **Series / Author membership.** Add/remove visible Series/Author membership, then repeat with a replacement
   that keeps the same count but changes identity. Confirm the relevant parent refreshes without reconnecting.
4. **Continue.** Change progress so Continue gains, loses or replaces a book. Confirm it refreshes. Also make a
   progress mutation that leaves exposed membership/order unchanged and confirm there is no visible browse churn.
5. **Profile boundary.** While still connected, switch profile A -> B through BookWave's existing atomic profile
   switch. Prefer equal Series/Author counts. Confirm no A title remains in Continue, Series, Authors or their
   dynamic profile-scoped children. Re-open Series/Author nodes visited under
   A and confirm cached A children are gone without an Android Auto reconnect. Do not expect Library, History
   or Downloads destinations in the car; those historical checks do not override PD-001.

## Evidence to capture

Record pass/fail plus a short screen recording or screenshots where practical. BookWave logs may correlate that
an invalidation sweep happened, but logs are not proof that the host redrew it.

Use the [combined drive checklist](android-auto-pd001-drive-acceptance.md) for the current rendered root,
profile actions and artwork. Host evidence is still required for profile-boundary and same-count refresh.
