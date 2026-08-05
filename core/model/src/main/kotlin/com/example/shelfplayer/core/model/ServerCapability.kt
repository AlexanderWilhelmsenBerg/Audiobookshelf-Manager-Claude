package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC SYNC-001 — the capability handshake.
 *
 * An unknown capability is *unsupported*, never assumed supported. [ServerCapabilities.supports]
 * therefore defaults to `false` for anything the probe did not explicitly confirm.
 */
enum class ServerCapability {
    LocalSessionSync,
    MetadataUpdate,
    CoverUpload,
    MatchProvider,
    ScanItem,
    ScanLibrary,
    UserManagement,
    RemoveFromDatabase,

    /**
     * PRODUCT_SPEC MGR-006 — true source-file deletion.
     *
     * This is deliberately separate from [RemoveFromDatabase]: the documented database-delete
     * operation does not delete media files, and the app must never present it as if it did.
     */
    SourceFileDelete,
    RangeDownload,
    Websocket,
    PlaybackSession,
    ChecksumOrETag,
}

/**
 * A resolved capability set for one server (PRODUCT_SPEC SYNC-001).
 *
 * @property serverVersion the version string the server reported, or `null` when it was not
 *   readable. It is only used to select known workarounds; feature probes decide behavior
 *   (PRODUCT_SPEC 10.4).
 * @property authMethods the authentication modes the server offers, e.g. `["local"]`. PRODUCT_SPEC
 *   SYNC-001 lists the authentication mode among the things a handshake must persist, and it belongs
 *   here rather than beside it: it is read from the same response, at the same moment, for the same
 *   server.
 * @property supported only the capabilities a probe **confirmed**. A capability that is absent was not
 *   confirmed, which PRODUCT_SPEC SYNC-001 requires to mean unsupported — the set carries no third
 *   "unknown" state, because a third state is what invites code to guess.
 */
data class ServerCapabilities(
    val serverId: ServerId,
    val serverVersion: String?,
    val supported: Set<ServerCapability>,
    val authMethods: List<String> = emptyList(),
) {
    fun supports(capability: ServerCapability): Boolean = capability in supported

    companion object {
        /** Nothing is supported until a probe says otherwise (PRODUCT_SPEC SYNC-001). */
        fun unknown(serverId: ServerId): ServerCapabilities = ServerCapabilities(
            serverId = serverId,
            serverVersion = null,
            supported = emptySet(),
        )
    }
}
