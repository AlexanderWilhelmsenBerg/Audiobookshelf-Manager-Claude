# APK2244: buffered transfer and departed Car intent

**Date:** 30 October 2026. **Requirements:** PLAY-002/004/008/009, ROUTE-001/002.
**Device evidence:** failed owner acceptance on combined source `c501404e0ba9d999d0ff40d20a8c03795e3622a4`.
The private diagnostic is not committed. Exact replacement source, gates and artifact belong in
[issue #289's checkpoint](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6448375926).

## Recorded sequence

| UTC | Recorded app evidence | Interpretation and limit |
| --- | --- | --- |
| 14:50:03 | A playing AudioTrack uses the initial mapped A2DP appearance. | Consistent with owner-heard headset playback. |
| 14:55:56–14:56:03 | Focus loss pauses playback. The original A2DP appearance disappears; multiple replacement appearances follow. Original-target preference recovery is refused and times out. | Arrival retention fails. The dashboard's playing display does not establish app playback or audible output. Replacements cannot inherit the original identity by name. |
| 14:56:14 / 22 | Headset key events reach the session, followed by external Play requests. | No raw Play follows while the guarded original appearance is unavailable. |
| 14:56:24 | Auto Play selects Car; a playing RemoteSubmix path confirms delegation. | Consistent with the owner's Car audio result, not proof of a physical car speaker. |
| 14:56:36–37 | Explicit Headset targets the replacement A2DP route. Compensation restores 266ms; playing target evidence later arrives with another 116ms of muted advancement. Final position validation refuses and restores before pausing. | This failure is separate from missing button attribution. The old export does not identify which correction stage performed the first seek. |
| 14:56:49–50 | Paused projection departure is rejected as `NoPlayingCarHeadset`; RemoteSubmix disappears and policy reports an available A2DP route. | No unwanted automatic resume is recorded. |
| 14:56:59–14:57:06 | External and same-app Play requests are followed by route-safety pause and Car proof refusal under the departed request's scope. | Car selection survives its physical projection departure and blocks later manual playback. |

All fourteen key rows resolve to the same virtual internal input, ID-1/source0. There is no positively
associated external headset input. Resolved InputDevice metadata therefore strengthens the negative
capability result; it does not recover a physical headset-to-A2DP association. The event log contains
Info/Debug entries only. Absence of warning/error entries does not accept these functional failures.

## Scoped corrections

The existing position protocol still allows at most an initial and one final correction, with fresh
playing target proof and a final 0–50ms settling limit. A compensation seek that moves Ready into
Buffering can hide a playing interval from the application callback order. The initial stage now
retains that buffering evidence through its playback-thread acknowledgement. If fresh target proof
then shows forward advancement above 50ms, it may use the existing final correction. A second
overshoot, backward movement, mismatch, unknown route, timeout or supersession still refuses; there
is no third barrier or raised position threshold. The temporary Player listener is removed in `finally`.

On a confirmed physical projection departure rejected as `NoPlayingCarHeadset`, the router may expire
only the exact unlocked Car choice while PlayWhenReady is false. It retires the preference epoch and
matching paused probe, then publishes Automatic through the existing owner/sequence. It issues no
preference request or Play and sets no explicit-release suppression. Pending Play, positive/Unknown
projection, accepted recovery, legacy controller churn, newer choices and retained explicit locks
are preserved. A later deliberate Play follows the normal Automatic policy; this does not identify
the physical control or promise a particular endpoint without measurement.

Durable `proof-prepared` rows label Initial/Final, whether that seek buffered, and captured
proof/request scope. `car-intent-retired` labels the old and replacement selection sequence.
These fields distinguish correction-budget exhaustion from missed buffering evidence on the next
device run without copying private device/media metadata.

## Verification and remaining acceptance

The corrected production-path buffering replay first fails unchanged production in four cases while
eight existing settling controls pass. An earlier replay consumed the final stage before injecting
the delay; it is not used as evidence of the missed-buffering defect. The real service projection
callback separately fails the departed-Car assertion while fourteen other cases pass.

Candidate tests cover Ready/barrier order permutations, delayed buffering notification, listener
cleanup, exact target proof, final +51ms/backward refusal, cancellation, paused departure, pending
Play, retained locks, successor selections and synchronous publication. Existing pinned Media3,
service/projection and S01–S22 tests remain. Final full local/normal CI counts are pinned in the
checkpoint, not inferred from this document.

The trace cannot prove that the bounded buffering correction fixes this phone's particular timing:
the first compensation stage was not labeled in 2244. Retest arrival churn, explicit Car→Headset,
then paused Car disconnection and manual headset/phone Play using one replacement APK. Record heard
destination separately from host state. A repeated final-stage overshoot still needs investigation;
do not accept it by raising the bound. Original-appearance loss and virtual-key attribution remain
separate physical capability limits. Genuine #288 HTTP 401 and #295 startup acceptance remain separate.
