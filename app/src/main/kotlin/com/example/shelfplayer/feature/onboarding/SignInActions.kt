package com.example.shelfplayer.feature.onboarding

import androidx.compose.runtime.Immutable

/**
 * Everything the sign-in screen can do, in one parameter.
 *
 * Two stages with a field each and a submit each, plus a way back and a shortcut into a known server, is
 * more callbacks than a readable parameter list holds.
 */
@Immutable
data class SignInActions(
    val onServerUrlChanged: (String) -> Unit,
    val onServerSubmitted: () -> Unit,
    val onKnownServerSelected: (String) -> Unit,
    val onBackToServer: () -> Unit,
    val onUsernameChanged: (String) -> Unit,
    val onPasswordChanged: (String) -> Unit,
    val onCredentialsSubmitted: () -> Unit,
)
