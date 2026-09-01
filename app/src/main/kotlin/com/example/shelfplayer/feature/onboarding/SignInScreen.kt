package com.example.shelfplayer.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.layout.contentWidth
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.feature.settings.transfer.ImportSettingsButton

/**
 * PRODUCT_SPEC AUTH-001 / 6.1 — the first thing a new install shows.
 *
 * `*Route` wires navigation and state; `*Screen` is a pure function of its arguments, so it can be
 * previewed and screenshot-tested without Hilt (PRODUCT_SPEC 16.4).
 */
@Composable
fun SignInRoute(onSignedIn: () -> Unit, modifier: Modifier = Modifier, viewModel: SignInViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigation is driven by a one-shot signal that the ViewModel then clears, so a recomposition or a
    // configuration change cannot navigate a second time.
    LaunchedEffect(uiState.signedIn) {
        if (uiState.signedIn != null) {
            viewModel.onSignedInHandled()
            onSignedIn()
        }
    }

    SignInScreen(
        uiState = uiState,
        actions = SignInActions(
            onServerUrlChanged = viewModel::onServerUrlChanged,
            onServerSubmitted = viewModel::onServerSubmitted,
            onKnownServerSelected = viewModel::onKnownServerSelected,
            onBackToServer = viewModel::onBackToServer,
            onUsernameChanged = viewModel::onUsernameChanged,
            onPasswordChanged = viewModel::onPasswordChanged,
            onCredentialsSubmitted = viewModel::onCredentialsSubmitted,
        ),
        modifier = modifier,
        // PRODUCT_SPEC SET-001 — a slot rather than a call, so `SignInScreen` stays a pure function of its
        // arguments (16.4) and its tests keep rendering without Hilt. The route is where a ViewModel may
        // be resolved; the screen only decides where the button sits.
        importSettings = { ImportSettingsButton(onServerPicked = viewModel::onKnownServerSelected) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    uiState: SignInUiState,
    actions: SignInActions,
    modifier: Modifier = Modifier,
    importSettings: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.sign_in_title)) }) },
    ) { innerPadding ->
        // PRODUCT_SPEC 4 / §51 — a sign-in form is the clearest case for a width cap. There are two
        // fields and a button; stretched across a tablet they sit a hand-span apart with nothing between
        // them, and the address field becomes a line of text nobody can scan. The outer box takes the
        // window and the inner column takes a readable measure of it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .contentWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (uiState.stage) {
                    SignInStage.Address ->
                        AddressStage(uiState = uiState, actions = actions, importSettings = importSettings)

                    SignInStage.Credentials -> CredentialsStage(uiState = uiState, actions = actions)
                }

                uiState.error?.let { error ->
                    // PRODUCT_SPEC 14.4 — the summary is the plain-language part and the code is the optional
                    // technical detail. Both are already redaction-safe, so neither can leak a host or a token.
                    Column(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(text = error.summary, color = MaterialTheme.colorScheme.error)
                        Text(
                            text = error.code,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (uiState.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

@Composable
private fun AddressStage(uiState: SignInUiState, actions: SignInActions, importSettings: @Composable () -> Unit) {
    Text(text = stringResource(R.string.sign_in_address_prompt), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = uiState.serverUrl,
        onValueChange = actions.onServerUrlChanged,
        label = { Text(text = stringResource(R.string.sign_in_server_label)) },
        supportingText = { Text(text = stringResource(R.string.sign_in_server_hint)) },
        singleLine = true,
        enabled = !uiState.isBusy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (uiState.canSubmitServer) actions.onServerSubmitted() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = actions.onServerSubmitted,
        enabled = uiState.canSubmitServer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.sign_in_continue))
    }
    KnownServers(uiState = uiState, onSelected = actions.onKnownServerSelected)
    // PRODUCT_SPEC SET-001 — below the servers this device already knows, because that list is the better
    // answer when it is not empty: a device that has a server does not need a file to find one.
    importSettings()
}

/**
 * PRODUCT_SPEC AUTH-001 / 6.1 — the servers this device already knows.
 *
 * Adding a second account to a server is the common case, and until now it meant typing a host on a phone
 * keyboard. Each entry shows what the app recorded last time: the address, the version it detected, and
 * whether the connection was encrypted.
 *
 * What it emphatically does **not** do is trust any of that. Picking one fills the field and runs the same
 * probe a typed address gets, so the version and the encryption line the user reads before entering a
 * password describe the server *now* — a certificate expires, a server is upgraded, and a remembered
 * "encrypted" would be a claim the app had stopped checking (PRODUCT_SPEC 15).
 */
@Composable
private fun KnownServers(uiState: SignInUiState, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    if (uiState.knownServers.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.sign_in_known_servers),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        uiState.knownServers.forEach { server ->
            Card(
                onClick = { onSelected(server.url) },
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = server.url, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = server.detectedVersion
                            ?.let { stringResource(R.string.sign_in_detected_version, it) }
                            ?: stringResource(R.string.sign_in_known_version_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val encryption = when {
                        server.isEncrypted -> R.string.sign_in_known_encrypted
                        else -> R.string.sign_in_known_cleartext
                    }
                    Text(
                        text = stringResource(encryption),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (server.isEncrypted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.sign_in_known_checked_again),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CredentialsStage(uiState: SignInUiState, actions: SignInActions) {
    uiState.candidate?.let { ServerSummary(candidate = it) }

    OutlinedTextField(
        value = uiState.username,
        onValueChange = actions.onUsernameChanged,
        label = { Text(text = stringResource(R.string.sign_in_username_label)) },
        singleLine = true,
        enabled = !uiState.isBusy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.password,
        onValueChange = actions.onPasswordChanged,
        label = { Text(text = stringResource(R.string.sign_in_password_label)) },
        singleLine = true,
        enabled = !uiState.isBusy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            if (uiState.canSubmitCredentials) actions.onCredentialsSubmitted()
        }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = actions.onCredentialsSubmitted,
        enabled = uiState.canSubmitCredentials,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.sign_in_submit))
    }
    TextButton(onClick = actions.onBackToServer, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.sign_in_change_server))
    }
}

/**
 * PRODUCT_SPEC 6.1 step 4 — the detected version and the connection security, before the password.
 *
 * The cleartext warning is not decoration. PRODUCT_SPEC 15 disables cleartext in release builds unless the
 * user opts in per server, and a user about to type a password into an `http://` address is entitled to
 * know it will cross the network unencrypted.
 */
@Composable
private fun ServerSummary(candidate: ServerCandidate) {
    Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = candidate.serverUrl, style = MaterialTheme.typography.titleSmall)
            Text(
                text = candidate.probe.serverVersion
                    ?.let { stringResource(R.string.sign_in_detected_version, it) }
                    ?: stringResource(R.string.sign_in_version_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
            if (candidate.wasSchemeAssumed) {
                Text(
                    text = stringResource(R.string.sign_in_https_assumed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (candidate.isCleartext) {
                    stringResource(R.string.sign_in_cleartext_warning)
                } else {
                    stringResource(R.string.sign_in_encrypted)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (candidate.isCleartext) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
