# BookWave design previews

Use the debug previews to iterate on Home, the compact player, Now Playing and the Appearance selector with production composables and a fictional library. This supports SET-002, LIB-002 and PLAY-001 and the design work in [#194](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/194) and [#195](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/issues/195).

## Select a design

In Settings → Appearance → Design, choose **Classic** for the existing look, or **Minimal** for square surfaces, an opaque background, a leading Resume action and smaller player artwork. Classic is the default for existing installs and stored keys this app version does not recognize. The selection is saved on the device and can be changed while listening.

The Theme setting still controls brightness, and accent and contrast remain available. Minimal uses a flat background and hides the glass controls. Choosing Classic brings back the saved glass and background options.

Add a stable key to `AppDesign` and localized labels for a new design. Map the key to its Compose style in `BookWaveAppearance.kt`, then add palettes and shapes in `:core:designsystem`. Use the root theme to apply the style without rebuilding navigation or the player.

## Edit and compare

- Change the shared shelf and player dimensions in [`BookWaveLayout.kt`](../../core/designsystem/src/main/kotlin/com/example/shelfplayer/core/designsystem/layout/BookWaveLayout.kt). The palettes and typography are in the design system's `theme` directory. Glass styles are in `app/src/main/kotlin/com/example/shelfplayer/ui/glass/`.
- Open [`DesignPreviews.kt`](../../app/src/debug/kotlin/com/example/shelfplayer/design/DesignPreviews.kt) while the `debug` variant is selected in Android Studio. Compare Classic and Minimal in light and dark mode and with larger text. Interactive mode lets you open Now Playing from the compact player and try play/pause and seeking against sample state.
- Run on an Android device or emulator to review insets, scrolling, and the device's rendering of blur. Android Studio's Run Preview uses the device's current display and accessibility settings. Check signed-in and offline behavior separately through the installed app. See [Compose previews](https://developer.android.com/develop/ui/compose/tooling/previews).

## Capture through ADB

Install the branch's debug APK on a test device or emulator. The launch command restarts the debug app so it loads the requested preview. With one authorized device connected, run the following commands from the repository root. Use `adb -s SERIAL` if more than one device is connected.

```powershell
$previewActivity = 'org.homebord.bookwave.debug/androidx.compose.ui.tooling.PreviewActivity'
$previewFile = 'com.example.shelfplayer.design.DesignPreviewsKt'
adb shell am start -S -W -n $previewActivity --es composable "$previewFile.HomeDesignPreview"
```

Once the screen is settled, capture it. Change the composable name and output filename for the other previews. The entry points are `HomeDesignPreview`, `PlayerDesignPreview`, `MinimalHomeDesignPreview`, `MinimalPlayerDesignPreview` and `AppearanceDesignPreview`. The command uses `cmd.exe` to preserve binary PNG bytes on older PowerShell versions.

```powershell
cmd /c 'adb exec-out screencap -p > bookwave-home.png'
```

Use fixture screenshots for shared design references. For a live-library capture, review visible book metadata, profiles, device names, and status-bar content before sharing.

## API 36 emulator captures

- [Appearance with the Design selector open](design-screenshots/design-selector.png)
- [Minimal Home in light mode](design-screenshots/minimal-home.png)
- [Minimal Now Playing in dark mode at 150% text size](design-screenshots/minimal-player.png)
