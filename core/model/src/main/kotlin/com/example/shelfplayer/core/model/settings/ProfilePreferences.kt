package com.example.shelfplayer.core.model.settings

import com.example.shelfplayer.core.model.LibraryId

/**
 * PRODUCT_SPEC SET-001 / AUTH-002 — how one account last left its screens.
 *
 * Per profile, not per device. Two accounts on one server are two people, PRODUCT_SPEC 5.2 keeps their
 * content apart, and an arrangement that followed the device would put one person's sort order and
 * default library on the other's screen.
 *
 * The sort orders are stored as names rather than as a typed enum. `BookSortOrder` lives in `:domain`
 * and this model is read by `:core:datastore`, which must not depend on it; resolving the name at the
 * point of use means an order that no longer exists falls back to the default instead of failing to
 * parse. See `app_settings.proto`.
 *
 * @property defaultLibraryId the library this profile opens on, or `null` for every granted library.
 * @property libraryOrders sort order name per library id.
 * @property shelfOrder sort order name for the home shelf, which spans libraries.
 */
data class ProfilePreferences(
    val defaultLibraryId: LibraryId? = null,
    val libraryOrders: Map<String, String> = emptyMap(),
    val shelfOrder: String? = null,
) {
    /** The stored order name for one library, or for the home shelf when [libraryId] is `null`. */
    fun orderName(libraryId: LibraryId?): String? =
        if (libraryId == null) shelfOrder else libraryOrders[libraryId.value]

    companion object {
        val Empty = ProfilePreferences()
    }
}
