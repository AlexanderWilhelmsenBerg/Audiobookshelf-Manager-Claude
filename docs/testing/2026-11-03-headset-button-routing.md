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

## Locking the headset and following Android on Play

On Android 13 or later, start BookWave on headset A with **Lock headset while playing** enabled.
Connect B and check that narration remains on A or pauses if A can no longer be verified. Pause,
select B in Android's media output picker if Android has not already selected it, then use Play on B.
Check that BookWave resumes on B only when the actual track verifies the target. Repeat with an
explicit BookWave output lock, and with both headsets shown as active in system policy.

In **Settings → Playback → Audio focus**, choose **Ignore focus loss**, pause, and start a
book on A. Connect Android Auto and compare the raw focus callback with the selected route and actual
AudioTrack progress. Repeat with **Pause**, check an incoming call and a denied focus request, then
verify that Pause still releases focus. Retest at least one run from cold with a headset Play command.
