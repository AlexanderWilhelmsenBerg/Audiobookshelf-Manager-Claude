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
     *
     * **No probe will ever confirm this, and that is a decision rather than an omission (ADR-0021).**
     * The server does have the operation — `DELETE /api/items/{id}?hard=1` removes the item's directory,
     * and `DELETE /api/items/{id}/file/{ino}` removes one file. What it does not have is an
     * acknowledgement: a filesystem removal that fails is logged on the server and discarded, and the
     * request succeeds anyway. MGR-006 requires the response to confirm the deletion, and no response
     * this server sends can. The entry stays so the gate has something to check and so a future server
     * that does confirm needs a probe rather than a new concept.
     */
    SourceFileDelete,

    /**
     * PRODUCT_SPEC DL-001 — the server honours a `Range` request, so an interrupted download resumes.
     *
     * One of the two entries no probe can answer: the only honest way to find out is to ask for a range
     * and see whether `206` or `200` comes back. It is therefore **observed**, not resolved — see
     * [ObservedOnly].
     */
    RangeDownload,
    Websocket,
    PlaybackSession,

    /**
     * PRODUCT_SPEC DL-002 — the server sends a validator with a file, so a stale copy can be detected.
     *
     * Named for what it is rather than for what it would be convenient to be. `contracts/item-file.json`
     * records an `ETag` and a `Last-Modified`; neither is required to be derived from the bytes, so this
     * confirms *staleness detection* and never *integrity* — which is exactly what limits what the
     * storage screen's check may claim (ADR-0018).
     */
    ChecksumOrETag,
    ;

    companion object {
        /**
         * The capabilities that are learned by doing, and that a handshake must therefore never clear.
         *
         * `/status` says nothing about ranges or validators — `AbsCapabilityResolver` deliberately returns
         * an empty set rather than guessing — so these two arrive from the download path, once a real file
         * has been fetched. A handshake that overwrote the stored set with the probe's would erase them
         * every time the app reconnected, and the diagnostics screen would forget what the device had
         * already proved.
         */
        val ObservedOnly: Set<ServerCapability> = setOf(RangeDownload, ChecksumOrETag)
    }
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
