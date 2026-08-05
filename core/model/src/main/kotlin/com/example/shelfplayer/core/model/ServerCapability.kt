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
 * A resolved capability set for one server.
 *
 * @property serverVersion the version string the server reported, or `null` when it was not
 *   readable. It is only used to select known workarounds; feature probes decide behavior
 *   (PRODUCT_SPEC 10.4).
 */
data class ServerCapabilities(
    val serverId: ServerId,
    val serverVersion: String?,
    val supported: Set<ServerCapability>,
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
