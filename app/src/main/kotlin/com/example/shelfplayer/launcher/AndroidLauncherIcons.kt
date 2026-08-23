package com.example.shelfplayer.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SET-003 — the enabled alias, read from and written to `PackageManager`.
 *
 * ### There is no second source of truth
 *
 * The choice is not stored in DataStore. Android already persists a component's enabled state across
 * restarts and app updates, and that state is what the launcher actually reads — so a stored preference
 * could only ever agree with it or be wrong about it. [current] asks the package manager.
 *
 * ### `DONT_KILL_APP` is load-bearing
 *
 * Without it, changing a component's enabled state restarts the application, which for this app means
 * killing whatever is playing (product priority 1). With it, the process is left alone and the launcher
 * picks the change up from the broadcast the system sends. The visible cost is on the home screen, not in
 * the audio: some launchers need a moment, and a shortcut placed by hand may have to be placed again —
 * which is what the setting's hint says.
 */
@Singleton
class AndroidLauncherIcons @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
) : LauncherIcons {

    private val packages: PackageManager get() = context.packageManager

    override fun current(): LauncherIcon {
        // The BookWave mark replaced the original manifest default without introducing a second enabled
        // component. If the old component was explicitly enabled, however, that means the listener had
        // deliberately selected Indigo. Migrate that one-time state to Indigo's new alias. A new install
        // has DEFAULT here; after any new picker choice IndigoClassicAlias is explicitly DISABLED/ENABLED,
        // so this condition cannot fire again on later app updates.
        if (
            stateOf(LauncherIcon.BookWave) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
            stateOf(LauncherIcon.Indigo) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        ) {
            setEnabled(LauncherIcon.Indigo, isEnabled = true)
            LauncherIcon.entries
                .filter { icon -> icon != LauncherIcon.Indigo }
                .forEach { icon -> setEnabled(icon, isEnabled = false) }
            return LauncherIcon.Indigo
        }

        // An explicitly enabled alias records an existing user's choice. It must win over a newly added
        // manifest default during an upgrade; otherwise Android would expose both aliases in the drawer.
        val explicit = LauncherIcon.entries.firstOrNull { icon ->
            stateOf(icon) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (explicit != null) {
            LauncherIcon.entries
                .filter { icon -> icon != explicit && isEnabled(icon) }
                .forEach { icon -> setEnabled(icon, isEnabled = false) }
            return explicit
        }

        // `DEFAULT` means the manifest default only while PackageManager has no explicit override. A
        // crashed or externally corrupted state can explicitly disable every alias; returning BookWave
        // without repairing that state would make `apply(BookWave)` no-op and leave the app absent from
        // the drawer. Reading the source of truth also restores its minimum valid state.
        if (!isEnabled(LauncherIcon.Default)) {
            setEnabled(LauncherIcon.Default, isEnabled = true)
        }
        return LauncherIcon.Default
    }

    /**
     * Whether this alias is the one a launcher would draw.
     *
     * `DEFAULT` means "whatever the manifest said", which is `enabled="true"` for exactly one alias — so
     * a device where nothing has been chosen yet answers with the default icon rather than with nothing.
     */
    private fun stateOf(icon: LauncherIcon): Int = packages.getComponentEnabledSetting(componentOf(icon))

    private fun isEnabled(icon: LauncherIcon): Boolean = when (stateOf(icon)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LauncherIcon.Default
        else -> false
    }

    override fun apply(icon: LauncherIcon) {
        if (current() == icon) return

        // Enable before disabling. In between the two calls the enabled set is briefly *two* aliases,
        // which every launcher copes with; the other order would briefly leave zero, and a launcher that
        // samples the app at that moment removes it from the drawer.
        setEnabled(icon, isEnabled = true)
        LauncherIcon.entries.filter { it != icon }.forEach { other -> setEnabled(other, isEnabled = false) }

        // The name of a picture, which is neither private nor identifying (PRODUCT_SPEC 14.5).
        logger.info(LogCategory.Settings, "The launcher icon was changed", LogField.Public("icon", icon.name))
    }

    private fun setEnabled(icon: LauncherIcon, isEnabled: Boolean) {
        packages.setComponentEnabledSetting(
            componentOf(icon),
            if (isEnabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * The alias's component: this installation's package, and the alias's own class name.
     *
     * The two halves come from different places on purpose. The package is `context.packageName`, so a
     * debug build addresses its own aliases rather than a release install's; the class name comes from the
     * namespace, because that is what a leading `.` in the manifest resolves against. See
     * [LauncherIcon.component].
     */
    private fun componentOf(icon: LauncherIcon) = ComponentName(context.packageName, icon.component)
}
