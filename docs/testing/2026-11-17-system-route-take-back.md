# Deferred headset system-route take-back

Requirements PLAY-002, ROUTE-001/002; PD-009 owner refinement, 2026-11-17. Debug-only continuation of
PR #307. One existing ExoPlayer/session and Media3 focus owner remain unchanged.

## Hardware evidence before this correction

Source: opt-in `bookwave-diagnostics-20261117-125338.json`, build 2279 at `f437c61b28ff`.
Raw diagnostics stay local. Times below are UTC. The tester reported a successful first attempt and
sound in the car as well during the second. The export cannot establish simultaneous acoustic output.

| Time | First arrival |
| --- | --- |
| 12:52:31.577 | Route-safety pause after the original headset disappears. |
| 12:52:31.580 | System headset take-back `Requested`. |
| 12:52:32.264 | Original headset's muted actual-track proof verified, 0.687 s after pause. |
| 12:52:32.461 | Matching system selection callback, 0.882 s after request. |
| 12:52:32.825 | Original Bluetooth peer reports streaming, 1.248 s after pause. |
| 12:52:35.012 | Later Media3 focus loss; inferred recovery verifies output after 0.725 s. |

| Time | Second arrival |
| --- | --- |
| 12:53:23.718 | Media3 focus loss; inferred recovery verifies output after 0.677 s. |
| 12:53:27.510 | Guard reads the playing track on RemoteSubmix, then safety-pauses. |
| 12:53:27.520 | Same recovery episode rearms with attempt 2; the earlier rearm fix is reached. |
| 12:53:27.535 | System take-back reports `AlreadySelected`; no transfer is invoked. |
| 12:53:27.832 | Car peer occupies the A2DP endpoint; it differs from the retained headset identity. |
| 12:53:30.659 | Returned endpoint matches the original headset peer. |
| 12:53:30.723 | Fresh original-headset output proof verified, 3.212 s after safety pause. |
| 12:53:31.646 | Original Bluetooth peer reports streaming, 4.135 s after safety pause. |

The first matching selection callback demonstrates the public request path on this phone, not that
the request alone caused restoration. The second attempt exposes disagreement between system selection
and actual output. The old helper ended on `AlreadySelected` and spent its link allowance before calling
the platform. Repeated headset verification could also overwrite its association from system selection;
the export does not establish whether that happened in this run.

## Correction and simplification

- `CarBluetoothArrivalWindow` stores one link record with inference/transfer allowances. A take-back
  scope captures that exact link and its existing absolute lifetime; reconnect cannot refresh an old scope.
- `SystemRouteHeadsetTakeBack` has one optional attempt: waiting or requested. Absent/selected snapshots
  remain waiting, with no polling or extra timer. Callback evaluation expires against the original window.
  The window allowance is consumed immediately before a transfer invocation, including one that throws.
- Measured return ends waiting. An issued request may still log its matching selection callback; neither
  callback nor selected state grants Play, unmute or physical output proof.
- Associations are pinned per endpoint. New learning requires positive car absence and a closed arrival
  window, including discovery callbacks delayed after the initial measurement.
- The existing governor cancellation reaches the adapter. Captured player/command/generation/selection
  and exact link eligibility are rechecked before any deferred transfer. Calls/permission revocation refuse it.
- Logs add `state`, `targetRouteAlias`, `selectedRouteAliases` and counts. Aliases are bounded,
  helper-local and temporary; they are not the manual lab's aliases and carry no names/addresses.

## Regression and verification

The three initial regressions fail against unchanged `f437c61b`: stale selected-route state, temporarily
absent remembered route, and repeated measured-headset callbacks overwriting the mapping with the car.
Tests were added before implementation; the red run failed all three.

Debug helper tests cover those orders, one transfer after duplicates/failure, original deadline,
replacement link, Pause/teardown cancellation, newer context, calls, permission loss, return before
transfer, delayed discovery, a different headset and privacy-safe diagnostic aliases. Main-source service
tests exercise actual governor Pause/output cancellation and the captured deferred authority. Window
tests verify independent inference allowance, original expiry and replacement-link refusal.

Final local verification and exact-head CI are recorded on PR #307. Hardware acceptance of this correction
remains pending; existing tests inject Android route callbacks and cannot establish acoustic exclusivity.

Local verification on 2026-11-17: `ktlintFormat` passed separately, followed by
`verifyDebug :playback:testReleaseUnitTest -Pshelfplayer.warningsAsErrors=true` PASS. Playback ran
1,299 debug and 1,269 release tests with zero failures/errors/skips; lint, detekt, coverage, debug APK
assembly and release/benchmark compilation passed. There are 17 added regression cases. The diagnostic
export test also proves the new aliases survive the Audio Route Lab allow-list without private fields.

## Parked acceptance

Clear diagnostics, start one headset, and repeat arrival without controls. `AlreadySelected state=Waiting`
must keep the same target alias; if a later callback selects another route, expect at most one `Requested`.
Original headset proof remains required before unmute. Repeat with listener Pause or dashboard output
selection while waiting; no later automatic transfer may reverse that intent. Departure and a call must
retain their existing behavior. Record silence and any car audio separately; system callbacks are not
acoustic measurements. A permanently stale vendor selection may still prevent transfer: this change
does not force a selected route through a different output or guarantee uninterrupted playback.

## Build 2306 follow-up

The car's hands-free link connected about seven seconds before Android removed the playing headset.
The same headset reappeared about 0.7 seconds after the pause, and the playing AudioTrack verified it
about 0.9 seconds after the pause.

Compare build 2306 with the early-preference debug build during ordinary parked car startup. Keep
the same headset, focus mode, **Lock headset while playing** and automatic system take-back setting
for both builds. Start with playback audible in the headset and the car disconnected. Repeat each
run and record any audible gap or output change. The new build should show one
`early-headset-preference` event after `car-bluetooth-window` and before projection, while the original
headset is still playing. This event records dispatch to Media3. Compare the subsequent track and
Bluetooth evidence to see whether Android held the route.

Also connect the car while playback is paused and disconnect while playing in the car. Expect no
early refresh from paused playback and no automatic fallback after losing the playing Car route.
An identical preference may be coalesced by Android, so the comparison must measure the heard gap.
