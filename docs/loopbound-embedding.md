# Loopbound inside BookWave

**Status:** Integration contract for the optional embedded game.

BookWave can host the production web build of the private Loopbound repository as a local-only screen. The two projects remain separate products and repositories: BookWave owns the Android shell and audiobook playback; Loopbound owns its game engine, content, UI, save schema and migrations.

## User experience

Home's overflow menu contains **Profiles**, **Downloads**, **Loopbound** and **Settings**. Opening Loopbound pushes an ordinary BookWave navigation destination. The audiobook `MediaLibraryService` remains the only playback owner, and BookWave's mini player stays above the navigation graph, so listening can continue while the game is open.

The Loopbound page receives bottom padding from the same mini-player inset as the rest of BookWave. If a BookWave build does not contain a Loopbound bundle, the route remains valid and shows a clear "not included in this build" state rather than failing to load a WebView.

## Security boundary

Loopbound is loaded from APK assets through BookWave's local-only `WebViewClient` asset responder at the fixed origin:

```text
https://appassets.androidplatform.net/assets/loopbound/index.html
```

The host enables JavaScript and DOM storage because Loopbound requires them, but disables file/content access, mixed content, multiple windows and third-party cookies. The responder accepts only that exact HTTPS host and the `/assets/loopbound/` path, rejects traversal segments, serves matching files directly from the APK, returns local error responses for everything else, and blocks navigation outside the Loopbound asset path.

There is deliberately no JavaScript bridge into BookWave playback in this slice. The embedded game cannot read Audiobookshelf credentials or take ownership of the Media3 session.

BookWave's repository remains public while Loopbound's source repository is private. The generated game bundle is never committed to BookWave, but a distributed APK necessarily contains the compiled JavaScript/assets and those bytes can be inspected by somebody who has the APK. Repository privacy therefore protects Loopbound's source repository/history; it is not DRM for the shipped game bundle.

## Where the game bundle lives

The generated bundle is copied to:

```text
app/src/main/assets/loopbound/
```

That directory is **gitignored** because BookWave is public and Loopbound is private. Neither Loopbound source nor its compiled JavaScript/content bundle is committed to the BookWave repository.

A developer who has both repositories locally can populate the directory with:

```powershell
.\scripts\bundle-loopbound.ps1
```

The script assumes Loopbound is a sibling directory by default. A different checkout can be supplied explicitly:

```powershell
.\scripts\bundle-loopbound.ps1 -LoopboundRoot C:\Development\Loopbound
```

Before copying anything, the script runs Loopbound's clean install, content validation, tests and production build. It then copies `dist/` and writes `BOOKWAVE_LOOPBOUND_VERSION` containing the exact Loopbound Git commit bundled into that BookWave build.

## Manual GitHub APK builds

The manually dispatched **Build APK** workflow can also embed Loopbound without committing private game files into BookWave.

The workflow exposes:

- `include_loopbound` — enabled by default for device-test APKs;
- `loopbound_ref` — the Loopbound branch, tag or commit to resolve, defaulting to `main`.

Embedding requires the BookWave repository secret `LOOPBOUND_READ_TOKEN`. It must be a **fine-grained personal access token restricted to `AlexanderWilhelmsenBerg/Loopbound` with only `Contents: Read`**. Do not give it write, administration or BookWave permissions, and never paste its value into repository files, build logs or chat.

The private build and BookWave build are deliberately separate jobs:

1. The Loopbound job checks out only the requested private Loopbound ref using the read-only token with checkout credential persistence disabled.
2. It uses Node 22, runs `npm ci`, content validation, the Vitest suite and the production build.
3. It resolves the exact Loopbound commit, writes that SHA to `dist/BOOKWAVE_LOOPBOUND_VERSION`, and uploads only `dist/` as a one-day workflow artifact.
4. The BookWave APK job independently checks out the selected BookWave commit/PR. It never receives the Loopbound repository token or private source checkout.
5. The APK job downloads only the compiled bundle into `app/src/main/assets/loopbound/`, verifies its version marker against the first job's resolved SHA, and then performs the normal BookWave verification/signing/assembly flow.
6. The workflow summary records the exact Loopbound commit included in the APK.

This separation matters because the Build APK workflow may compile a selected BookWave PR. Private Loopbound credentials and source must not be exposed to code from that selected BookWave revision merely because both are needed to produce one final APK.

When `include_loopbound` is disabled, the workflow requires no Loopbound credential and preserves the public/no-game build path. Normal push and pull-request CI also remain independent of the private repository.

## How Loopbound updates in BookWave

**The installed game does not update itself over the network.** A BookWave APK/AAB contains an immutable snapshot of Loopbound. This is intentional: a BookWave version should not silently change behaviour because the private Loopbound `main` branch changed later.

For a local build:

1. Update and review Loopbound in its own repository.
2. Check out the exact Loopbound commit intended for BookWave.
3. Run `scripts/bundle-loopbound.ps1` from BookWave. The script validates/tests/builds Loopbound and replaces the local ignored asset directory.
4. Build and test BookWave normally.
5. Ship a new BookWave APK/AAB. That release now contains the new Loopbound snapshot.

For a manually dispatched GitHub APK, select the desired `loopbound_ref`; the workflow performs the same validate/test/build/stage process and records the exact resolved commit automatically.

The version marker makes a built APK traceable to a precise game commit even though the generated bundle itself is not tracked by BookWave Git.

### Why updates are coupled to a BookWave release

Serving Loopbound from a remote URL would allow instant game updates, but it would also make the game dependent on connectivity and let behaviour change independently of the BookWave version that was tested. Bundling keeps Loopbound offline-capable, reproducible and subject to the same release gate as the Android host.

## Saves across game updates

Inside BookWave, Loopbound is not running in Capacitor, so its existing save abstraction selects IndexedDB. The fixed `appassets.androidplatform.net` origin remains the same between BookWave releases, allowing the WebView's origin storage to survive an ordinary app update. Loopbound remains responsible for versioned serialization and save migrations.

Uninstalling BookWave or clearing its app data can remove the embedded WebView's IndexedDB. Loopbound's existing export/import remains the portable backup path. Moving the save into a native BookWave-owned store would require a separate, deliberately versioned bridge and is not part of this integration slice.

## Verification

With no local Loopbound bundle:

```powershell
.\gradlew.bat verifyDebug -Pshelfplayer.warningsAsErrors=true
```

BookWave must build normally and the Loopbound route must show the fallback state.

With both repositories available locally:

```powershell
.\scripts\bundle-loopbound.ps1
.\gradlew.bat verifyDebug -Pshelfplayer.warningsAsErrors=true
```

For GitHub device builds, run **Build APK** from a workflow revision containing the private-bundle job, keep `include_loopbound` enabled, and confirm the run summary identifies the intended Loopbound commit before installing the APK.

Device acceptance should verify:

- Home's overflow menu exposes Profiles, Downloads, Loopbound and Settings;
- each menu item reaches the expected destination;
- Loopbound renders from local assets with the device offline;
- an audiobook keeps playing while Loopbound is open;
- the mini player remains usable and does not cover the game;
- leaving and reopening Loopbound preserves game progress;
- killing and reopening BookWave preserves game progress;
- installing a BookWave build with a newer Loopbound snapshot preserves and migrates the existing save;
- external navigation/resource requests from the game are not loaded by the embedded WebView.
