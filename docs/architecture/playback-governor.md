# Playback governor

**Status:** Implementation candidate, dependent on draft PR305. Physical acceptance remains open.
**Owner decision:** 2026-11-07; PLAY-001/002/004/008/009, ROUTE-001/002; preserve S01-S22.

## Responsibility and execution

PlaybackGovernor arbitrates synchronous listener commands and observed interruptions on the existing
Media3 player thread. PlaybackService owns the single ExoPlayer, MediaLibrarySession and service lifetime.
There is no suspending command mailbox: Pause, safety muting and invalidation cannot wait for REST,
Room or a route probe. Media3 remains the sole focus owner.

The governor coordinates existing authorities instead of mirroring player state:

| Authority | Owned truth |
| --- | --- |
| OutputIntentOwner | Listener destination, selection sequence, output lock and transfer |
| LiveOutputEndpointRegistry | Live endpoints and process-private exact identity |
| AudioTrackRouteMonitor | Actual current track observations |
| CarContinuityRecoveryCoordinator and its gate | Episode, attempt eligibility and single-use claims |
| ResumeFreshnessCoordinator | Freshness evidence and resume request generations |
| SleepTimerController | Timer phase, schedule and grace request authority |
| BookChanges / SessionSyncCoordinator | Ordered book acceptance, journal/session/outbox and progress |

PlaybackResumeEffects orders held-book materialization, sleep authorization, freshness and final resume.
The governor captures command/profile/player authorization before this work suspends. Existing selection
and resume generations are checked at the corresponding specialist boundaries. Materialization may
legitimately replace a metadata holder within the same command; it cannot authorize another profile or
newer request. History recording remains with its current owner.

## Entry paths

| Entry | Arbitration and effects |
| --- | --- |
| Phone, notification, headset and Auto standard transport | MediaSession forwarding player -> governor -> resume/movement specialists |
| Phone chooser and Keep/Release | PlaybackOutputCommands -> attached governor -> existing router/intent owner |
| Dashboard output actions | Governor output command -> existing router/intent owner |
| Sleep grace | Governor -> PlaybackResumeEffects -> shared freshness and command gate |
| Raw focus/noisy/safety/user/end observations | Typed interpretation -> governor -> continuity/rewind owners |
| Book/queue change and teardown | Governor cancels pending effects; existing generations revoke suspended results |
| Retry, device policy and automatic series advance | Existing scoped DeferredPlaybackWork -> guarded command effects |
| Auto paused holder / explicit holder materialization | Guarded install-only command, without prepare or Play |

Physical input evidence is captured before the controller command generation advances. Unknown input
stays unknown. The dispatcher does not strengthen Bluetooth inference or bypass an output lock.
The output command port stores only a service callback. Detached selection keeps existing intent-only
behavior and never starts a service or queues a future Play. Detachment is identity-scoped so an older
service cannot clear a successor's command handler.

PlaybackCommands remains the raw activation/install boundary. Install-only and install-and-prepare
share attachment, authorization and inherited-Play protection. Seek/Stop/queue mutations still use the
forwarding player's position checkpoint boundary and governor invalidation before Media3 effects.
Do not redirect internal position compensation through user-command invalidation: the handoff's single
checkpoint owner already scopes it.

## Bounded recovery

A BookWave safety pause of previously verified active headset playback can issue a `HeadsetReturn`
claim with an absolute two-second deadline. The recovery gate owns this short allowance through its
small `HeadsetReturnRecovery` helper; the existing coordinator, effects and router perform the work.
There is no new reconnect listener, player or transport owner. The same headset must return to inventory
and pass the existing muted actual-track proof before the deadline. Duplicate safety callbacks cannot
restart it. A noisy notification during the same gap preserves only the still-unconsumed claim; after
Play it cannot rearm that attempt. New intent, identity loss and subsequent focus loss revoke it. This is a
narrow exception to car-boundary pairing, not permission to restart a user-paused book on connection.
An expired or cancelled short attempt does not consume the separately qualified car-arrival budget.

An arrival episode retains the exact previously measured headset and its book/selection scope. Its
absolute deadline is 30 seconds from the earliest qualifying interruption; this is not a waiting delay.
The initial trusted-arrival allowance is preserved. A successful route recovery does not reset the
deadline or attempt count.

At most three automatic Play claims are permitted: the initial claim and two further interruptions.
Rearming requires the same headset to have been measured while actually playing after the preceding
issued Play, current ownership/trust, connected Auto, and a new route-safety interruption. It does not
require another connection event inside the already confirmed episode. Duplicate callbacks, preferred
device acceptance, playWhenReady and inventory return cannot rearm. Failed or unproven attempts cannot
retry themselves.

Each attempt is single-use and scope-bound. Episode lifetime and focus/boundary pairing use separate
clocks. Departure terminates arrival work and retains the existing conservative six-second pairing
rules using the fresh departure event, never an old safety-pause timestamp. No generic reconnect Play
is introduced. Explicit Pause, Stop, seek, output/book/profile change and teardown retain precedence.

Healthy verified headset playback is preserved without preference resets, seeks or artificial delay.
If actual routing becomes unavailable or wrong, safety still pauses. Recovery uses the existing player,
muted probe, position checkpoint and actual AudioTrack proof before unmute. Registry availability is
not physical output proof. No extra AudioTrack, second player or zero-silence guarantee is introduced.

### Early projection observation

An eligible arrival focus loss can precede both the car's media-session bind and its projection broadcast.
While the existing gate is Armed with `FocusLossWaitingForBoundary`, CarConnectionRefreshWindow requests
read-only provider refreshes at 0, 250, 750, 1500, 2500, 4000 and 6000 ms. These are observation times,
not playback delays. Duplicate interruptions cannot restart or extend the burst. The window owns no
connection truth and cannot issue Play, request focus, select a device or create another AudioTrack.

AndroidAutoProjectionMonitor remains the one provider adapter. Reads are serialized and concurrent
requests coalesce to one pending refresh; service cancellation discards suspended results. Provider
latency may outlast the observation window. Only actual state changes reach the existing lifecycle
authority. Initial/Unknown results and unchanged observations do not manufacture an arrival.
Controller connection, positive projection, observed Play, listener/context invalidation and teardown
cancel future refresh requests. A read already in flight may still report genuine lifecycle state;
it cannot restore cancelled recovery eligibility or beat newer intent.

This can shorten recovery only if the provider exposes Projection before the delayed broadcast/bind.
It cannot prevent Android focus loss or prove that another process has released focus. Media3 remains
the sole focus owner. See the [focus investigation procedure](../testing/2026-11-07-early-auto-observation.md).

The passive `PlaybackFocusDiagnostics` listener correlates Media3 suppression, loss-or-denial and
playback activity without authorizing recovery. It explicitly marks raw Android focus type and
requester unavailable; system capture supplies those only where exposed. See
[focus evidence and capture](../testing/2026-11-08-focus-diagnostics.md).

Service bind, session lookup and controller-connect entry diagnostics add monotonic timestamps and
classified protocol/controller facts. Generic legacy binds remain unattributed. These observations
do not start a trace, advance the lifecycle authority or authorize recovery; an existing trace may
provide correlation. Trace openings also carry the monotonic clock so earlier entries can be aligned
without mixing phone and desktop wall clocks. No raw intents, extras or controller identifiers are
added. See the [DHU findings and bounded route checks](../testing/2026-11-07-fast-route-observation.md).

## Evidence and delivery

Opt-in `SystemAudioProbe` adds public Bluetooth-profile and system-audio observations through the
existing OutputClassifier lifetime. It joins process-private exact identities against the existing
registry and reads the selected/last-heard target; it cannot reconcile endpoints, request focus,
authorize recovery or call a player. Profile connectivity, streaming and audio mode are observations,
not route proof or a focus-owner identity. The bounded `BluetoothProbeEvents` queue preserves callback
transitions across coalesced reads. `PublicAudioActivity` summarizes anonymous public attributes;
`MediaCommandTiming` records capture-local input ordering using the key event's uptime clock. These
adapters have no player or routing mutation dependency. See [scope and acceptance](../testing/2026-11-08-system-audio-probe.md)
and [probe research](../testing/2026-11-08-public-probe-expansion.md).

Diagnostics correlate process-local episode/attempt numbers, phase, decision reason, remaining lifetime
and Play-issued versus audible-confirmed outcomes. No Bluetooth address, device name, media metadata or
identity correlation key is added. A playing-track observation is software evidence, not an acoustic
measurement or proof of physical button origin.

See [the implementation and acceptance record](../testing/2026-11-07-playback-governor.md). Unit and
service tests establish ordering and cancellation; repeated stationary car tests establish audibility,
actual destination and interruption duration. Main and PR305 remain unchanged until explicitly merged.
