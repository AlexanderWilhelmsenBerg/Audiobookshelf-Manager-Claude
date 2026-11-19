# Car-Bluetooth inferred arrival

**Requirements:** PLAY-002, ROUTE-001, ROUTE-002; PD-009 owner refinement 2026-11-16.
**Delivery:** PR 307, stacked on PR 306. Hardware acceptance pending.

## Evidence

Two hardware runs with one headset and wireless Android Auto (APK 2273 and 2274):

| Interval | 2273 | 2274 |
| --- | --- | --- |
| Car hands-free link to Auto's focus request | 10.0 s | 10.2 s |
| Safety pause to headset available again | 4.1 s | 2.4 s |
| Headset available to Auto's controller bind | 1.4 s | 3.9 s |
| Safety pause to verified route | 5.7 s | 6.4 s |

The system capture of the 2273 run shows Android classifying the car's Bluetooth device as car audio,
`com.google.android.projection.gearhead` requesting permanent focus three times within 0.26 s as
projection started, and abandoning focus 164 ms after BookWave's later request. A capture without a
headset showed two such requests 46 ms apart.

## Behavior

`CarBluetoothArrivalMonitor` listens for the public hands-free connection broadcast and opens
`CarBluetoothArrivalWindow` for 30 seconds when a device of class car audio connects. It needs the
existing optional Nearby devices access, reads no address into any log, and never scans or connects.

On a Media3 focus loss, `CarBluetoothArrivalInference` asks the gate for an inferred arrival only when:

- the gate's own decision was `Armed / FocusLossWaitingForBoundary`, which already requires a trusted,
  verified, playing headset and an open 30-second episode;
- the window is open and unused;
- Android's audio mode is normal.

The gate issues a normal arrival target marked `inferredArrival`. The resume effects use the
[inferred arrival settle wait](#faster-route-checks-2026-11-18), recheck the claim, then run the existing
route wait, muted probe and actual-track proof. The later Auto
bind finds the claim already issued and cannot issue a second Play. Each evaluation logs
`Car Bluetooth arrival inference` with `windowAge`, `audioMode` and `outcome`.

The gate also logs `Car continuity kept headset through a measured other route` when the two-second
grace from this PR resolves a speaker sample as part of an interruption.

## Automatic take-back experiment (debug builds only)

APK 2277 ran the inferred arrival on hardware: the window opened, the inference fired 1.6 seconds before
Auto's bind, and Play was issued 0.05 seconds after the headset returned. The pause was 3.5 seconds, the
time the car held the media device. In the previous run on an older build the car's media profile never
connected and the pause was 2.0 seconds.

`HeadsetTakeBack` is a seam in the main source set; release builds bind `NoHeadsetTakeBack`. In debug
builds `SystemRouteHeadsetTakeBack` uses the platform router adapter from the system-route lab:

- While BookWave measures its own track on the headset, it remembers the one Bluetooth system route
  Android has selected. Nothing is matched by name.
- On a route-safety pause that yields a trusted return claim, inside an open car window, the service asks
  for one take-back per car link.
- The request is made only if both Nearby devices permissions are granted, audio mode is normal, and the
  remembered route is listed and not selected.
- Each attempt logs `Headset take-back experiment` with `outcome` (`Requested`, `Selected`, `Failed`,
  `TimedOut`, `CallBlocked`, `PermissionRequired`, `HeadsetRouteUnknown`, `HeadsetRouteAbsent`,
  `AlreadySelected`, `Unavailable`), a Bluetooth route count and an elapsed time. No id or name is logged.

The same run showed the two-second return claim issuing a muted Play 32 ms after the safety pause, while
the displaced headset was still listed; the track went to the car's Bluetooth output and the proof was
refused. The claim now waits until the output inventory's membership changes, or 0.3 seconds, first.

Regression evidence for this part:

- `SystemRouteHeadsetTakeBackTest` (9, debug): requests only the remembered route, reports the matching
  selection's duration, ignores other selections and late timeouts, refuses unknown, absent, selected or
  ambiguous routes, blocks on a call or missing permission, never adopts the car's route after
  displacement.
- `PlaybackServiceHeadsetTakeBackTest` (4): one take-back per car link through the real service
  callbacks, none without a car link, the return claim waits while the displaced headset is listed, and
  still probes after 0.3 seconds when the inventory never changes.
- `CarBluetoothArrivalWindowTest`: one take-back claim per link, independent of the arrival inference.
- Counterfactuals: removing the inventory wait or the take-back call made the matching test fail.

Device acceptance for this part: repeat step 2 above with a debug build. In the export expect
`Headset take-back experiment outcome=Requested` right after the `routeSafety` pause, then
`outcome=Selected` with its elapsed time, then the headset returning and one verified resume. If the
outcome is `PermissionRequired`, enable the experiment once in the Audio Route Lab to grant access. If it
is `HeadsetRouteUnknown`, the phone did not report a single selected Bluetooth route while playing.

## Not covered

The 2026-11-17 build-2279 hardware run and owner-approved deferred take-back supersede the immediate
refusal of absent/already-selected system routes above. These states now wait for route callbacks within
the original link deadline; the allowance is spent only at a transfer invocation. Associations are pinned
and delayed work follows governor cancellation. See the
[hardware evidence, regressions and limits](2026-11-17-system-route-take-back.md).

- The car holding media audio for two to four seconds after it connects. No resume can start before the
  headset is the active media device again; the route wait covers it.
- Head units that report a Bluetooth class other than car audio. They keep the Auto-boundary behavior.
- Wired Android Auto with no hands-free link.
- Another media app started inside the window: BookWave would take focus back from it.

## Regression evidence

- `CarBluetoothArrivalWindowTest` (6): closed by default, age and expiry, single use, fresh window on a
  new link, peer-scoped close, backwards clock.
- `CarBluetoothArrivalMonitorTest` (6): only a connected car kit opens the window, nothing identifying
  is logged, disconnect and lost access close it, start/stop idempotent.
- `InferredCarArrivalTest` (8): resume issued with the original deadline, call modes blocked without
  spending the window, no window keeps the boundary path, untrusted headset refused, later bind cannot
  issue a second Play, a Play taken straight back may retry, listener cancellation, unrelated focus loss.
- `PlaybackServiceInferredArrivalTest` (4): real service callbacks and resume effects. One muted Play
  after the settle delay, unmute only on route proof, call stays paused, no window keeps waiting, Pause
  during the settle delay cancels.
- Counterfactuals: removing the service wiring, the audio-mode check or the settle delay each made the
  matching tests fail; all three were restored before the gate.

## Stationary device acceptance

### Faster route checks (2026-11-18)

Build 2281's two successful inferred arrivals took 752–771 ms from the observed focus loss to
verified headset playback, including the fixed 500 ms settle wait. Android was still publishing routing
changes near the end of that wait. Observe the existing route publications during the wait and admit an
earlier attempt only after 350 ms and a fresh exact-headset prediction observed for at least 75 ms.
A fresh framework query must identify the same sole live headset and account for every reported sink.
An unchanged, ambiguous or unavailable prediction retains the 500 ms fallback. The 350 ms floor leaves
margin over the earlier 256 ms focus burst. It is a starting value for hardware comparison.

Both timings feed the existing exact-headset recovery and muted verification. The active claim,
current scope and normal audio mode are rechecked before proceeding. Media3 handles audio focus.
The route must still be verified on the playing AudioTrack before audio becomes audible. Existing
arrival retry limits and the absolute episode deadline apply.

In a parked comparison, use one headset and let it play before connecting Android Auto normally.
Record the time from focus loss to verified playback and whether narration stayed on the headset.
Export the log even if there is no interruption. `arrival-settle` records `route-ready` or
`settle-elapsed` for the existing arrival attempt. The paired output-proof event reports the result.
Confirm that a deliberate Pause stays paused and that neither the car nor the phone speaker plays
audio during a headset recovery. Compare with build 2281 before claiming a hardware latency gain.

### Repeated route loss before the Auto bind (2026-11-17)

Requirements PLAY-002, ROUTE-001/002; owner-approved bounded rearm refinement in PD-009.
APK 2277 confirmed its inferred arrival's headset route and active playback, then safety-paused again
when the track moved to a remote mix about 394 ms later, before the trusted Auto bind. The second
interruption issued no claim; the later bind returned `CandidateInvalidated` and playback stayed paused.

`CarArrivalEpisode.rearm` now accepts either connected Auto or the existing episode's inferred-arrival
provenance. Its confirmed-route/playback requirement, identity and intent checks, claim budget and
absolute deadline are unchanged. There is no new timer, focus listener, player, or permission.

`PlaybackServiceInferredSafetyRearmTest` exercises the real service callbacks, command boundary,
coordinator, resume effects, missing-endpoint wait and muted route proof with the pinned Media3 player:

- The second safety loss works before or after Auto's bind, retaining the same episode, endpoint,
  generation, selection and deadline. Duplicate safety and bind callbacks cannot issue another Play.
- A returned endpoint remains muted until fresh route and active-playback evidence arrive.
- At most three Plays can be claimed before the bind; a fourth interruption and later bind stay paused.
- Listener Pause while waiting for the missing endpoint cancels the remaining Play.
- A waiting attempt expires at the original deadline, even if the endpoint then returns and Auto binds.
- Unconfirmed inferred playback cannot gain a safety retry from inventory return or the later bind.

Counterfactual: on unchanged `33f13f5`, four of the five tests fail at the missing second claim; the
unconfirmed-play refusal passes. Hardware acceptance of this correction is pending. The service fixture
injects route loss and platform callbacks; Robolectric does not establish audible headset continuity.
The exact final SHA, local verification and normal PR CI are recorded on PR #307.

Software verification on 2026-11-17: `ktlintFormat verifyDebug :playback:testReleaseUnitTest
-Pshelfplayer.warningsAsErrors=true` passed. Playback ran 1,282 debug and 1,264 release tests with zero
failures, errors or skips. The normal managed checkout was used; actual source formatting checks ran.

For a parked test, clear diagnostics, use one healthy headset, and connect the car without touching
transport controls. Repeat several times because the failing sequence is order dependent. A second
safety interruption after a confirmed inferred resume should retain the same episode and deadline and
use the next claim only once the same headset is available for a new muted proof. Repeat with listener
Pause during the gap: playback must remain paused through inventory return and the real Auto bind.

### Initial arrival and departure checks

1. Clear diagnostics and start capture. Play a book in one headset with the car off.
2. Connect the car and leave all controls alone for 30 seconds. Expect a pause when the car takes media
   audio and an automatic resume in the headset shortly after Auto's focus request, before
   `A car connected to the media session`. Note the heard silence.
3. In the export, find `Car-class hands-free link connected`, then `Car Bluetooth arrival inference`
   with `outcome=resume-issued`, then `Muted output route proof completed confirmation=Verified`.
4. Repeat with a deliberate Pause just before connecting. The book must stay paused.
5. Repeat while a phone call is ringing or active during connection. The book must stay paused and the
   line must read `outcome=blocked-by-audio-mode`.
6. Disconnect the car and confirm departure recovery is unchanged.
7. Revoke Nearby devices access and repeat step 2. Expect the previous behavior: resume at Auto's bind.
