# ADR-0036 — Explicit output transfer commits after current-track proof

**Status:** Implemented in draft #300; full strict local verification passes. Exact source/CI is recorded in
[the checkpoint](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6448375926).
Device acceptance remains pending.
**Requirements:** PLAY-002/004/005/008/009, ROUTE-002, SYNC-002; issues #276/#277 and the #289 execution plan.

## Context

A preferred-device request is not proof of where the playing AudioTrack went. Clearing the previous lock
as soon as Car or another output is requested also removes the constraint before its replacement is proved.
Intent publication can synchronously resume observers, so muting after publishing the request leaves a gap.

The current monitor distinguishes an unmapped route from a mapped endpoint, but its endpoint-only projection
cannot distinguish RemoteSubmix from other unresolved paths. An Android Auto controller package or a virtual
media key does not establish a physical dashboard/headset gesture.

## Decision

`OutputIntentOwner` remains the only selection/sequence/lock authority. It reserves an immutable request using
the existing sequence without publication. `HeadsetRouteGuard.Probe` reserves and captures the heard position
before acquiring mute can invoke listeners, and before intent or preferred-device effects are published.
Replacing a pending probe holds its previous mute claim until the successor has acquired its own claim;
there is no audible release gap. Reentrant newer selection, command, item/profile or attachment wins.

The owner publishes requested/Waiting/Verifying/Verified/Delegated/Failed state together with the retained
prior lock. Only current successful proof releases that prior lock. A paused selection arms a request and
does not create Play. A later deliberate Play revalidates the existing selection without emitting a second
listener choice. Automatic/Release retains its deliberate existing release policy.

A confirmed physical projection departure rejected as `NoPlayingCarHeadset` may expire the exact paused,
unlocked Car choice to Automatic. This uses the same sequence/owner and retires queued preference/probe
work, without a preference request, Play or Automatic-release suppression. It preserves explicit locks,
newer choices, pending Play and accepted continuity recovery. Unknown projection and ordinary controller
churn do not authorize expiration. This corrects 2244's departed Car proof blocking subsequent manual Play;
it is not an input-origin association or automatic headset rebinding. The
[2244 assessment](../testing/output-2244-regression.md) records the narrower acceptance scope.

Current-track observations use the existing AudioTrack observer epoch and retain four distinct paths: one
mapped endpoint, RemoteSubmix, unresolved, or multiple sinks. Endpoint confirmation requires a fresh unique
exact live mapping with no collision. Explicit Speaker is a valid endpoint request; an unexpected speaker
is a mismatch. Explicit Car requires positive current projection context and an adequate current measured
path. RemoteSubmix can establish delegation to Auto in that context, never a particular physical speaker.
Binding, names, preferred-device acceptance and a sole ambiguous A2DP endpoint are insufficient.

`OutputHandoffEffects` orchestrates these effects through the existing owner/probe/preference
ports. It keeps no other mutable current transaction, counter, player, session, focus owner or retry
executor. Compensation and bounded playback-thread/route proof retain ADR-0035's shared held-position reads.
Actual-track observation uses `AudioTrack.getRoutedDevice`, available before the app's API26 minimum;
explicit transfer is not artificially restricted to API33's unrelated media-policy prediction API.
An unavailable observer or unknown path still times out muted and pauses. Car still requires positive
projection evidence. These changes do not broaden the earlier implicit-headset Play/Keep-here eligibility.
Old proof/status/cancellation callbacks cannot commit or clear a successor, including an in-place held-position
rebase. Final scope is rechecked around synchronous publication before releasing mute.
Success publication is followed by fresh target proof under that same checkpoint. If a synchronous
observer changes the route without a newer request, the attempt fails while still muted, restores the
held point and retained prior lock, and pauses. A newer selection or in-place seek cannot be rejected
by the retired confirmation. The retained lock is an immutable local commit snapshot, not a second owner.
ADR-0035's post-seek position bound is also checked after publication and fresh route measurement. Excessive
clock movement refuses that confirmation while the same prior-lock snapshot is still available for recovery;
the attempt restores the held point and pauses. Publishing Verified/Delegated cannot bypass the final check.
An in-place seek that invalidates the published success retains that same prior recovery lock while its
new checkpoint/barrier is verified; it does not resurrect a lock after a newer destination or release.

Failure/timeout remains paused with the prior logical lock retained. A previous endpoint preference may be
restored only under the unchanged request and fresh unique original-route proof; rollback does not resume
audio or claim hardware success. Unknown/unsupported outputs remain unverified. Virtual media transport
does not acquire a new physical identity.

## Verification and limits

The [output adapter continuation](../architecture/playback-output-adapter-plan.md) retains this decision.
Android output access is separated from intent/proof ownership. Healthy-route preservation uses fresh
current-track observations; an eligible paused recovery can reach the existing silent probe promptly.
Neither `ProbeReady` nor the asynchronous 400ms fallback observation replaces target proof or the final
position check. The [acceptance record](../testing/output-adapter-continuity.md) separates software timing
from the owner's under-one-second physical interruption target.

Implementation tests must exercise actual router/controller/service paths, not a second playback model:
Car-to-Headset-to-Car, paused choice, exact proof, old route during settling, mismatch/unknown/multiple,
timeouts, rollback, nested/newer choices, command/book/profile/attachment supersession and repeated callbacks.
Progress, history, profile, sleep, queue and speed must survive the same transaction.

The transfer checkpoint passes the full strict repository gate: 2,748 tests /322 suites, including 932
playback tests, with no failures, errors or skips. API26/32 cases drive the production command boundary
with controlled actual-track proof; they establish software behavior, not OEM observation availability.
Valid failing regressions cover route loss and a genuine seek during synchronous success publication,
late unheard advancement, direct Pause, Automatic retirement and stale policy epochs. No checks are suppressed.

The current Robolectric runtime does not support SDK36. The production multiple-sink classifier is
tested directly; Android36 `getRoutedDevices` itself remains a device tier. No toolchain upgrade or physical
PASS follows from that classifier test. The final combined candidate, strict gates, exact-head CI and the
device acceptance ledger remain required before acceptance.
