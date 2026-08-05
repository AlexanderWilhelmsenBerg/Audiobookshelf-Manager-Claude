package com.example.shelfplayer.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.ServerCandidate

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
        onServerUrlChanged = viewModel::onServerUrlChanged,
        onServerSubmitted = viewModel::onServerSubmitted,
        onBackToServer = viewModel::onBackToServer,
        onUsernameChanged = viewModel::onUsernameChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onCredentialsSubmitted = viewModel::onCredentialsSubmitted,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    uiState: SignInUiState,
    onServerUrlChanged: (String) -> Unit,
    onServerSubmitted: () -> Unit,
    onBackToServer: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCredentialsSubmitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.sign_in_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (uiState.stage) {
                SignInStage.Address -> AddressStage(
                    uiState = uiState,
                    onServerUrlChanged = onServerUrlChanged,
                    onServerSubmitted = onServerSubmitted,
                )

                SignInStage.Credentials -> CredentialsStage(
                    uiState = uiState,
                    onUsernameChanged = onUsernameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onCredentialsSubmitted = onCredentialsSubmitted,
                    onBackToServer = onBackToServer,
                )
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

@Composable
private fun AddressStage(uiState: SignInUiState, onServerUrlChanged: (String) -> Unit, onServerSubmitted: () -> Unit) {
    Text(text = stringResource(R.string.sign_in_address_prompt), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = uiState.serverUrl,
        onValueChange = onServerUrlChanged,
        label = { Text(text = stringResource(R.string.sign_in_server_label)) },
        supportingText = { Text(text = stringResource(R.string.sign_in_server_hint)) },
        singleLine = true,
        enabled = !uiState.isBusy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (uiState.canSubmitServer) onServerSubmitted() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onServerSubmitted,
        enabled = uiState.canSubmitServer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.sign_in_continue))
    }
}

@Composable
private fun CredentialsStage(
    uiState: SignInUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCredentialsSubmitted: () -> Unit,
    onBackToServer: () -> Unit,
) {
    uiState.candidate?.let { ServerSummary(candidate = it) }

    OutlinedTextField(
        value = uiState.username,
        onValueChange = onUsernameChanged,
        label = { Text(text = stringResource(R.string.sign_in_username_label)) },
        singleLine = true,
        enabled = !uiState.isBusy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.password,
        onValueChange = onPasswordChanged,
        label = { Text(text = stringResource(R.string.sign_in_password_label)) },
        singleLine = true,
        enabled = !uiState.isBusy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (uiState.canSubmitCredentials) onCredentialsSubmitted() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onCredentialsSubmitted,
        enabled = uiState.canSubmitCredentials,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.sign_in_submit))
    }
    TextButton(onClick = onBackToServer, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
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
