package com.example.shelfplayer.feature.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import com.example.shelfplayer.core.model.lock.RelockDelay
import com.example.shelfplayer.feature.settings.ChoiceRow
import com.example.shelfplayer.feature.settings.Hint
import com.example.shelfplayer.feature.settings.SubHeader
import com.example.shelfplayer.feature.settings.SwitchRow

/** So a test can find the controls without depending on their labels. */
internal const val SETTINGS_PASSCODE_SWITCH = "settings-passcode-switch"
internal const val SETTINGS_PASSCODE_NEW = "settings-passcode-new"

/**
 * AUTH-005 / 3.3 — the passcode controls, under **Profiles** where 3.3 lists them.
 *
 * ### The warning comes before the switch, not after
 *
 * A forgotten passcode is cleared by signing in to the account again, and that needs the server to be
 * reachable. Somebody deciding whether to turn this on has to know that *before* they turn it on, so the
 * hint sits above the switch rather than being discovered at the moment it matters. Product priority 5
 * asks the same of destructive actions; a lock that can strand you offline earns the same treatment.
 *
 * ### The biometric row is disabled, never hidden
 *
 * A hidden row is indistinguishable from a feature nobody built, and was reported as exactly that during
 * Phase 5. Each unavailable reason gets its own sentence, so "no fingerprint is set up" and "this Android
 * version cannot" are not collapsed into one shrug.
 */
internal fun LazyListScope.profileLockSection(
    state: LockSettingsUiState,
    preferences: LockPreferencesUi,
    actions: ProfileLockActions,
    message: LockSettingsMessage? = null,
) {
    item { SubHeader(text = stringResource(R.string.settings_section_passcode)) }
    item { Hint(text = stringResource(R.string.settings_passcode_hint)) }
    item { Hint(text = stringResource(R.string.settings_passcode_forgot_warning)) }

    item {
        PasscodeControls(
            hasPasscode = state.hasPasscode,
            isEnabled = state.profileId != null,
            actions = actions,
        )
    }

    // PRODUCT_SPEC 21 — the outcome of a write the user just asked for.
    //
    // `ProfileLockViewModel` produced this from the first version and nothing rendered it, so three strings
    // sat unused and a rejected passcode was indistinguishable from an accepted one. Android lint's
    // `UnusedResources` is what noticed, which is the second time in this project a written-but-unrendered
    // string has been the visible end of a missing piece of feedback.
    message?.let { outcome ->
        item {
            Text(
                text = stringResource(outcome.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (outcome == LockSettingsMessage.Saved) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (!state.hasPasscode) return

    item {
        BiometricRow(
            availability = state.biometrics,
            enabled = preferences.biometricUnlock,
            onToggled = actions.onBiometricToggled,
        )
    }
    item { SubHeader(text = stringResource(R.string.settings_passcode_relock)) }
    item {
        ChoiceRow(
            options = RelockDelay.entries,
            selected = preferences.relockDelay,
            label = { delay -> stringResource(delay.labelRes()) },
            onSelected = actions.onRelockDelayChanged,
        )
    }
    item {
        TextButton(
            onClick = actions.onLockNow,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(text = stringResource(R.string.settings_passcode_lock_now))
        }
    }
}

/**
 * The switch, and the fields it reveals.
 *
 * Turning it **on** asks for a new passcode. Turning it **off** asks for the current one — the same proof
 * changing it requires, and for the same reason: without it, an unlocked phone on a desk is a lock somebody
 * else can remove.
 */
@Composable
private fun PasscodeControls(hasPasscode: Boolean, isEnabled: Boolean, actions: ProfileLockActions) {
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var newPasscode by rememberSaveable { mutableStateOf("") }
    var currentPasscode by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            label = stringResource(R.string.settings_passcode_switch),
            checked = hasPasscode,
            onCheckedChange = { wanted ->
                // The switch does not itself change anything: both directions need a value typed first.
                // Moving the switch and then failing validation would leave the control disagreeing with
                // the stored state, which is the shape of bug this app has already fixed twice.
                isEditing = true
                newPasscode = ""
                currentPasscode = ""
                if (!wanted && !hasPasscode) isEditing = false
            },
            modifier = Modifier.testTag(SETTINGS_PASSCODE_SWITCH),
        )

        if (!isEditing || !isEnabled) return@Column

        if (hasPasscode) {
            PasscodeField(
                label = stringResource(R.string.settings_passcode_current),
                value = currentPasscode,
                onValueChange = { currentPasscode = it },
            )
        }
        PasscodeField(
            label = stringResource(R.string.settings_passcode_new),
            value = newPasscode,
            onValueChange = { newPasscode = it },
            testTag = SETTINGS_PASSCODE_NEW,
        )
        TextButton(
            onClick = {
                actions.onPasscodeSet(
                    newPasscode.toCharArray(),
                    currentPasscode.takeIf { hasPasscode }?.toCharArray(),
                )
                newPasscode = ""
                currentPasscode = ""
                isEditing = false
            },
            enabled = newPasscode.isNotEmpty() && (!hasPasscode || currentPasscode.isNotEmpty()),
        ) {
            Text(text = stringResource(R.string.settings_passcode_save))
        }
        if (hasPasscode) {
            TextButton(
                onClick = {
                    actions.onPasscodeRemoved(currentPasscode.toCharArray())
                    currentPasscode = ""
                    newPasscode = ""
                    isEditing = false
                },
                enabled = currentPasscode.isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.settings_passcode_turn_off))
            }
        }
    }
}

@Composable
private fun PasscodeField(label: String, value: String, onValueChange: (String) -> Unit, testTag: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(next.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .let { base -> if (testTag != null) base.testTag(testTag) else base },
    )
}

/**
 * AUTH-005 — "or biometric", offered where it can work and explained where it cannot.
 */
@Composable
private fun BiometricRow(availability: BiometricAvailability, enabled: Boolean, onToggled: (Boolean) -> Unit) {
    if (availability == BiometricAvailability.Available) {
        SwitchRow(
            label = stringResource(R.string.settings_passcode_biometric),
            checked = enabled,
            onCheckedChange = onToggled,
        )
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.settings_passcode_biometric),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(availability.reasonRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Each outcome says which one it was, because "it did not work" is not an answer somebody can act on. */
private fun LockSettingsMessage.messageRes(): Int = when (this) {
    LockSettingsMessage.Saved -> R.string.settings_passcode_saved
    LockSettingsMessage.Invalid -> R.string.settings_passcode_length_error
    LockSettingsMessage.WrongCurrent -> R.string.settings_passcode_wrong_current
    LockSettingsMessage.Failed -> R.string.settings_passcode_failed
}

private fun RelockDelay.labelRes(): Int = when (this) {
    RelockDelay.Immediately -> R.string.settings_passcode_relock_immediately
    RelockDelay.AfterOneMinute -> R.string.settings_passcode_relock_one_minute
    RelockDelay.AfterFifteenMinutes -> R.string.settings_passcode_relock_fifteen_minutes
}

/** Each unavailable case has its own sentence, because each has a different thing the user could do. */
private fun BiometricAvailability.reasonRes(): Int = when (this) {
    BiometricAvailability.Available -> R.string.settings_passcode_biometric
    BiometricAvailability.UnsupportedAndroidVersion -> R.string.settings_passcode_biometric_unsupported
    BiometricAvailability.NoHardware -> R.string.settings_passcode_biometric_no_hardware
    BiometricAvailability.NoneEnrolled -> R.string.settings_passcode_biometric_none_enrolled
}

/**
 * What the section can do, as one bundle.
 *
 * A `data class` so detekt's parameter rule exempts it, and one bundle rather than five lambdas because
 * they always travel together — a section that could set a passcode but not remove one would be a trap.
 */
data class ProfileLockActions(
    val onPasscodeSet: (CharArray, CharArray?) -> Unit = { _, _ -> },
    val onPasscodeRemoved: (CharArray) -> Unit = {},
    val onBiometricToggled: (Boolean) -> Unit = {},
    val onRelockDelayChanged: (RelockDelay) -> Unit = {},
    val onLockNow: () -> Unit = {},
)
