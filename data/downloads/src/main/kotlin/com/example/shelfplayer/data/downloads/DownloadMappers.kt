package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.database.dao.DownloadedBookWithFiles
import com.example.shelfplayer.core.database.entity.DownloadedFileEntity
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC 9.4 — entities in, domain models out. Nothing from Room leaves this module.
 */
internal object DownloadMappers {

    fun toDomain(row: DownloadedBookWithFiles): OfflineBook = OfflineBook(
        serverId = ServerId(row.book.serverId),
        itemId = LibraryItemId(row.book.remoteItemId),
        state = stateOf(row.book.state),
        files = row.files.sortedBy(DownloadedFileEntity::fileIndex).map(::toDomain),
        coverUri = row.book.coverUri,
        requestedBy = row.requests.map { request -> ProfileId(request.profileId) }.toSet(),
        createdAt = Instant.ofEpochMilli(row.book.createdAt),
        updatedAt = Instant.ofEpochMilli(row.book.updatedAt),
    )

    fun toEntity(bookKey: String, file: OfflineFile): DownloadedFileEntity = DownloadedFileEntity(
        bookKey = bookKey,
        remoteFileId = file.remoteFileId,
        fileIndex = file.index,
        uri = file.uri,
        state = file.state.name,
        expectedBytes = file.expectedBytes,
        downloadedBytes = file.downloadedBytes,
        mimeType = file.mimeType,
        durationMillis = file.duration?.inWholeMilliseconds,
        eTag = file.eTag,
        lastModified = file.lastModified,
    )

    private fun toDomain(entity: DownloadedFileEntity): OfflineFile = OfflineFile(
        remoteFileId = entity.remoteFileId,
        index = entity.fileIndex,
        uri = entity.uri,
        state = stateOf(entity.state),
        expectedBytes = entity.expectedBytes,
        downloadedBytes = entity.downloadedBytes,
        mimeType = entity.mimeType,
        duration = entity.durationMillis?.milliseconds,
        eTag = entity.eTag,
        lastModified = entity.lastModified,
    )

    /**
     * PRODUCT_SPEC SYNC-001 — an unrecognized state reads back as [DownloadState.Failed].
     *
     * The same "tolerate the unknown" rule the rest of the app follows, resolved in the direction that
     * cannot hurt: a row written by a future version whose state this build does not know is *not* treated
     * as complete, so nothing tries to play files it cannot vouch for. A downgrade then shows a failed
     * download that can be retried, which is recoverable; the opposite is a book that stops mid-chapter.
     */
    private fun stateOf(stored: String): DownloadState =
        DownloadState.entries.firstOrNull { it.name == stored } ?: DownloadState.Failed
}
