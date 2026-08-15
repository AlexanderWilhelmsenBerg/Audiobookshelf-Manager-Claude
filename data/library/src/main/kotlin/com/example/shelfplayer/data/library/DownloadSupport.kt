package com.example.shelfplayer.data.library

import com.example.shelfplayer.domain.download.SmartDownload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-001 / DL-005 — what the playback layer needs from downloads, as one collaborator.
 *
 * A bundle rather than two constructor parameters, for the reason detekt's limit exists: the playback
 * repository reached eleven when the smart-download trigger arrived, and a constructor with eleven is one
 * somebody will eventually get wrong. The grouping is honest — both are "what the download subsystem
 * contributes to playing a book": where the bytes are, and what to fetch next.
 */
@Singleton
class DownloadSupport @Inject constructor(
    /** Local URIs for a session, and the session itself when the server cannot be reached. */
    val sessions: OfflineSessionBuilder,
    /** PRODUCT_SPEC DL-005 — the halfway trigger, considered on every journaled position. */
    val smartDownload: SmartDownload,
)
