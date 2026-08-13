package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.CarReadiness

/**
 * PRODUCT_SPEC PLAY-001 / ROUTE-002 — why the app is, or is not, in the car's app list.
 *
 * ### Why a screen, rather than another attempt
 *
 * Two device runs ended with *"the app doesn't show among the apps"*. The first had a cause inside the APK —
 * the missing `automotive_app_desc` metadata — and it was fixed. The second one did not: the metadata is in
 * the shipped binary. Everything left is on the phone, and none of it can be seen from a car seat.
 *
 * So these five rows, in the order they rule things out. The first two are what the build controls, and if
 * either is *no* the app is at fault. The last three are the phone, and the one that most often explains it
 * is **Installed by**: Android Auto will not list a media app it did not get from Play unless *Unknown
 * sources* is on, and unlocking developer settings does not turn that on by itself.
 *
 * **Last car connection** is the row that closes the question. If it still says *never* after a drive, no car
 * ever bound to this app's session — so the browse tree, the tabs and the auto-play setting are all
 * irrelevant, and the problem is discovery. If it says a time, the app was reached and anything missing after
 * that is ours.
 */
internal fun LazyListScope.carRows(readiness: CarReadiness) {
    item { SubHeader(text = stringResource(R.string.settings_section_car_check)) }
    item { Hint(text = stringResource(R.string.settings_car_body)) }
    item {
        YesNoRow(
            labelRes = R.string.settings_car_declared,
            value = readiness.isDeclared,
            hintRes = R.string.settings_car_declared_hint,
        )
    }
    item {
        YesNoRow(
            labelRes = R.string.settings_car_browser,
            value = readiness.hasBrowserService,
            hintRes = R.string.settings_car_browser_hint,
        )
    }
    item {
        YesNoRow(
            labelRes = R.string.settings_car_installed,
            value = readiness.isAndroidAutoInstalled,
            hintRes = R.string.settings_car_installed_hint,
        )
    }
    item {
        TextRow(
            labelRes = R.string.settings_car_installer,
            value = readiness.installer ?: stringResource(R.string.settings_car_installer_sideloaded),
        )
    }
    if (readiness.isSideloaded) {
        item { UnknownSourcesWarning() }
    }
    item {
        TextRow(
            labelRes = R.string.settings_car_last_connection,
            value = readiness.lastConnectedAt?.asClockTime()
                ?: stringResource(R.string.settings_car_last_connection_never),
        )
    }
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/**
 * The instruction that a sideloaded build almost always needs, shown only when it applies.
 *
 * Not a hint under the row: it is the single most likely reason the app is missing from a dashboard, and a
 * grey line under a grey line is a line nobody reads. It is also not shown at all for a Play install, where
 * it would be advice to change a setting that does not matter.
 */
@Composable
private fun UnknownSourcesWarning(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.settings_car_unknown_sources),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
