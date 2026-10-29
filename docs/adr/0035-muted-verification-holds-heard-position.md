# ADR-0035 — Muted verification holds the listener's position

**Status:** Implemented candidate; full strict local verification passed. Exact-head CI is tracked in #300.
Hardware acceptance pending.
**Requirements:** PLAY-002/003/004/005/008/009, ROUTE-002, SYNC-002 and PRODUCT_SPEC 6.5.

## Context

An internally muted Media3 player can keep advancing. Excluding that time from sleep and listened-duration
accounting does not prevent the journal, session sync or history from recording unheard progress. A cancelled
probe can also outlive Stop, because Media3 may retain PlayWhenReady while becoming idle.

## Decision

The existing `HeadsetRouteGuard.Probe` owns one immutable `MutedPositionCheckpoint`. Capture happens after
reserving that exact probe and before acquiring mute can invoke synchronous listeners. It carries the existing
player/item, book/profile, command/item/profile generations, listener selection and preference-effect scope.
Reentrant capture can neither mute a superseded request nor erase a replacement probe. There is no additional
sequence, player, focus owner, session or durable progress store.

The service-owned `MutedPositionEffects` supplies the shared effective position. Journal, session sync,
transport/history, sleep history, bookmarks and controller/profile-handover reads project the held position
while that exact media remains muted. Zero and file-fallback positions can be held for playback safety;
existing persistence exclusions apply to the projected point. Muted raw IsPlaying does not sample listening
history or create chapter-crossing evidence.

Forwarded movement enters the position boundary before existing command invalidation. Pause/Stop restore
silent advancement while still muted, forward the real command, then retire the probe. Seek supersedes old
rollback, and relative commands calculate their target from the held position. A newer Play or replacement
cannot inherit an old timeout. Logical output lock policy remains in `OutputIntentOwner`.

Current-item queue replacement has a distinct movement kind: Media3 can internally prepare the successor
without another controller Prepare. With a guarded destination, inherited PlayWhenReady is safely cleared
before replacing/removing the current item or inserting the first item into an empty queue. Edits confined
to future items preserve healthy transport. Existing command/item/profile, attachment and selection authority
are checked after synchronous pause listeners; a newer seek or selection refuses the older raw mutation.

Compensation seek provenance lasts only for its synchronous raw seek call and its captured existing command,
item and profile authority. A nested newer user command cannot inherit the correction marker; a superseded
outer command is refused before raw transport. The actual service's pinned Media3 callback tests distinguish
that correction from both nested and later genuine user seeks. Legitimate adopted seeks and
auto-rewind replace the same probe's held point, retire an older completion and require post-seek proof.

After compensation, a bounded cancellable Media3 playback-thread message barrier precedes fresh playing-track
queries. Barrier delivery alone is insufficient: the current player/item/command/profile/selection, Ready and
PlayWhenReady/IsPlaying state, exact live endpoint and collision checks must still agree. Unknown, mismatched
or multiple sinks fail closed. Timeout restores any further silent advancement before safety Pause and volume
release. Repeated callbacks cannot start another completion. No application thread blocks for the barrier.

The 2242 device test exposed a missing distinction: a playback-thread message orders the seek but does not
freeze a playing media clock. Verification must not keep seeking back to demand exact position equality.
After the initial compensation, buffering waits for Ready without another seek. A genuinely playing,
unknown-route interval may require one further compensation when proof arrives; that extra correction is
bounded to once per completion. All post-seek target proof remains fresh and exact. At the final position
validation, normal media advancement of zero through 50ms is allowed; this is a small residual unheard-media
budget, not permission to accept a different endpoint or stale evidence. Larger or backward movement restores
the held point and pauses instead of scheduling another verification seek. Explicit transfers recheck this
bound after synchronous success publication, before releasing mute, with the prior lock retained as scoped
recovery context. Existing scope,
timeout, cancellation, sleep/listened-duration and user-seek provenance rules remain in force.

Immutable event snapshots travel into asynchronous persistence. An old pause may persist its captured local
owner's point, but cannot establish a successor baseline or sync a successor after waiting for the session
mutex. Authorization is checked inside the serialized main-thread capture. Auto-rewind history likewise
captures book, profile and event time before dispatching its write.

## Evidence and limits

Production-service regressions reproduce raw 12s-to-25s muted advancement and pre-return mute-listener reads.
Separate mutations remove checkpoint visibility and compensation provenance; the tests must fail at the
position/write assertions. A queued old-pause regression crosses the real session mutex. Controlled real
Media3 PlayerMessage delivery tests barrier ordering, repetition, cancellation, timeouts and fresh proof;
actual ExoPlayer/service seek callbacks establish the provenance contract. These tiers do not prove physical
audio output or narration heard on a headset/head unit.

Formatter and strict repository `verifyDebug --continue -x assembleDebug -Pshelfplayer.warningsAsErrors=true`
pass: 2,700 tests /319 suites /0 failures, errors or skips, including 884 playback tests. Lint, Detekt and
coverage pass. APK assembly is excluded. The nested-command regression first failed at the intended real
user write assertion; the restored fix also prevents the superseded outer raw Pause. The first full run
found three command-admission complexity findings; simplifying that decision and rerunning the complete
gate resolved them without suppression.

Eight current-item mutation regressions cover guarded replacement/removal/first insertion, future-only and
unguarded controls, and synchronous newer seek/Car intent. Before the fix, three raw mutations retained
unverified PlayWhenReady while both controls passed; first insertion and seek supersession separately failed.
Removing exact-selection admission reproduced the Car supersession failure after its callback was witnessed;
the original bytes were restored. Pinned Media3 uses singular overloads for single-item mutations, and the
observer checks both forms. The initial observer-only failure is not regression proof. A full run caught
eight ignored test future results; explicit result assertions and the complete rerun passed without suppression.

Explicit verified Car/Headset transfer, prior-lock commit/rollback and final output-status UI remain subsequent
#277 work. The final combined APK and the device acceptance ledger are required before physical acceptance.
