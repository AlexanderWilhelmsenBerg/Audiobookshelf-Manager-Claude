package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.LibraryId

/**
 * PRODUCT_SPEC 5.2 — the server's library grant for one account, and the only place the rule lives.
 *
 * Extracted from [AuthSession] because two callers need the rule and only one of them may hold a token.
 * A sign-in produces it as part of a session; a library sync applies it while holding nothing but a
 * profile id. Duplicating `hasAllLibraryAccess || id in list` at the second call site is how the two
 * drift, and the direction they drift in is "an unauthorized library became visible".
 *
 * ### The empty-list trap
 *
 * `permissions.accessAllLibraries` with an **empty** `librariesAccessible` means *all libraries*, not
 * none. Verified on Audiobookshelf 2.36.0: an ordinary admin has the flag set and an empty list, so
 * reading the list alone hides every library from them. That is why [hasAllLibraryAccess] is checked
 * first and why it is not derived from the list being empty.
 */
data class LibraryAccess(
    val hasAllLibraryAccess: Boolean,
    val accessibleLibraryIds: List<LibraryId>,
    /**
     * PRODUCT_SPEC 5.2 — whether the server also shows this account every *item*.
     *
     * Audiobookshelf restricts twice: by library, and by tag within a library. An account with
     * `accessAllTags = false` is served a *filtered* item list for a library it can otherwise see, which
     * makes its sync a partial view of that library rather than a complete one.
     *
     * That distinction is the difference between a working cache and data loss. Reconciliation deletes
     * what a sync did not return, and a device run proved the consequence: a restricted account synced a
     * shared library and 302 of the unrestricted account's 490 books were marked removed. See
     * [reconciles].
     */
    val hasAllTagAccess: Boolean = false,
    /**
     * PRODUCT_SPEC DL-001 — whether this account may download at all.
     *
     * `user.permissions.download`, observed in `contracts/me.json`. It is **not** about visibility like the
     * two flags above, and it does not affect [reconciles]: an account that may not download still sees the
     * same books and still syncs a complete picture. It rides here because it arrives in the same object and
     * is persisted on the same row, and giving it a type of its own for one boolean would be ceremony.
     *
     * DL-001's first criterion is "the download button is visible only when the server grants it", and until
     * this was persisted that criterion had no plumbing: the field was parsed off the wire and dropped.
     */
    val canDownload: Boolean = false,
    /**
     * PRODUCT_SPEC MGR-001 / MGR-002 / MGR-005 — the three grants EPIC MGR gates on.
     *
     * `user.permissions.update`, `.delete` and `.upload`, all present in `contracts/me.json` and all
     * dropped on the floor until Phase 5. They ride here for the same reason [canDownload] does: they
     * arrive in the same object and are persisted on the same row.
     *
     * Like every other grant here, absent means **no**. PRODUCT_SPEC principle 4 enforces permissions
     * twice, and the second enforcement is only worth anything if an unknown grant is treated as withheld
     * — a management action offered and then refused by the server is worse than one never offered.
     */
    val canUpdate: Boolean = false,
    val canDelete: Boolean = false,
    val canUpload: Boolean = false,
) {

    /**
     * Whether a sync by this account may be read as a complete picture of the server.
     *
     * Only an account that sees every library **and** every item can say "this book is gone" by not
     * returning it. Anyone else is looking through a filter, and absence tells you about the filter.
     * A restricted account still syncs — its books are real and current — it simply never deletes.
     */
    val reconciles: Boolean get() = hasAllLibraryAccess && hasAllTagAccess

    /**
     * Whether this account may see [libraryId].
     *
     * Answered from the server's grant, never inferred from a role: an account can be an `admin` with
     * every library, or a `user` restricted to two.
     */
    fun allows(libraryId: LibraryId): Boolean = hasAllLibraryAccess || libraryId in accessibleLibraryIds

    companion object {
        /**
         * The grant an account has before the server has told us anything.
         *
         * Nothing, deliberately. A profile whose grant has not been recorded must not see a library
         * (PRODUCT_SPEC 5.2), for the same reason an unprobed capability is unsupported: the safe
         * default is the restrictive one.
         */
        val None: LibraryAccess = LibraryAccess(
            hasAllLibraryAccess = false,
            accessibleLibraryIds = emptyList(),
            hasAllTagAccess = false,
        )
    }
}
