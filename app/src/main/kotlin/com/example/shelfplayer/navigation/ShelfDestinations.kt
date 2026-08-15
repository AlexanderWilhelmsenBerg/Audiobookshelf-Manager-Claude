package com.example.shelfplayer.navigation

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import java.net.URLEncoder

/**
 * PRODUCT_SPEC 9.1 — the routes of the app shell.
 *
 * PRODUCT_SPEC 15 requires deep links to validate server/profile/item access. No `<intent-filter>` deep
 * links are registered: a route that cannot be reached from outside the app cannot be used to bypass a
 * permission check. When they are registered, the check they need already exists —
 * `AbsLibraryApi.listBooks` refuses a library the active profile is not granted, whatever asked for it.
 */
object ShelfDestinations {
    /**
     * PRODUCT_SPEC AUTH-001 / AUTH-004 — adding a server, and returning to one.
     *
     * Both arguments are optional and empty by default, so the plain `sign-in` route still resolves. They
     * exist for reauthentication: a profile the server stopped accepting already told the app its address
     * and its username, and making the user retype a host on a phone keyboard is how a reauthentication
     * prompt turns into an abandoned account.
     */
    const val SIGN_IN = "sign-in?serverUrl={serverUrl}&username={username}"
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val SETTINGS = "settings"

    /** PRODUCT_SPEC DL-003 / ADR-0018 decision 6 — every download on this device, in one list. */
    const val DOWNLOADS = "downloads"

    /** PRODUCT_SPEC EPIC USER — the server's own accounts. Admin only; the screen and the server both check. */
    const val SERVER_USERS = "server-users"
    const val BOOK = "book/{bookId}"

    /** PRODUCT_SPEC MGR-001 — the metadata editor, addressed at the book it edits. */
    const val EDIT_METADATA = "book/{bookId}/metadata"
    const val SERIES = "series/{seriesId}"

    const val ARG_BOOK_ID = "bookId"
    const val ARG_SERIES_ID = "seriesId"

    /**
     * The arguments are URL-encoded: a server address contains `:` and `/`, which would otherwise end the
     * query value and take the rest of the address with it.
     */
    fun signIn(serverUrl: String? = null, username: String? = null): String = "sign-in?serverUrl=" +
        encode(serverUrl) + "&username=" + encode(username)

    private fun encode(value: String?): String = URLEncoder.encode(value.orEmpty(), Charsets.UTF_8.name())

    fun book(bookId: LibraryItemId): String = "book/${bookId.value}"

    fun editMetadata(bookId: LibraryItemId): String = "book/${bookId.value}/metadata"

    /**
     * PRODUCT_SPEC LIB-003 — the bare server-side series id, exactly as the book route carries an item
     * id. The server it belongs to is not in the route: the repository scopes the lookup to the active
     * profile's server, so a route cannot name a series on a server the user is not currently on.
     */
    fun series(seriesId: SeriesId): String = "series/${seriesId.value}"
}
