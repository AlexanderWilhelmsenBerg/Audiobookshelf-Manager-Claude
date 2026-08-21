package com.example.shelfplayer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC EPIC MGR — the second enforcement (principle 4), which is the one that runs on this
 * device and therefore the one that can be tested without a server.
 *
 * The gating rules are not this app's invention: they are what Audiobookshelf 2.36.0 does, recorded in
 * `docs/api-compatibility.md` under "Permissions are checked per-method". Each test names the server
 * behaviour it mirrors, because a gate that drifts from the server is worse than no gate — it offers an
 * action that then fails, which MGR-001 explicitly calls out as the bad outcome.
 */
class ManagementActionTest {

    @Test
    fun `an ordinary listener may do none of it`() {
        val permissions = permissions()

        for (action in ManagementAction.entries) {
            assertFalse(permissions.isAvailable(action), "$action was offered to a listener")
        }
    }

    @Test
    fun `an editor may edit metadata and nothing destructive`() {
        val permissions = permissions(canUpdate = true)

        assertTrue(permissions.isAvailable(ManagementAction.EditMetadata))
        assertEquals(ManagementBlock.Permission, permissions.blockOn(ManagementAction.RemoveFromDatabase))
        assertEquals(ManagementBlock.Permission, permissions.blockOn(ManagementAction.RemoveCover))
    }

    /**
     * The one a client written from the requirements alone gets wrong.
     *
     * Uploading a cover is a `POST`, so the server's method gate passes it on the *update* grant, and then
     * the handler refuses it separately unless the account also has *upload*. An account with update and
     * no upload can edit every field and cannot change the picture.
     */
    @Test
    fun `changing a cover needs both update and upload`() {
        assertEquals(
            ManagementBlock.Permission,
            permissions(canUpdate = true).blockOn(ManagementAction.ChangeCover),
        )
        assertEquals(
            ManagementBlock.Permission,
            permissions(canUpload = true).blockOn(ManagementAction.ChangeCover),
        )
        assertNull(permissions(canUpdate = true, canUpload = true).blockOn(ManagementAction.ChangeCover))
    }

    /** Removing a cover is a `DELETE`, so the server gates it on delete — not on upload, not on update. */
    @Test
    fun `removing a cover needs delete rather than upload`() {
        assertNull(permissions(canDelete = true).blockOn(ManagementAction.RemoveCover))
        assertEquals(
            ManagementBlock.Permission,
            permissions(canUpdate = true, canUpload = true).blockOn(ManagementAction.RemoveCover),
        )
    }

    /**
     * PRODUCT_SPEC MGR-004 — "item scan appears only for roles/endpoints that allow it".
     *
     * Scanning and user management are gated on the account *type*, not on any grant. An account holding
     * every permission the server has is still refused if its type is `user`, which is why this is the one
     * place [ProfileRole] does real work rather than being a presentation bucket.
     */
    @Test
    fun `scanning and user management follow the account type and not the grants`() {
        val fullyPermittedListener = permissions(canUpdate = true, canDelete = true, canUpload = true)

        assertEquals(ManagementBlock.Permission, fullyPermittedListener.blockOn(ManagementAction.ScanItem))
        assertEquals(ManagementBlock.Permission, fullyPermittedListener.blockOn(ManagementAction.ScanLibrary))
        assertEquals(ManagementBlock.Permission, fullyPermittedListener.blockOn(ManagementAction.ManageUsers))
        // MGR-007 joins them, and this is the assertion that says why: the account below holds update,
        // delete and upload, and the server still refuses it because `isAdminOrUp` is about the type.
        assertEquals(ManagementBlock.Permission, fullyPermittedListener.blockOn(ManagementAction.EmbedMetadata))

        val admin = permissions(role = ProfileRole.Admin)

        assertNull(admin.blockOn(ManagementAction.ScanItem))
        assertNull(admin.blockOn(ManagementAction.ScanLibrary))
        assertNull(admin.blockOn(ManagementAction.ManageUsers))
        assertNull(admin.blockOn(ManagementAction.EmbedMetadata))
    }

    /**
     * PRODUCT_SPEC MGR-007 — an administrator on a train is *blocked*, not unpermitted.
     *
     * The distinction the whole type exists for, applied to the one action that rewrites files. An offline
     * administrator has the permission and cannot use it right now; telling them they lack it would send
     * them looking for another administrator.
     */
    @Test
    fun `embedding offline is blocked rather than refused`() {
        val offline = permissions(role = ProfileRole.Admin, isOnline = false)

        assertEquals(ManagementBlock.Offline, offline.blockOn(ManagementAction.EmbedMetadata))
    }

    /** PRODUCT_SPEC MGR-003 — matching is the one management action that also needs a confirmed capability. */
    @Test
    fun `matching needs the provider capability as well as the update grant`() {
        assertEquals(
            ManagementBlock.Capability,
            permissions(canUpdate = true).blockOn(ManagementAction.MatchMetadata),
        )
        assertEquals(
            ManagementBlock.Permission,
            permissions(confirmed = setOf(ServerCapability.MatchProvider))
                .blockOn(ManagementAction.MatchMetadata),
        )
        assertNull(
            permissions(canUpdate = true, confirmed = setOf(ServerCapability.MatchProvider))
                .blockOn(ManagementAction.MatchMetadata),
        )
    }

    /**
     * ADR-0021 — source-file deletion is never available, and the reason it reports is the capability.
     *
     * No probe sets [ServerCapability.SourceFileDelete], because the server discards a failed filesystem
     * removal and answers success either way. This test is the guard on that decision: it fails the moment
     * somebody adds a probe that confirms the capability, which is exactly when the decision needs
     * revisiting rather than quietly reversing.
     */
    @Test
    fun `deleting source files is never available even to a root account`() {
        val root = permissions(role = ProfileRole.Admin, canUpdate = true, canDelete = true, canUpload = true)

        assertEquals(ManagementBlock.Capability, root.blockOn(ManagementAction.DeleteSourceFiles))
    }

    /**
     * PRODUCT_SPEC MGR-005 — "offline invocation is blocked".
     *
     * And blocked *is* the word: the account has the permission, so the reason must say so. A user who is
     * told they lack a permission they hold goes looking for an administrator instead of for signal.
     */
    @Test
    fun `an offline device blocks what it would otherwise allow`() {
        val offline = permissions(canUpdate = true, canDelete = true, isOnline = false)

        assertEquals(ManagementBlock.Offline, offline.blockOn(ManagementAction.EditMetadata))
        assertEquals(ManagementBlock.Offline, offline.blockOn(ManagementAction.RemoveFromDatabase))
    }

    /**
     * The ordering inside a verdict, which is the part that is easy to get backwards.
     *
     * A missing permission outranks being offline, because the permission is missing on the train too.
     */
    @Test
    fun `a missing permission is reported even when the device is also offline`() {
        val offline = permissions(isOnline = false)

        assertEquals(ManagementBlock.Permission, offline.blockOn(ManagementAction.EditMetadata))
    }

    /** A handshake that never ran leaves no capabilities, which SYNC-001 requires to mean unsupported. */
    @Test
    fun `a server with no handshake confirms nothing`() {
        val unknown = permissions(canUpdate = true, capabilities = null)

        assertEquals(ManagementBlock.Capability, unknown.blockOn(ManagementAction.MatchMetadata))
        assertNull(unknown.blockOn(ManagementAction.EditMetadata))
    }

    private fun permissions(
        role: ProfileRole = ProfileRole.Listener,
        canUpdate: Boolean = false,
        canDelete: Boolean = false,
        canUpload: Boolean = false,
        confirmed: Set<ServerCapability> = emptySet(),
        capabilities: ServerCapabilities? = ServerCapabilities(
            serverId = ServerId("srv_1"),
            serverVersion = "2.36.0",
            supported = confirmed,
        ),
        isOnline: Boolean = true,
    ) = ManagementPermissions(
        profileRole = role,
        canUpdate = canUpdate,
        canDelete = canDelete,
        canUpload = canUpload,
        capabilities = capabilities,
        isOnline = isOnline,
    )
}
