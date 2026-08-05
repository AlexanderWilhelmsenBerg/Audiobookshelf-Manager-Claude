package com.example.shelfplayer.feature.profiles

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId

@Composable
fun ProfileSwitcherRoute(
    onNavigateUp: () -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileSwitcherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileSwitcherScreen(
        uiState = uiState,
        onProfileSelected = viewModel::onProfileSelected,
        onSignOut = viewModel::onSignOut,
        onRemoveProfile = viewModel::onRemoveProfile,
        onErrorDismissed = viewModel::onErrorDismissed,
        onNavigateUp = onNavigateUp,
        onAddProfile = onAddProfile,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherScreen(
    uiState: ProfileSwitcherUiState,
    onProfileSelected: (ProfileId) -> Unit,
    onSignOut: (ProfileId) -> Unit,
    onRemoveProfile: (ProfileId) -> Unit,
    onErrorDismissed: () -> Unit,
    onNavigateUp: () -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
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
                onAction = onAddProfile,
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
                    TextButton(onClick = onErrorDismissed) {
                        Text(text = stringResource(R.string.profiles_dismiss))
                    }
                }
            }

            items(uiState.profiles, key = { it.id.value }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = profile.id == uiState.activeProfileId,
                    isBusy = uiState.isBusy,
                    onSelected = { onProfileSelected(profile.id) },
                    onSignOut = { onSignOut(profile.id) },
                    onRemove = { pendingRemoval = profile },
                )
            }

            item {
                TextButton(onClick = onAddProfile, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profiles_add))
                }
            }
        }
    }

    pendingRemoval?.let { profile ->
        RemoveProfileDialog(
            profile = profile,
            onConfirm = {
                onRemoveProfile(profile.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    isActive: Boolean,
    isBusy: Boolean,
    onSelected: () -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = profile.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.profiles_role, profile.role.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                if (isActive) {
                    Text(
                        text = stringResource(R.string.profiles_active),
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    TextButton(onClick = onSelected, enabled = !isBusy) {
                        Text(text = stringResource(R.string.profiles_use))
                    }
                }
                TextButton(onClick = onSignOut, enabled = !isBusy) {
                    Text(text = stringResource(R.string.profiles_sign_out))
                }
                TextButton(onClick = onRemove, enabled = !isBusy) {
                    Text(text = stringResource(R.string.profiles_remove))
                }
            }
        }
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
