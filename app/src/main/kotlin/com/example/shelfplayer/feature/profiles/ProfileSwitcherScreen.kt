package com.example.shelfplayer.feature.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.feature.lock.FailureText

@Composable
fun ProfileSwitcherRoute(
    onNavigateUp: () -> Unit,
    onAddProfile: () -> Unit,
    onSignInAgain: (serverUrl: String?, username: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileSwitcherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val unlockPrompt by viewModel.unlockPrompt.collectAsStateWithLifecycle()
    ProfileSwitcherScreen(
        uiState = uiState,
        unlockPrompt = unlockPrompt,
        onUnlockSubmitted = viewModel::onUnlockSubmitted,
        onUnlockDismissed = viewModel::onUnlockDismissed,
        actions = ProfileSwitcherActions(
            onProfileSelected = viewModel::onProfileSelected,
            onSignOut = viewModel::onSignOut,
            onSignInAgain = onSignInAgain,
            onRemoveProfile = viewModel::onRemoveProfile,
            onErrorDismissed = viewModel::onErrorDismissed,
            onNavigateUp = onNavigateUp,
            onAddProfile = onAddProfile,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherScreen(
    uiState: ProfileSwitcherUiState,
    actions: ProfileSwitcherActions,
    modifier: Modifier = Modifier,
    unlockPrompt: UnlockPrompt? = null,
    onUnlockSubmitted: (CharArray) -> Unit = {},
    onUnlockDismissed: () -> Unit = {},
) {
    var pendingRemoval by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        if (uiState.hasNoProfiles) {
            ShelfEmptyState(
                title = stringResource(R.string.profiles_empty_title),
                body = stringResource(R.string.profiles_empty_body),
                actionLabel = stringResource(R.string.profiles_add),
                onAction = actions.onAddProfile,
                modifier = content,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = content,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uiState.error?.let { error ->
                item {
                    Text(
                        text = error.summary,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    TextButton(onClick = actions.onErrorDismissed) {
                        Text(text = stringResource(R.string.profiles_dismiss))
                    }
                }
            }

            items(uiState.profiles, key = { it.profile.id.value }) { row ->
                ProfileCard(
                    row = row,
                    isActive = row.profile.id == uiState.activeProfileId,
                    isBusy = uiState.isBusy,
                    onSelected = { actions.onProfileSelected(row.profile.id) },
                    onSignOut = { actions.onSignOut(row.profile.id) },
                    onSignInAgain = { actions.onSignInAgain(row.serverUrl, row.profile.username) },
                    onRemove = { pendingRemoval = row.profile },
                )
            }

            item {
                TextButton(
                    onClick = actions.onAddProfile,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.profiles_add))
                }
            }
        }
    }

    unlockPrompt?.let { prompt ->
        UnlockProfileDialog(
            prompt = prompt,
            username = uiState.profiles.firstOrNull { it.profile.id == prompt.profileId }?.profile?.username,
            onSubmit = onUnlockSubmitted,
            onDismiss = onUnlockDismissed,
        )
    }

    pendingRemoval?.let { profile ->
        RemoveProfileDialog(
            profile = profile,
            onConfirm = {
                actions.onRemoveProfile(profile.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

/**
 * PRODUCT_SPEC AUTH-002 / 6.5 — one saved account.
 *
 * Three things a device run said were unclear, fixed here:
 *
 * - **Which account is in use.** "In use" as plain text between two buttons read as a third, disabled
 *   button. The active card now carries the theme's selected container colour and a filled badge, so the
 *   answer is visible before anything is read.
 * - **How to switch.** The whole card is the target, not a small text button. Tapping an inactive card
 *   switches to it; the active card is not clickable, because switching to what is already active is not
 *   an action.
 * - **What to do with a signed-out profile.** It offered *Sign out* — an action already taken, and no way
 *   back. A profile that needs to sign in again now offers exactly that, and carries its server address
 *   and username with it so nothing has to be retyped.
 */
@Composable
private fun ProfileCard(
    row: ProfileRow,
    isActive: Boolean,
    isBusy: Boolean,
    onSelected: () -> Unit,
    onSignOut: () -> Unit,
    onSignInAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    val profile = row.profile
    val selectLabel = stringResource(R.string.profiles_use_named, profile.displayName)
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (isActive || isBusy) {
                Modifier
            } else {
                Modifier
                    .clickable(onClick = onSelected)
                    .semantics { contentDescription = selectLabel }
            },
        )

    Card(
        modifier = cardModifier,
        colors = if (isActive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    ActiveBadge()
                }
            }
            Text(
                text = stringResource(R.string.profiles_role, profile.role.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // PRODUCT_SPEC AUTH-002 — which server this account is on. Two profiles on one server look
            // identical without it, and two accounts with the same username on different servers are the
            // case where guessing wrong actually costs something.
            row.server?.let { server ->
                Text(
                    text = server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // AUTH-005 — the same reasoning as the reauthentication mark below, and it arrived for the
            // same reason: `SwitchProfileUseCase` refuses a locked profile, so a card that gave no sign of
            // it offered an action the app would decline. Stating it turns a failure into an expectation.
            if (row.hasPasscode) {
                Text(
                    text = stringResource(R.string.profiles_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // PRODUCT_SPEC AUTH-004 — the mark is visible rather than only enforced, so the user learns why
            // a refresh is refused before they try one.
            if (profile.requiresReauthentication) {
                Text(
                    text = stringResource(R.string.profiles_needs_sign_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!isActive) {
                    TextButton(onClick = onSelected, enabled = !isBusy) {
                        Text(text = stringResource(R.string.profiles_use))
                    }
                }
                if (profile.requiresReauthentication) {
                    TextButton(onClick = onSignInAgain, enabled = !isBusy) {
                        Text(text = stringResource(R.string.profiles_sign_in_again))
                    }
                } else {
                    TextButton(onClick = onSignOut, enabled = !isBusy) {
                        Text(text = stringResource(R.string.profiles_sign_out))
                    }
                }
                TextButton(onClick = onRemove, enabled = !isBusy) {
                    Text(text = stringResource(R.string.profiles_remove))
                }
            }
        }
    }
}

/** A filled badge rather than a line of text: the active account has to be findable at a glance. */
@Composable
private fun ActiveBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(R.string.profiles_active),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * PRODUCT_SPEC 21 / MGR-005's principle — a destructive action states its actual effect.
 *
 * The wording is specific about both halves, because the difference between this and signing out is the
 * entire reason both buttons exist: this deletes the profile's local data, and it deletes nothing on the
 * server. A confirmation that said only "are you sure?" would leave the user to guess which one they were
 * about to do.
 */
@Composable
private fun RemoveProfileDialog(profile: Profile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.profiles_remove_title, profile.displayName)) },
        text = { Text(text = stringResource(R.string.profiles_remove_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.profiles_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profiles_remove_cancel))
            }
        },
    )
}

/**
 * AUTH-005 — the passcode field for a profile that is not the active one.
 *
 * ### Why this exists at all
 *
 * The curtain draws for the **active** profile and only that one, because `observeLockState` reads
 * `activeProfileId`. `SwitchProfileUseCase` refuses a locked target *before* it becomes active, so without
 * this dialog the two rules meet in a dead end: tapping a locked card produced "That account is locked.
 * Enter its passcode to switch to it", and there was no field anywhere in the app in which to enter it. A
 * profile locked on a device whose active account is a different one could not be opened at all — only
 * signed in to again, which silently discards the passcode.
 *
 * ### What it does not do
 *
 * No biometric button. The platform prompt is an activity-level dialogue and stacking it on top of this one
 * is a shape no test here can reach; the passcode is the floor on every supported release anyway, and the
 * curtain still offers biometrics once the profile is active.
 *
 * No recovery block either. Re-authenticating clears a passcode, and offering that on a *switch* would put
 * a destructive action behind a gesture that meant "show me my other account". The route stays on the
 * curtain, where the account is the one in front of you.
 */
@Composable
private fun UnlockProfileDialog(
    prompt: UnlockPrompt,
    username: String?,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.profiles_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = username
                        ?.let { stringResource(R.string.profiles_unlock_body, it) }
                        ?: stringResource(R.string.profiles_unlock_body_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { next -> typed = next.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.lock_passcode_label)) },
                    singleLine = true,
                    enabled = !prompt.isChecking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SWITCHER_PASSCODE_FIELD),
                )
                // Shared with the curtain rather than mapped again here. The case that would drift first is
                // `Unreadable`, whose whole point is that it must not read as "wrong passcode".
                prompt.failure?.let { FailureText(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Converted to an array as late as possible and wiped by the view model. The `String`
                    // behind the field cannot be wiped, which is `OutlinedTextField`'s limitation rather
                    // than a choice.
                    onSubmit(typed.toCharArray())
                    typed = ""
                },
                enabled = !prompt.isChecking && typed.isNotEmpty(),
                modifier = Modifier.testTag(SWITCHER_PASSCODE_SUBMIT),
            ) {
                Text(text = stringResource(R.string.lock_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !prompt.isChecking) {
                Text(text = stringResource(R.string.profiles_remove_cancel))
            }
        },
    )
}

internal const val SWITCHER_PASSCODE_FIELD = "switcher-passcode-field"
internal const val SWITCHER_PASSCODE_SUBMIT = "switcher-passcode-submit"
