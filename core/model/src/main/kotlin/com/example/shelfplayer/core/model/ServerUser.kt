package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC USER-001 — an account on the server, as an administrator sees it.
 *
 * ### There is no token here, and there never will be
 *
 * `GET /api/users` returns **every user's live access token**, in plain text, to any admin who asks. That
 * is a fact about the server and it is recorded in `docs/api-compatibility.md` and pinned by a test.
 *
 * USER-001 says tokens are never *displayed*. The stronger rule this model enforces is that the app must
 * never **hold** one: `UserDto` has no `token` property, so the field is dropped at the wire and there is
 * nothing here to store, to log, to put in a crash report, or to render by accident. A field that is never
 * parsed cannot leak.
 *
 * The same applies to `pash`, the password hash, for the same reason.
 *
 * @property isActive an account the server has disabled. USER-003 prefers disabling to deletion, so this is
 *   the field that feature turns on and off.
 * @property isLocked locked out by the server, which is not the same as disabled — one is an administrator's
 *   decision and the other is a consequence of failed sign-ins.
 */
data class ServerUser(
    val id: String,
    val username: String,
    val accountType: String,
    val isActive: Boolean,
    val isLocked: Boolean,
    val canDownload: Boolean,
    val canUpdate: Boolean,
    val canDelete: Boolean,
    val canUpload: Boolean,
    val hasAllLibraryAccess: Boolean,
    val accessibleLibraryIds: List<String>,
) {
    val role: ProfileRole get() = ProfileRole.ofAccountType(accountType)
}

/**
 * PRODUCT_SPEC USER-002 — the fields a new account needs.
 *
 * ### `isActive` defaults to true here and to false on the server
 *
 * The server stores `isActive: !!req.body.isActive`, so an omitted flag creates an account that **cannot
 * sign in** — captured, and paired with a second fixture that proves sending `true` works. USER-002 says
 * active state is required, and defaulting it to `true` here is what stops the app from cheerfully
 * reporting "created" about an account nobody can use.
 *
 * ### The password is not in this type twice
 *
 * It is held only for as long as the request takes, and USER-002 requires it be cleared from UI state after
 * submission. Keeping it in one place makes that one clear rather than two.
 */
data class NewServerUser(
    val username: String,
    val password: String,
    val accountType: String = "user",
    val isActive: Boolean = true,
    val canDownload: Boolean = true,
    val canUpdate: Boolean = false,
    val canDelete: Boolean = false,
    val canUpload: Boolean = false,
) {
    /** PRODUCT_SPEC USER-002 — "username and password validation matches server feedback". */
    fun validate(): Set<NewUserError> = buildSet {
        if (username.isBlank()) add(NewUserError.UsernameRequired)
        if (password.isBlank()) add(NewUserError.PasswordRequired)
        // The server's own list. An unknown type is rejected with a `400`, and predicting that here turns a
        // round trip into an inline message.
        if (accountType !in ACCOUNT_TYPES) add(NewUserError.UnknownType)
    }

    companion object {
        /** The four Audiobookshelf recognises. `root` is created by the installer and never by this app. */
        val ACCOUNT_TYPES = listOf("user", "guest", "admin")
    }
}

enum class NewUserError { UsernameRequired, PasswordRequired, UnknownType }
