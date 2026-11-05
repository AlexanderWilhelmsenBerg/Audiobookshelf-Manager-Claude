# Output ownership and handoff device acceptance

**Status:** owner-reported device failure on2244; replacement acceptance pending.
Updated 30 October 2026.
**Requirements:** PLAY-002/004/005/008/009, ROUTE-001/002/003.

Use this procedure for the combined candidate described in
[the execution plan](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6445791504).
PR #297 supplies transient route-loss protection. The dependent #276 lock, #277 handoff, selection/UI
and separate auth software are integrated in the single QA branch below. The 2242 device report exposed
seek/buffering feedback; [the regression follow-up](output-settling-regression.md) records its software
correction. A green build or earlier smoke test does not accept physical routing.

On2243 (`855f620e46b4`), the owner reported retained headset output at Auto arrival, headset transport
keeping that output, Car transport selecting Car, working explicit Headset/Car actions, and no resume
when departing while paused. Later headset Play still resumed Car; captured events had virtual input
ID-1/source0 and did not identify a physical headset. These are positive results for the reported
configuration and an unresolved attribution capability, not acceptance of every matrix row or the new
diagnostic candidate. The trace also contains a focus-loss pause/recovery, so retention alone does not
certify uninterrupted human-heard audio.

On2244 (`c501404e0ba9`), the owner reported lost audio at Auto connection, failed explicit Headset
transfer, and refused manual playback after car disconnection. The capture shows original endpoint
churn,116ms final settling refusal after a measured Bluetooth route, and retained Car intent after
paused projection departure. The [regression assessment](output-2244-regression.md) separates these
failures from virtual-key attribution. The replacement adds bounded buffering recovery and scoped
paused-Car retirement; the unlabeled2244 correction stage cannot establish a hardware fix. Repeat
the matrix and inspect Initial/Final proof-stage rows before accepting the replacement.

The following paragraphs retain earlier software checkpoints. The current candidate and its exact gates
are maintained in the execution checkpoint linked below.

The #300 command-boundary checkpoint covers loaded fresh-start/duplicate controller Play, controller Prepare,
retained-intent retry, accepted deferred/post-connect installs and continuity through one service guard. Its
software tests are extended by the position and explicit-transfer checkpoints below. The final combined
candidate must include all remaining integration slices before the complete-feature rows can be accepted.

The following position candidate adds a probe-owned checkpoint and shared heard-position reads. Its
software checks include elapsed advancement, pre-return mute callbacks, nested/later real service seek provenance,
Pause/Stop retirement, a queued old pause crossing the session mutex, and controlled barrier/proof delivery.
These do not fill any physical row below.
Guarded current-item replacement and first-item insertion also clear inherited Play intent before mutation;
future-only queue edits preserve transport. Newer synchronous Seek/Car intent refuses the old raw setter.
The transfer checkpoint adds current target proof, retained prior lock, safe rollback/failure, callback
supersession and API26/32 production-command cases. Its full strict local repository gate passes 2,748
tests /322 suites, including 932 playback tests, with no failures, errors or skips. Exact-head CI and the
source SHA are recorded in #300 and the execution checkpoint. No new APK is claimed here.

The selection-scope checkpoint extends the strict local gate to 2,759 tests /325 suites, including
943 playback tests, with no failures, errors or skips. Pending controller queues, accepted-connect
restoration, retry, Prepare and Play lose authority to a newer output choice; captured pause writes
retain their original profile/book/position. A real Media3 cold-callback regression proves that a
cancelled old selection cannot install or fallback Play over the current item. Muted heard-route,
presentation and final auth integration are still pending; this does not fill any physical row.

The heard-route checkpoint adds typed selection and one service audible observer, with strict local
verification passing 2,765 tests /326 suites /949 playback, no failures, errors or skips. Actual service
callbacks under route mute cannot create heard evidence or consume recovery. Successful current-player
unmute, paused release, player replacement and listener removal during dispatch are covered. Output
presentation and final auth integration still precede the combined APK; no physical row is filled.

The phone presentation checkpoint passes the strict repository gate at 2,784 tests /327 suites /
960 playback, no failures, errors or skips. It distinguishes requested, measured, unknown and delegated
paths plus retained previous choice; selected semantics, full-contrast status and actual doubled-font
rendering are covered. The controller keeps one typed selection command. Final auth integration,
forced combined verification, exact CI and review still precede the APK; physical rows remain pending.

## Candidate and setup

The combined branch `codex/289-ownership-device-test-0c966ed6` contains playback source
`b30ba552ef6846ed6b9f3cae9dc0acbd2647e5c9` and separate auth #296 source
`9379a828c504eb56b0122d876d3f2669beb2363a`, integrated locally at `772366b8dc66f2fde862e8f2023ed9db4a9d637a`.
The already-identical Windows recorder-test compatibility change is not duplicated. No GitHub PR was
merged, and main is unchanged. The exact final source/tree, forced local verification, normal CI,
independent review and signed APK identity are maintained in
[the execution checkpoint](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/289#issuecomment-6448375926).
Hardware acceptance must use that one final APK, rather than a different APK for every draft PR.
Build 2242 (`27c1a921dc0c`) is retained as failed device evidence; it does not verify the replacement.
The branch-name suffix is historical: use the exact commit and APK version from the checkpoint.
The current addition isolates Android output access and preserves a measured healthy headset before
requesting preferences. Eligible paused recovery can begin its one muted probe promptly; actual output
proof still gates unmute. Follow the [adapter continuity procedure](output-adapter-continuity.md) for repeated
arrival and the less-than-one-second audible-gap target, as well as the [extended input/Auto capture procedure](unified-diagnostics.md#input-and-auto-capability-capture)
and [platform research](../architecture/playback-input-capability-research.md) for the next test.

Test one combined APK containing the selected playback stack and the separate #296 auth fix. Record
its build number, exact source commit/tree, APK workflow run, and installed application identity.
Use the same installed candidate for every row; changing the candidate invalidates affected results.
Never test a parent PR's APK as evidence for a later child PR.

Use a stationary vehicle or Android Auto DHU. Start with a downloaded book, a charged headset, and
an identifiable passage. Record Android version, wired/wireless projection, host version, headset
configuration, permissions and the host's autoplay setting. Repeat the applicable paths with the app
in the foreground and background. Diagnostics are optional for ordinary safety behavior; enable
privacy-safe route capture for a repeat if needed. Do not upload raw diagnostics to a public issue.

Separate what you **hear** from what Android **reports**. Record silence duration, skipped narration,
repeated narration and displayed position separately. A measured RemoteSubmix identifies a framework
mix path. Interpret it with current projection state; it does not uniquely identify Auto or the final
physical speaker. A virtual media key does not identify its headset.

## Stationary routing checks

| Case | Steps | Expected result | Result |
| --- | --- | --- | --- |
| Healthy automatic arrival | Play on the headset in Automatic; connect Auto without pressing any control. Repeat with host autoplay enabled and disabled. | A still-live, measured headset is retained without unnecessary preference reassertion. Any observed loss or mismatch safely pauses. |2243: retained output reported; repeat/current-candidate matrix pending |
| Explicit lock | While headset audio is playing, open the phone output chooser and choose **Keep audio here**. Connect Auto. | Active lock remains on the confirmed target. Binding and generic host Play do not transfer it. | Pending |
| Headset transport | With the lock active, press Pause then Play on the headset before and after Auto connection. | Pause remains authoritative; Play uses the owned destination or stays safely paused when it cannot be verified. It does not manufacture headset identity. |2243: ordinary retained-headset transport worked; explicit-lock/current-candidate variants pending |
| Generic Auto transport | With the lock active, use the Auto screen's ordinary Pause and Play controls. | Transport preserves the lock. A generic car-controller request does not silently become a destination choice. | Pending |
| Explicit Car transfer | With the lock active, press the existing **Car** destination action. | The newer explicit destination supersedes the lock through verified handoff. Success requires adequate current projection/output evidence; otherwise playback stays paused with truthful status. | Pending |
| Explicit Headset return | After confirmed Car transfer, press the existing **Headset** action or select the live headset in the phone chooser. | The current exact headset must be verified before audible playback there. A virtual headset Play alone does not authorize a transfer. | 2242: jumbled audio;2243: explicit return reported working; current diagnostic candidate pending |
| Lock release | Choose **Release lock** or explicit **Automatic**. | The lock releases as a new choice. Retired observations cannot resurrect it. | Pending |
| Endpoint loss | While locked and playing, cause genuine output disconnection; repeat during Auto arrival. | No continued audio on speaker, car or another headset after invalid routing is observed. Playback pauses and retains a truthful waiting/unverified state. Record any residual callback-to-pause latency. | Pending |
| New appearance | Reconnect the disconnected headset, then repeat with a second headset sharing its display name. | No silent adoption by name, list order or sole-Bluetooth status. No autoplay on reconnect. A fresh explicit choice and current proof are required when identity cannot be established. | Pending |
| Missing inventory | Leave no selectable external output while the lock is waiting. | Lock status and release remain reachable in the phone chooser. | Pending |
| Unknown/conflicting proof | Exercise unavailable route measurement or two live candidate outputs, including denied optional permissions. | No successful/active claim based only on preferred-device requests or predicted policy. Unknown or conflicting verification fails closed. | Pending |
| Repetition and cancellation | Repeat connect/disconnect and Car/Headset selections. During verification, press Pause, select another destination, change book, clear the queue, or switch profile. | Only the latest authorized operation may take effect. No late Play, rollback, unmute or change to the replacement book/profile. | Pending |

A real endpoint disappearance may require silence. Acceptance distinguishes that necessary safe pause
from an avoidable interruption while the same verified output remains live; it does not promise audio
through an absent endpoint.

## Playback integrity and lifecycle

| Case | Expected result | Result |
| --- | --- | --- |
| Handoff near a sleep-timer tick/fade/expiry | A timer volume update cannot unmute an unverified route. Muted verification is not counted as audible listening; existing expiry, grace and rewind rules remain intact. | Pending |
| Progress, history and position | Transfers do not restart the book, skip narration during silent verification, duplicate a playback session, or write progress/history for a retired book/profile. Speed and queue remain intact. Record start/end positions around success and failure. | Pending |
| Navigation, transient focus and calls | Media3 remains the audio-focus owner. No recovery on a wrong route, fabricated physical origin, or repeated auto-rewind from a safety pause. | Pending |
| Nonempty book/source replacement | Logical lock target survives; old route proof is invalidated and the new playing track is verified. No old-generation effect affects the new source. | Pending |
| Stop, empty queue and profile handover | Runtime lock and queued effects release at the existing ownership boundary. Account progress remains isolated. | Pending |
| Activity rotation/recreation | Chooser state reflects the same playback owner; no extra player/session, duplicate command or lock release. Check large text and accessibility labels. | Pending |
| Service/process recreation | Ephemeral endpoint IDs and lock claims are not restored as physical identity. Explicit later Play follows normal cold-start and route policies. | Pending |
| Cold entry | Notification, headset and Auto entry paths preserve normal foreground-service timing, locked-profile and failed/cancelled startup behavior. Test beyond the former startup deadline. | Pending |
| Capture disabled | Safety, lock and verification operate with diagnostic recording disabled. | Pending |

Retain the complete [S01–S22 ledger](playback-scenario-coverage.md); this procedure supplements it.
The #295 Android 16 startup cases and #288 real expired-token recovery remain separate acceptance
criteria. Ordinary successful sync requests do not exercise HTTP 401 renewal.

## Evidence and disposition

For each failure or pass, record candidate identity, test row, exact control used, initial destination,
human-heard destination, reported actual route category, final lock/handoff state, book position and
silence/skip/repeat observations. Keep private identifiers and media metadata out of issue summaries.
Mark an unsupported observation as unsupported, with its fallback behavior, rather than PASS.

Software gate, physical routing, and server-auth acceptance are separate results. Keep related PRs
draft and issues open until their own acceptance evidence is recorded; this procedure authorizes no
merge and certifies no physical result.
