# Headset button routing during Android Auto

Test while parked. Record the APK version and start with a clear diagnostics export. The following are
hardware acceptance cases for PLAY-002 and ROUTE-001.

1. Leave Sony Sound Connect enabled. Use its controls before and after testing, including with direct Sony
   tap detection enabled in BookWave. Sound Connect must remain usable throughout.
2. Start playing on one headset, then connect Android Auto. Check audibility and progress. Pause and Play
   on the headset, then on the dashboard. The dashboard Play should move audio to Car. Pause and Play on
   the headset again. A recognized button path and one eligible headset should move audio back. Confirm
   the switch by listening and compare the export's media-button evidence with its actual output trace.
3. Repeat with two headsets connected for media. An unidentifiable press must keep the current output.
   Use the dashboard icons to select either destination. Lock one output and repeat both kinds of Play.
4. Check phone-screen Play, a generic media command, double tap/skip, and Auto connect while paused. None
   should select a headset by proximity or start a paused book merely because a route appeared.
5. Disable headset-button routing and repeat. Dashboard commands and explicit icons should still work.
   If Android sends a steering-wheel control via Bluetooth, compare with routing enabled. Its generic key
   can share the headset command path. Record the actual sender separately from BookWave's inference.
6. Only if Sound Connect is already absent or disabled, optionally enable direct Sony tap detection with
   Nearby devices access. With a WF-1000XM6 and another headset, compare a right Play tap, phone Play and
   the other headset's Play. Only the Sony tap paired with a matching media key can identify the Sony.

Check for a single playback start, stable narration and position, retained focus policy, and silence on an
unverified destination. A diagnostic route acceptance is not proof of audible output.
