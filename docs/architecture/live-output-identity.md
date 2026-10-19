# Collision-safe live output identity — ROUTE-002 / PLAY-002

The persisted BookWave device policy keys are currently derived from audio transport
and the advertised name. That remains backward-compatible here. A name is not a
unique physical Bluetooth identity, especially when two identical earbuds/headsets
are connected at once.

This foundation introduces shared, permission-free, process-local collision evidence.
It is consumed by both AudioOutputRouter and OutputDeviceWatcher:

- A uniquely resolved logical output retains its existing chooser and saved policy.
- Simultaneous distinct Bluetooth/hearing-aid/other handles sharing one logical key
  are ambiguous. Do not let a map's first value silently win.
- Reject explicit selection while ambiguous; preserve a previously chosen destination
  and pause it rather than clearing selection to Automatic.
- Do not treat a matching *logical* predicted media route as proof of the physical
  endpoint when an identity collision exists. Guarded playback stays fail-closed.
- Suppress automatic connection actions (especially AutoPlay) while a logical key
  is shared; do not erase debounce state when one same-name peer disconnects.
- Wired, car and speaker identifiers remain intentional device *categories*.
- Platform handle IDs are ephemeral and cannot safely become persisted policy IDs.
  No Bluetooth addresses, device names or hardware identifiers are added to reports.

This does NOT solve physical A/B button attribution, persistent same-name device
identity across reconnect, headset lock, or controlled handoff. Those require
separate user-visible decisions, optional platform permissions where supported,
and stationary hardware acceptance. No inference may trigger a route switch.

Test: two live identical-name Bluetooth handles; unique alternative handles;
duplicate callback rows; one peer disappearing; legacy wired category; unknown
platform route. Existing PR #271 diagnostics can record collision observations.

When PR #271 merges, rebase this stacked PR onto main and require exact-head
verifyDebug, route continuity regression checks, and physical A/B tests before
claiming playback routing accepted.
