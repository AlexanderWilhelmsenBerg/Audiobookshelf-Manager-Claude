package com.example.shelfplayer.feature.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.NewServerUser
import com.example.shelfplayer.core.model.NewUserError
import com.example.shelfplayer.core.model.ServerUser

/**
 * PRODUCT_SPEC EPIC USER — the server's accounts, for an administrator.
 *
 * ### What each row does and does not show
 *
 * Username, account type, whether the account is active or locked, and its four grants. **No token and no
 * password hash**, and not because they are filtered here — the DTO never parses them, so there is nothing
 * on this screen to filter (USER-001).
 *
 * ### Disabling, not deleting
 *
 * The only change this screen makes to an existing account is its active state. USER-003 prefers disabling
 * where the server supports it and puts deletion in later scope "unless thoroughly contract-tested" — and
 * it has not been, so there is no delete button rather than a disabled one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerUsersScreen(onBack: () -> Unit, viewModel: ServerUsersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.users_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Text(stringResource(R.string.users_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorSummary?.let { summary ->
                Card(Modifier.fillMaxWidth()) {
                    Text(summary, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                state.rows.forEach { row -> UserRow(row, viewModel) }
            }
            HorizontalDivider()
            CreateUserForm(state, viewModel)
        }
    }

    if (state.selfDisableRefused) {
        AlertDialog(
            onDismissRequest = viewModel::acknowledgeSelfDisable,
            title = { Text(stringResource(R.string.users_self_disable_title)) },
            text = { Text(stringResource(R.string.users_self_disable_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeSelfDisable) {
                    Text(stringResource(R.string.users_self_disable_ok))
                }
            },
        )
    }
}

/**
 * PRODUCT_SPEC USER-003 — one account, and whether it is the one holding this session.
 *
 * The signed-in row is labelled and its switch is disabled while it is on. Disabling yourself is refused in
 * the `ViewModel` regardless, but a control that cannot do harm is better than one that refuses afterwards:
 * the switch never moves, so there is no moment where the user believes it worked.
 */
@Composable
private fun UserRow(row: ServerUserRow, viewModel: ServerUsersViewModel) {
    val user = row.user
    ListItem(
        headlineContent = {
            Text(
                if (row.isCurrentUser) {
                    stringResource(R.string.users_you, user.username)
                } else {
                    user.username
                },
            )
        },
        supportingContent = { Text(descriptionOf(user)) },
        trailingContent = {
            Switch(
                checked = user.isActive,
                // Only the *disabling* direction is barred. Re-enabling your own account is impossible to
                // reach anyway — a disabled account has no session — but the asymmetry is the honest one.
                enabled = !(row.isCurrentUser && user.isActive),
                onCheckedChange = { checked -> viewModel.setActive(user, checked) },
            )
        },
    )
}

/**
 * The grants, as words.
 *
 * Listed rather than shown as four toggles because this screen does not change them: USER-003 requires a
 * warning before removing library access, and that is a conversation this build does not have yet. Showing
 * them read-only is honest; showing them as switches that do nothing would not be.
 */
@Composable
private fun descriptionOf(user: ServerUser): String {
    val state = when {
        user.isLocked -> stringResource(R.string.users_state_locked)
        user.isActive -> stringResource(R.string.users_state_active)
        else -> stringResource(R.string.users_state_disabled)
    }
    val grants = buildList {
        if (user.canDownload) add(stringResource(R.string.users_grant_download))
        if (user.canUpdate) add(stringResource(R.string.users_grant_update))
        if (user.canDelete) add(stringResource(R.string.users_grant_delete))
        if (user.canUpload) add(stringResource(R.string.users_grant_upload))
    }
    val grantText = grants.joinToString(", ").ifEmpty { stringResource(R.string.users_grant_none) }
    return "${user.accountType} · $state · $grantText"
}

/**
 * PRODUCT_SPEC USER-002 — the create form.
 *
 * The new account is created **active**, because the server defaults it to inactive and an account nobody
 * can sign in to is not what "created" means. Its permissions are the server's own defaults for an ordinary
 * `user` — download and nothing else — which an administrator raises deliberately afterwards rather than by
 * filling in a form they were not thinking about.
 *
 * `root` is absent from the type list. It is created by the installer, and USER-002's "creating
 * root-equivalent accounts requires an additional warning" is a warning this build would rather not need.
 */
@Composable
private fun CreateUserForm(state: ServerUsersUiState, viewModel: ServerUsersViewModel) {
    Text(stringResource(R.string.users_create_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::usernameChanged,
        label = { Text(stringResource(R.string.users_username)) },
        singleLine = true,
        isError = NewUserError.UsernameRequired in state.fieldErrors ||
            NewUserError.UsernameTaken in state.fieldErrors,
        supportingText = errorTextFor(NewUserError.UsernameRequired, state, R.string.users_username_required)
            ?: errorTextFor(NewUserError.UsernameTaken, state, R.string.users_username_taken),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::passwordChanged,
        label = { Text(stringResource(R.string.users_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = NewUserError.PasswordRequired in state.fieldErrors,
        supportingText = errorTextFor(NewUserError.PasswordRequired, state, R.string.users_password_required),
        modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NewServerUser.ACCOUNT_TYPES.forEach { type ->
            FilterChip(
                selected = state.accountType == type,
                onClick = { viewModel.accountTypeChanged(type) },
                label = { Text(type) },
            )
        }
    }
    TextButton(onClick = viewModel::createUser, enabled = !state.isCreating) {
        Text(stringResource(R.string.users_create))
    }
}

@Composable
private fun errorTextFor(error: NewUserError, state: ServerUsersUiState, message: Int): (@Composable () -> Unit)? =
    if (error in state.fieldErrors) {
        { Text(stringResource(message), color = MaterialTheme.colorScheme.error) }
    } else {
        null
    }
