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

The gate issues a normal arrival target marked `inferredArrival`. The resume effects wait 0.5 seconds,
recheck the claim, then run the existing route wait, muted probe and actual-track proof. The later Auto
bind finds the claim already issued and cannot issue a second Play. Each evaluation logs
`Car Bluetooth arrival inference` with `windowAge`, `audioMode` and `outcome`.

The gate also logs `Car continuity kept headset through a measured other route` when the two-second
grace from this PR resolves a speaker sample as part of an interruption.

## Not covered

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
