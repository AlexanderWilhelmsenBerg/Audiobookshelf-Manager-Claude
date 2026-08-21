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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.model.Profile

@Composable
fun ProfileSwitcherRoute(
    onNavigateUp: () -> Unit,
    onAddProfile: () -> Unit,
    onSignInAgain: (serverUrl: String?, username: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileSwitcherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileSwitcherScreen(
        uiState = uiState,
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
