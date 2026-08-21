package com.example.shelfplayer.feature.lock

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import com.example.shelfplayer.core.model.lock.UnlockFailure

/** So a test can find the field and the confirm control without depending on their labels. */
internal const val LOCK_PASSCODE_FIELD = "lock-passcode-field"
internal const val LOCK_SUBMIT = "lock-submit"

/**
 * AUTH-005 — the curtain, drawn *instead of* the app rather than over it.
 *
 * ### Why it replaces the content and does not overlay it
 *
 * Because an overlay leaves the thing it is hiding in the semantics tree. `MiniPlayer` marks its title as
 * a polite live region, so TalkBack would read the locked account's book aloud over a passcode field; its
 * stop button and its tap-to-expand would still be reachable by a screen reader and by a stray touch. A
 * lock that announces what it is protecting is not a lock. So `MainActivity` composes this *or* the app,
 * never both.
 *
 * ### What it does not claim
 *
 * The disclosure block is part of the feature, not an apology for it. AUTH-003 says this protects "profile
 * selection, not server authentication semantics", and the honest consequences of that — the notification
 * still works, a car can still browse, downloaded files are ordinary files — are stated where somebody
 * reads them, not only in an ADR. `LockCurtainScreenTest` asserts each of the four lines is present,
 * because a disclosure that can be silently deleted is not a disclosure.
 */
@Composable
fun LockCurtain(modifier: Modifier = Modifier, viewModel: LockViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val isChecking by viewModel.isChecking.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()

    LockCurtainContent(
        state = state,
        failure = failure,
        isChecking = isChecking,
        isBiometricEnabled = biometricEnabled,
        onSubmit = viewModel::onPasscodeSubmitted,
        onBiometricAccepted = viewModel::onBiometricAccepted,
        modifier = modifier,
    )
}

/**
 * The curtain without its ViewModel, so `LockCurtainScreenTest` can render it.
 *
 * The split is not ceremony. The disclosure block is the part of this feature that has to be *enforced*
 * rather than trusted, and a composable that can only be reached through `hiltViewModel()` cannot be
 * rendered by a Robolectric test without standing up a whole graph. Splitting the state out makes the
 * promise checkable, which is the difference between a comment and a guarantee.
 */
@Composable
internal fun LockCurtainContent(
    state: LockUiState,
    failure: UnlockFailure?,
    isChecking: Boolean,
    isBiometricEnabled: Boolean,
    onSubmit: (CharArray) -> Unit,
    onBiometricAccepted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.locked ?: return

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = state.account?.displayName ?: stringResource(R.string.lock_account_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (locked.canUsePasscode) {
                    PasscodeField(isChecking = isChecking, onSubmit = onSubmit)
                } else {
                    // A field that will refuse every value, including the right one, is worse than no
                    // field. `unreadable` and `exhausted` both land here and both say what to do instead.
                    Text(
                        text = stringResource(
                            if (locked.unreadable) R.string.lock_unreadable else R.string.lock_exhausted,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                failure?.let { reason -> FailureText(reason) }

                if (isBiometricEnabled && state.biometrics == BiometricAvailability.Available) {
                    BiometricButton(onAccepted = onBiometricAccepted)
                }

                RecoveryBlock(hasOtherAccounts = state.others.isNotEmpty())
                DisclosureBlock()
            }
        }
    }
}

@Composable
private fun PasscodeField(isChecking: Boolean, onSubmit: (CharArray) -> Unit) {
    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        value = typed,
        onValueChange = { next -> typed = next.filter(Char::isDigit) },
        label = { Text(stringResource(R.string.lock_passcode_label)) },
        singleLine = true,
        enabled = !isChecking,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LOCK_PASSCODE_FIELD),
    )
    Button(
        onClick = {
            // Handed over as an array and cleared by the ViewModel. The `String` behind the field cannot
            // be wiped, which is a limitation of `OutlinedTextField` rather than a choice, and is the
            // reason the array conversion happens as late as possible and no earlier copy is kept.
            onSubmit(typed.toCharArray())
            typed = ""
        },
        enabled = !isChecking && typed.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LOCK_SUBMIT),
    ) {
        Text(stringResource(R.string.lock_unlock))
    }
}

/**
 * What went wrong, without ever naming what was typed.
 *
 * Each case says something different because each has a different next step: try again, wait, or stop
 * trying and re-authenticate. "Wrong passcode" for an unreadable record would send somebody to keep
 * guessing at something that cannot succeed.
 */
@Composable
private fun FailureText(failure: UnlockFailure) {
    val text = when (failure) {
        is UnlockFailure.Wrong -> if (failure.remainingBeforeBackoff > 0) {
            stringResource(R.string.lock_wrong_with_remaining, failure.remainingBeforeBackoff)
        } else {
            stringResource(R.string.lock_wrong)
        }

        is UnlockFailure.BackingOff ->
            stringResource(R.string.lock_backing_off, failure.remaining.inWholeSeconds)

        UnlockFailure.Exhausted -> stringResource(R.string.lock_exhausted)
        UnlockFailure.Unreadable -> stringResource(R.string.lock_unreadable)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun BiometricButton(onAccepted: () -> Unit) {
    val context = LocalContext.current
    val gateway = remember { PlatformBiometricGateway(context.applicationContext) }
    TextButton(
        onClick = {
            // The prompt needs an Activity, not any Context: it is a window the system draws over this
            // one. A non-Activity context here would throw at the moment of use rather than at build time.
            (context as? Activity)?.let { activity ->
                gateway.authenticate(activity, onSuccess = onAccepted, onFailed = {})
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.lock_use_biometrics))
    }
}

/**
 * AUTH-005 — what to do when the passcode is gone.
 *
 * Three routes, in order of how much they cost. Signing in again is the primary one and is a *feature*
 * rather than a bypass: somebody holding the account password has cleared a strictly higher bar than a
 * six-digit local code, and AUTH-003 says this lock is not about server authentication in the first place.
 *
 * The offline caveat is stated because it is real: that route needs the server, and a forgotten passcode on
 * a train is not recoverable by it.
 */
@Composable
private fun RecoveryBlock(hasOtherAccounts: Boolean) {
    Text(
        text = stringResource(R.string.lock_forgot_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.lock_forgot_signin),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.lock_forgot_offline),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (hasOtherAccounts) {
        Text(
            text = stringResource(R.string.lock_forgot_other_accounts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * PRODUCT_SPEC AUTH-003 / 14.5 — the four things this lock does not cover, said out loud.
 *
 * Every line here is a bypass that exists and is not closed, disclosed rather than hidden. The media
 * notification and the lock-screen transport keep working because there is no interception point for a
 * media button in this app and ROUTE-001 treats one as explicit intent; a car keeps browsing because
 * blanking a head unit mid-drive to hide the active profile's own content from a physically present person
 * is the worse trade; downloaded audio is ordinary files.
 */
@Composable
private fun DisclosureBlock() {
    Text(
        text = stringResource(R.string.lock_not_covered_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    listOf(
        R.string.lock_not_covered_playback,
        R.string.lock_not_covered_car,
        R.string.lock_not_covered_downloads,
        R.string.lock_not_covered_files,
    ).forEach { line ->
        Text(
            text = stringResource(line),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { },
        )
    }
}

/** Wide enough to read, narrow enough not to stretch a passcode field across a tablet. */
private val CONTENT_MAX_WIDTH = 420.dp
