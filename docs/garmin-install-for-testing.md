# Install the Connect IQ development apps for testing

BookWave has two separate apps: **BookWave Audio** is a music provider that downloads/plays books;
**BookWave Companion** displays phone playback. Installing Companion alone does not enable watch audio.
These are development builds, not Connect IQ Store releases. Physical acceptance is still pending.
Upgrade the phone to the matching BookWave Android APK with device controls before pairing; keep app data.

1. Download the artifacts from the successful [Garmin Verification run](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/workflows/garmin-verification.yml)
   for the source revision recorded in the test report. Choose `audio-provider` and optionally `companion`.
   Match `fenix843mm` to the 43 mm fēnix 8 or `fenix847mm` to the 47 mm model. Do not use either binary on
   a different model. The `.iq` package is for store submission; sideload the target-specific `.prg`.
2. Connect the watch by USB and choose its file-transfer/MTP mode if prompted. Open the watch in Explorer.
   Copy `BookWave-audio-provider-<target>.prg` (and optionally the matching Companion `.prg`) into
   **Internal Storage → GARMIN → APPS**. Safely disconnect it. Let installation finish/restart if prompted.
3. On the watch, select **BookWave Audio** from the music providers. Companion appears separately among
   regular apps. Garmin menus vary with firmware. Keep Garmin Connect Mobile installed and the watch
   paired with the Android phone; it carries the BookWave bridge messages.
4. In BookWave Audio setup, enter the **full HTTPS WatchShelf Sidecar URL** and sign in with the same
   Audiobookshelf account used by the active BookWave phone profile. Login uses Sidecar's opaque session;
   do not paste a normal Audiobookshelf token. Existing WatchShelf Sidecar supplies/transcodes the audio.
5. **Pair before downloading books on the new provider.** In Android BookWave, open
   **Settings → Playback → Devices → Garmin → Pair watch**. Keep the provider open on the watch.
   Compare the code and confirm that Sidecar uses the same server/account on the watch. This is an
   explicit account check: the current Sidecar cannot prove account identity through the Bluetooth bridge.
   A provider that already contains unbound books/jobs/events refuses to relabel them; use a fresh
   BookWave provider installation for first pairing. Existing WatchShelf storage remains separate.
6. Finish one authorized download on the phone. In the expanded watch row choose **New download**,
   then the book. This queues its server item for Sidecar to fetch to the watch; phone audio files are
   not copied. Follow the watch's native Wi-Fi/charging/download prompts. **Queued** is not **Downloaded**.
   A resume download may cache only the remaining audio; the dialog states its saved start position. Check **Downloads**, then test playback with the phone absent and later use **Force sync**.

**Updating development builds:** CI generates a temporary signing key per job/run. Different CI artifacts
are not a stable upgrade identity; do not assume copying a later PRG preserves an installed app's data.
For repeated tests with retained books, build both apps locally using the same retained private developer
key and source revision, then use their target PRGs. Never share/commit the private key. Record the source,
PRG SHA-256, signing-key fingerprint, watch firmware and Android APK before testing. Face-feed consumers also need the same retained developer key (protected complications). The retained account anchor also refuses switching username/server over cached state; sync old progress, then use a fresh provider installation to switch accounts. Uninstalling a provider
can remove its local watch media; sync pending listening progress before doing so.

The face-state publisher is present, but this change builds no watch face. Its cross-app delivery and
battery behavior remain physical tests. See [device tests](garmin-device-management.md) GD-01–10 and
[feed tests](garmin-watchface-state-plan.md) GF-01–07; Companion transport tests remain separate.

Garmin's official [sideloading guide](https://developer.garmin.com/connect-iq/connect-iq-basics/your-first-app/)
explains the PRG/USB installation workflow. The [SDK](https://developer.garmin.com/connect-iq/sdk/) is needed
only for local builds/simulator tests, not copying an already compiled PRG.
