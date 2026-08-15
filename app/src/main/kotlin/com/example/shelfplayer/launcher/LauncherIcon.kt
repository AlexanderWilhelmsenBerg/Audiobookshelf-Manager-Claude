package com.example.shelfplayer.launcher

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.shelfplayer.R

/**
 * PRODUCT_SPEC SET-003 — the launcher icons a listener can choose between.
 *
 * ### Why an enum and not a list of resource ids
 *
 * Each entry is three things that must agree: a manifest `activity-alias`, the adaptive icon that alias
 * points at, and the swatch the picker draws. Splitting them across three places is how a build ships with
 * a picker showing one icon and a launcher showing another. Here they cannot drift, and
 * `LauncherIconsTest` walks the enum to prove every alias in it actually resolves.
 *
 * ### The preview draws the layers, not the icon
 *
 * [foreground] and [background] are the adaptive icon's own two layers rather than the assembled
 * `@mipmap/ic_launcher_…`. An `AdaptiveIconDrawable` rendered inside a list row is drawn unmasked — a full
 * 108dp square with the artwork floating small in the middle of it — which is not what any launcher shows.
 * Compositing the layers under a circle is what the home screen actually does.
 *
 * @property alias the manifest `activity-alias`'s simple name. Enabling exactly one of these is the whole
 *   mechanism; [component] is the class name it resolves to.
 */
enum class LauncherIcon(
    private val alias: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val foreground: Int,
    @param:ColorRes val background: Int,
) {
    Indigo(
        alias = "IndigoAlias",
        label = R.string.settings_icon_indigo,
        foreground = R.mipmap.ic_launcher_indigo_foreground,
        background = R.color.ic_launcher_background_indigo,
    ),
    Spectrum(
        alias = "SpectrumAlias",
        label = R.string.settings_icon_spectrum,
        foreground = R.mipmap.ic_launcher_spectrum_foreground,
        background = R.color.ic_launcher_background_spectrum,
    ),
    Crimson(
        alias = "CrimsonAlias",
        label = R.string.settings_icon_crimson,
        foreground = R.mipmap.ic_launcher_crimson_foreground,
        background = R.color.ic_launcher_background_crimson,
    ),
    Illuminated(
        alias = "IlluminatedAlias",
        label = R.string.settings_icon_illuminated,
        foreground = R.mipmap.ic_launcher_illuminated_foreground,
        background = R.color.ic_launcher_background_illuminated,
    ),
    Vintage(
        alias = "VintageAlias",
        label = R.string.settings_icon_vintage,
        foreground = R.mipmap.ic_launcher_vintage_foreground,
        background = R.color.ic_launcher_background_vintage,
    ),
    Monochrome(
        alias = "MonochromeAlias",
        label = R.string.settings_icon_monochrome,
        foreground = R.mipmap.ic_launcher_monochrome_foreground,
        background = R.color.ic_launcher_background_monochrome,
    ),
    ;

    /**
     * The alias's fully qualified class name.
     *
     * Built from the module's **namespace**, not from `context.packageName`. An `android:name` beginning
     * with `.` in the manifest is resolved against the namespace, while the installed package can differ
     * from it — the debug build carries an `applicationIdSuffix`, so it is installed as
     * `com.example.shelfplayer.debug` and its aliases are still `com.example.shelfplayer.launcher.*`.
     * Deriving this from the package name instead produced components that resolved on release and threw
     * `NameNotFoundException` on every debug install.
     *
     * A literal rather than a reflected package name, because R8 renames packages in a minified build and
     * would silently point this at nothing. `LauncherIconsTest` resolves all six against the merged
     * manifest, so a namespace change fails the build rather than the app.
     */
    val component: String get() = "$NAMESPACE.launcher.$alias"

    companion object {
        /** Matches `namespace` in `app/build.gradle.kts` — see [component] for why it is spelled out. */
        private const val NAMESPACE = "com.example.shelfplayer"

        /**
         * What a fresh install shows, and what the manifest enables.
         *
         * It must stay in step with the one alias declared `android:enabled="true"`, because that is the
         * icon a device with no stored choice will actually draw. `LauncherIconsTest` asserts the pair.
         */
        val Default: LauncherIcon = Indigo
    }
}

/**
 * PRODUCT_SPEC SET-003 — reading and changing the enabled launcher alias.
 *
 * A seam rather than a `PackageManager` call in the ViewModel, for the reason the settings screen already
 * has two others like it (`NotificationAccessReader`, `CarReadinessReader`): it is a question about the
 * *device's* component state, so there is nothing for a repository to mediate, and a fake makes the picker
 * testable without an installed package.
 */
interface LauncherIcons {

    /** The alias currently enabled, or [LauncherIcon.Default] when nothing has been chosen. */
    fun current(): LauncherIcon

    /**
     * Enables [icon]'s alias and disables the other five.
     *
     * Enabling first, then disabling, deliberately: a moment with no enabled `LAUNCHER` component is a
     * moment in which some launchers drop the app from the drawer and do not put it back.
     */
    fun apply(icon: LauncherIcon)
}
