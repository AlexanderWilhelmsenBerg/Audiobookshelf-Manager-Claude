package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC EPIC MGR / EPIC USER — the management operations, and the second enforcement of the
 * permissions that guard them (PRODUCT_SPEC principle 4).
 *
 * ### Why this is not a capability set
 *
 * [ServerCapabilities] answers "what does this *server* offer". That is the wrong question for almost
 * everything in EPIC MGR, because the server offers all of it to every deployment and then refuses per
 * account. Two profiles on one server get the same capability set and must get different menus.
 *
 * So the gate is a function of three things — the account's grants, the server's confirmed capabilities,
 * and whether the device can reach the server at all — and it lives here, in the domain model, where
 * every caller reaches the same verdict.
 *
 * ### Why a reason rather than a boolean
 *
 * A hidden action and a disabled one carry different information, and PRODUCT_SPEC's answer differs by
 * requirement: MGR-005 says offline invocation is *blocked* (the user has the permission and cannot use
 * it right now), while MGR-006 says the action *does not exist* without the capability. A caller cannot
 * make that distinction from a boolean, and guessing it is how an app ends up telling somebody they lack
 * a permission they actually have.
 */
enum class ManagementAction {
    /** MGR-001 — `PATCH /api/items/{id}/media`. */
    EditMetadata,

    /** MGR-002 — `POST /api/items/{id}/cover`, which needs *both* update and upload. */
    ChangeCover,

    /** MGR-002 — `DELETE /api/items/{id}/cover`, which the server gates on delete rather than upload. */
    RemoveCover,

    /** MGR-003 — the search-and-preview flow, not quick match. See ADR-0012's amendment for why. */
    MatchMetadata,

    /** MGR-004 — `POST /api/items/{id}/scan`, admin or root. */
    ScanItem,

    /** MGR-004 — `POST /api/libraries/{id}/scan`, admin or root. */
    ScanLibrary,

    /** MGR-005 — `DELETE /api/items/{id}` with no `hard` flag. Media files remain on the server. */
    RemoveFromDatabase,

    /**
     * MGR-006 — true source-file deletion.
     *
     * Never available, and ADR-0021 records why: the endpoints exist, but the server discards a failed
     * filesystem removal and answers success either way, so it cannot satisfy MGR-006's *"the server
     * response must explicitly confirm deletion"*. It is listed here rather than omitted so that the
     * refusal has somewhere to be stated, and so a future server that does confirm needs a probe rather
     * than a new concept.
     */
    DeleteSourceFiles,

    /** EPIC USER — the user list and its writes, admin or root. */
    ManageUsers,
}

/**
 * Why a [ManagementAction] is not available.
 *
 * Ordered by how the UI should treat them: [Offline] is temporary and worth saying out loud, while
 * [Capability] means the action should not be drawn at all.
 */
enum class ManagementBlock {
    /**
     * PRODUCT_SPEC MGR-005 — "offline invocation is blocked", and the same applies to every other write.
     *
     * Deliberately not a queue. PRODUCT_SPEC MGR-001 is explicit that privileged edits are not queued for
     * blind offline execution in version 1: an edit written against an item that changed while the phone
     * was in a tunnel is a conflict nobody is present to resolve.
     */
    Offline,

    /** The server says this account may not. Worth stating — the user may be able to ask an admin. */
    Permission,

    /**
     * No probe has confirmed the server offers this, so PRODUCT_SPEC SYNC-001 requires treating it as
     * absent. For [ManagementAction.DeleteSourceFiles] this is permanent by decision (ADR-0021).
     */
    Capability,
}

/**
 * What one profile may do on one server right now.
 *
 * @property profileRole the account type's bucket. Scanning and user management are gated on this rather
 *   than on a grant, because the server gates them on the account *type*: an account with every
 *   permission set is still refused a scan if its type is `user`.
 * @property isOnline whether a write can reach the server at all.
 */
data class ManagementPermissions(
    val profileRole: ProfileRole,
    val canUpdate: Boolean,
    val canDelete: Boolean,
    val canUpload: Boolean,
    val capabilities: ServerCapabilities?,
    val isOnline: Boolean,
) {
    /** `null` when the action is available. */
    fun blockOn(action: ManagementAction): ManagementBlock? = when (action) {
        ManagementAction.EditMetadata -> permission(canUpdate)
        // Both grants, and in this order: the server checks the method gate first and the upload grant
        // second, so an account with upload but not update is refused for the update it lacks.
        ManagementAction.ChangeCover -> permission(canUpdate && canUpload)
        ManagementAction.RemoveCover -> permission(canDelete)
        ManagementAction.MatchMetadata -> capability(ServerCapability.MatchProvider) ?: permission(canUpdate)
        ManagementAction.ScanItem, ManagementAction.ScanLibrary -> permission(profileRole == ProfileRole.Admin)
        ManagementAction.RemoveFromDatabase -> permission(canDelete)
        // The capability is checked first and is never confirmed, so this always answers `Capability`.
        // Written as a chain anyway: if a server ever does confirm it, the grant becomes the next gate
        // without this branch needing to be reasoned about again.
        ManagementAction.DeleteSourceFiles ->
            capability(ServerCapability.SourceFileDelete) ?: permission(canDelete)
        ManagementAction.ManageUsers -> permission(profileRole == ProfileRole.Admin)
    }

    fun isAvailable(action: ManagementAction): Boolean = blockOn(action) == null

    /**
     * Offline is checked last on purpose.
     *
     * An account that lacks a permission lacks it on the train too, and telling somebody "you are offline"
     * about an action they could never perform sends them to look for signal for no reason.
     */
    private fun permission(granted: Boolean): ManagementBlock? = when {
        !granted -> ManagementBlock.Permission
        !isOnline -> ManagementBlock.Offline
        else -> null
    }

    private fun capability(capability: ServerCapability): ManagementBlock? =
        if (capabilities?.supports(capability) == true) null else ManagementBlock.Capability
}
