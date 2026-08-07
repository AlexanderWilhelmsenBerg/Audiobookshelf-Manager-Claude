package com.example.shelfplayer.feature.profiles

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.ProfileId

/**
 * Everything the profile switcher can do, in one parameter.
 *
 * Each saved profile offers three actions and the screen offers three more, which is more callbacks than a
 * readable parameter list holds. Grouping them keeps `ProfileSwitcherScreen` a function of
 * `(state, actions)` and makes it previewable with one no-op instance.
 */
@Immutable
data class ProfileSwitcherActions(
    val onProfileSelected: (ProfileId) -> Unit,
    val onSignOut: (ProfileId) -> Unit,
    /**
     * PRODUCT_SPEC AUTH-004 — reauthentication carries what the app already knows.
     *
     * The server address is nullable because the join to the server row can miss; the sign-in screen then
     * asks for it, which is the only honest alternative to guessing a host to send a password to.
     */
    val onSignInAgain: (serverUrl: String?, username: String) -> Unit,
    val onRemoveProfile: (ProfileId) -> Unit,
    val onErrorDismissed: () -> Unit,
    val onNavigateUp: () -> Unit,
    val onAddProfile: () -> Unit,
)
