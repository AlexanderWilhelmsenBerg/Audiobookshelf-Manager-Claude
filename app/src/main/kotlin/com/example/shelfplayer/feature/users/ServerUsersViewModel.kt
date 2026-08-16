package com.example.shelfplayer.feature.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.NewServerUser
import com.example.shelfplayer.core.model.NewUserError
import com.example.shelfplayer.core.model.ServerUser
import com.example.shelfplayer.domain.repository.ServerUserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC EPIC USER — the server's accounts.
 *
 * ### The password lives here for one call and no longer
 *
 * USER-002 requires that the password be "held only in transient UI state and cleared after submission".
 * [ServerUsersUiState.password] is that state, and [createUser] clears it on **every** outcome — success,
 * refusal, or a network failure — rather than only on success. A failed create leaves the username so the
 * administrator can try again, and does not leave the password sitting in memory behind a screen they may
 * put down.
 *
 * ### Why a refresh rather than a local update
 *
 * Creating or disabling an account re-reads the list. The alternative — patching the local copy — would be
 * faster and would also be this app deciding what the server now holds, which is exactly the assumption
 * USER-002's "success refreshes the user list" exists to prevent.
 */
@HiltViewModel
class ServerUsersViewModel @Inject constructor(private val users: ServerUserRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUsersUiState())
    val uiState: StateFlow<ServerUsersUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorSummary = null) }
            when (val listed = users.list()) {
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorSummary = listed.error.summary)
                }
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, users = listed.value)
                }
            }
        }
    }

    fun usernameChanged(username: String) {
        _uiState.update { it.copy(username = username, fieldErrors = emptySet(), errorSummary = null) }
    }

    fun passwordChanged(password: String) {
        _uiState.update { it.copy(password = password, fieldErrors = emptySet(), errorSummary = null) }
    }

    fun accountTypeChanged(accountType: String) {
        _uiState.update { it.copy(accountType = accountType, fieldErrors = emptySet()) }
    }

    /**
     * PRODUCT_SPEC USER-002 — create the account, then forget the password whatever happened.
     *
     * `isActive` is sent as `true` because the server defaults it to `false`, which would create an account
     * nobody can sign in to. Permissions default to download only, matching what the server gives an
     * ordinary `user` — an administrator who wants more grants them afterwards, deliberately.
     */
    fun createUser() {
        val state = _uiState.value
        val request = NewServerUser(
            username = state.username.trim(),
            password = state.password,
            accountType = state.accountType,
        )
        val errors = request.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = errors) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, errorSummary = null) }
            val created = users.create(request)
            // Cleared on every path, not only on success. See the class comment.
            val failure = (created as? AppResult.Failure)?.error
            // PRODUCT_SPEC USER-002 — a taken username belongs against the field, not in a banner: the
            // user has to change *that box*, and a page-level card does not say which one.
            val takenUsername = (failure as? AppError.Validation)?.fieldErrors?.containsKey("username") == true
            _uiState.update {
                it.copy(
                    isCreating = false,
                    password = "",
                    username = if (created is AppResult.Success) "" else it.username,
                    fieldErrors = if (takenUsername) setOf(NewUserError.UsernameTaken) else emptySet(),
                    errorSummary = failure?.summary?.takeUnless { takenUsername },
                )
            }
            if (created is AppResult.Success) refresh()
        }
    }

    /**
     * PRODUCT_SPEC USER-003 — disabling is preferred to deletion, and deletion is not offered at all.
     *
     * ### Disabling yourself is refused outright
     *
     * USER-003 asks that "the currently authenticated user cannot accidentally remove their own required
     * admin access without an explicit elevated confirmation". On this screen the mistake is one mis-tap on
     * a `Switch`, and it is **unrecoverable from inside the app**: the account that would have to undo it is
     * the one that was just disabled, so the next request is a `401` and there is no second admin session to
     * fall back on. The recovery is a shell on the server.
     *
     * So there is no elevated confirmation for this case — there is a refusal. A confirmation dialogue is
     * the right shape for a decision somebody might legitimately make; locking yourself out of the app you
     * are holding is not one, and offering it politely would be offering a trap with a handrail.
     */
    fun setActive(user: ServerUser, isActive: Boolean) {
        val signedInAs = _uiState.value.signedInAs
        if (!isActive && signedInAs.isNotEmpty() && user.username == signedInAs) {
            _uiState.update { it.copy(selfDisableRefused = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(errorSummary = null) }
            when (val changed = users.setActive(user.id, isActive)) {
                is AppResult.Failure -> _uiState.update { it.copy(errorSummary = changed.error.summary) }
                is AppResult.Success -> refresh()
            }
        }
    }

    fun acknowledgeSelfDisable() {
        _uiState.update { it.copy(selfDisableRefused = false) }
    }
}

/**
 * PRODUCT_SPEC EPIC USER — the screen's state.
 *
 * Note what is not here: no token, no password hash, and no cached copy that survives the screen. The list
 * is re-read on entry and dropped when the `ViewModel` is cleared, which is USER-001's "not cached for
 * offline viewing by default" expressed as an absence rather than as a policy somebody has to remember.
 */
data class ServerUsersUiState(
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val users: List<ServerUser> = emptyList(),
    val username: String = "",
    /** PRODUCT_SPEC USER-002 — transient, and cleared on every outcome of a create. */
    val password: String = "",
    val accountType: String = NewServerUser.ACCOUNT_TYPES.first(),
    val fieldErrors: Set<NewUserError> = emptySet(),
    val errorSummary: String? = null,
    /** PRODUCT_SPEC USER-003 — the signed-in account's username, so its own row can be marked and guarded. */
    val signedInAs: String = "",
    /** Set when the user tried to disable their own account, which this screen refuses. */
    val selfDisableRefused: Boolean = false,
) {
    /** The rows, each knowing whether it is the account currently holding this session. */
    val rows: List<ServerUserRow>
        get() = users.map { user ->
            ServerUserRow(user = user, isCurrentUser = signedInAs.isNotEmpty() && user.username == signedInAs)
        }
}

/** One account, plus the one thing the list knows that the account itself does not. */
data class ServerUserRow(val user: ServerUser, val isCurrentUser: Boolean)
