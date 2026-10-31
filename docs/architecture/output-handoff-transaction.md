# Output handoff transaction redesign

**Status:** Implemented candidate; exact-head gates are tracked in #289/#300. Hardware acceptance failed on APK2246.
**Requirements:** PLAY-002/004/008/009, ROUTE-001/002; retain S01–S22.
**Baseline:** PR300 b30ba552, combined PR301 6a56e907. GitHub is authoritative.

## Decision

Keep OutputIntentOwner as the intent/selection authority, the existing Probe as the sole transient
claim/checkpoint owner, and PlaybackVolumeEffects as the volume writer. Retain one ExoPlayer,
MediaLibrarySession, endpoint registry and Media3 focus owner. The transaction completion driver owns
the ordered asynchronous stages; position effects supply checkpoint and seek/barrier operations,
and the guard supplies reservation, cancellation and safe release. No second retry executor is added.

For a real destination change:

1. Reserve and capture the current position before acquiring mute or publishing intent.
2. Apply the current request once. While muted, await fresh target-specific playing-route evidence.
   Do not seek the original output merely because it is still playing.
3. Restore the held position once and await the playback-thread acknowledgement. This may replace
   the AudioTrack; the acknowledgement itself proves neither its route nor its clock health.
4. Verify current playing output again and enforce the existing 0–50ms position bound. Never return
   to a live compensation stage. If this fails, pause before restoring the held point and finish
   refused. Failure restoration does not start another verification cycle.
5. Recheck current scope and proof around final publication, then release mute. Newer command,
   selection, item/profile, attachment or checkpoint invalidates the old completion.

The same driver serves implicit headset probes and explicit transfers. Paused selection does not
start playback. An existing authorized Play owns any start; the transaction never manufactures Play.
Waiting is event-driven with bounded failure deadlines, not a fixed settling delay.

A loaded, unmuted player already measured on the explicitly requested destination may preserve its
output. New intent still supersedes older work and receives fresh proof, but does not acquire mute,
seek, request the preferred device or issue Play/Pause. Pending probes and uncertain/mismatched output
cannot use this shortcut. If synchronous publication invalidates the preserved route, fail closed.

## Observation and physical acceptance

Destination verification is separate from output-clock health. Capture bounded opt-in output timing
around track attachment/routing: configuration, effective speed/pitch, frame-head/timestamp samples,
underrun deltas and the associated observer generation. Do not infer physical media-button origin.
Missing timestamps are explicitly unknown/unsupported, not automatically faulty. Short-term timestamp
jitter must not become an unvalidated production pause heuristic. No acoustic success is inferred from
route identity, IsPlaying or these diagnostics alone.

Compare running transfer with a deliberate pause/select/resume on the same device, media and speed.
This candidate removes repeated live compensation but does not claim that doing so fixes the native
distortion. A later timing-fault policy needs the resulting evidence, valid-speed/seek controls and
last-trustworthy-position handling; arbitrary thresholds must not corrupt progress or block playback.

## Verification

- Demonstrate new regressions fail on the baseline: no early seek while old route remains; no second
  proof/correction cycle after buffered overshoot; same-target explicit selection preserves output.
- Retain wrong/unknown/multiple output refusal, scope invalidation, repeated callbacks, old barrier
  rejection, genuine seeks, position/history/profile/sleep integrity and paused semantics.
- Exercise actual ExoPlayer seek/barrier behavior and the production observation provider separately;
  native Auto/A2DP timing remains a physical-device tier.
- Run formatter, strict verifyDebug and normal exact-head PR CI. Update the combined QA branch only
  after the feature gate. Build the authorized signed APK after combined CI succeeds. Do not merge.
- Hardware: repeated Car→Headset→Car while running and paused, healthy same-target selection,
  arrival retention, no speaker spill, no skipped/jumbled narration, supported non-1x speed,
  diagnostics off/on and current progress after Pause. Retain the under-one-second interruption target.

The broader review and exact CI/device checkpoints are in GitHub issue #289.

## Diagnostic compatibility and limits

| Evidence | Compatibility | Meaning |
| --- | --- | --- |
| Native frame head, timestamp, sample rate/channels/encoding, buffer size, underrun count and native speed/pitch | Public APIs available at the app's API26 minimum; SDK34 lifecycle tested | Numeric measurements tagged with the current observer epoch; unavailable reads stay null |
| Media3 speed/pitch | Current single player's parameters at restoration and on parameter changes | Distinguishes Media3's requested/effective parameters from native AudioTrack parameters; Media3 may process speed in software |
| Routed devices | Existing API36 multi-route path; earlier APIs retain single-route observation | Route identity evidence, not clock-health or acoustic proof |

Capture produces at most 16 samples spaced 250ms apart per burst, with a five-second cooldown for the
same observer epoch. Replacement retires old work; callbacks cannot extend the burst. Recording off
performs no native timing queries. Data uses the existing opt-in private trace, row limit and TTL, with
no media/device names, addresses or persistent identifiers. Raw counts permit offline deltas; frame-head
wrap/reset and output generation must be respected when interpreting them.

Android documents timestamps as potentially unavailable or unstable during route changes and warns
against inferring fine-grained behavior from short intervals. These bounded incident samples are
observational, not a mandatory timestamp gate or a new playback delay. See the official
[AudioTrack timestamp contract](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)).

The production-provider integration uses real pinned ExoPlayer, a silence source and the actual observed
AudioTrack provider. Its routing evidence is injected, and Robolectric's native audio is simulated. It
checks provider attachment, seek/barrier delivery and timing capture; it cannot reproduce OEM Auto/A2DP
clocks, speaker output or the reported distortion.

## Local software checkpoint

Formatter and strict `verifyDebug --continue -x assembleDebug -Pshelfplayer.warningsAsErrors=true`
pass: 2,841 tests across 343 suites, including 1,017 playback tests, with zero
failures/errors/skips; lint, Detekt and coverage pass. No dependency/classpath change was made. The
initial full run passed runtime tests but exposed typed diagnostic lint findings; injecting AppClock
and explicitly rendering unavailable numeric values resolved them without suppression.

Baseline counterfactual: the three initial transaction cases produced two intended failures (early
restoration and unnecessary same-output effects); the excessive-drift refusal control already passed.
The final cases also exercise reentrant route loss/newer selection and native capture lifetime. Agent00
reviewed source/callers and invariants. Additional independent review remains outstanding. jCodeMunch's
main-only index returned no reference edges for the monitor despite current local callers, so it is
navigation evidence, not a complete call-graph proof. Exact committed CI, Repowise comparison, combined
QA and signed APK results belong in the live #289/#300/#301 checkpoints. Physical acceptance is open.
