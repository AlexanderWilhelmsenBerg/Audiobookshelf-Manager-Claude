package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.AudioTrackEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.download.BookAssets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC DL-001 / 5.2 — the catalogue's answer to "what is this book made of".
 *
 * ### The visibility check is the point of doing this here
 *
 * `LibraryDao.observeBook` joins onto `profile_visible_books`, so a book this profile may not see produces
 * no row and this returns a failure. Downloading is a *read* of the library, and 5.2 applies to it exactly
 * as it applies to a screen — a profile that cannot see a book must not be able to pull its audio onto the
 * device by asking for it directly.
 *
 * ### Excluded tracks are absent, not skipped
 *
 * PLAY-003 says an excluded track is not played, and `PlaybackSession.playableTracks` already drops them
 * from the timeline. Downloading one would spend a listener's data on bytes nothing will ever open, and —
 * worse — a book whose file count included them could never reach `Complete`, because the manifest counts
 * files and the player would only ever be given the rest.
 */
@Singleton
class DefaultBookAssetSource @Inject constructor(
    private val libraryDao: LibraryDao,
    private val profileDao: ProfileDao,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : BookAssetSource {

    override suspend fun assetsFor(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookAssets> =
        withContext(ioDispatcher) {
            val profile = profileDao.findProfile(profileId.value)
                ?: return@withContext AppResult.Failure(
                    AppError.Authentication(summary = "That profile is no longer on this device."),
                )
            val stored = libraryDao.observeBook(profileId.value, EntityKey.of(profile.serverId, bookId.value)).first()
                ?: return@withContext AppResult.Failure(
                    AppError.Authorization(summary = "This book is not in your library."),
                )

            val tracks = stored.tracks.filterNot(AudioTrackEntity::isExcluded).sortedBy(AudioTrackEntity::trackIndex)
            AppResult.Success(
                BookAssets(
                    files = tracks.mapIndexed { index, track -> track.toPlannedFile(index) },
                    coverUrl = stored.book.coverPath,
                    estimatedBytes = tracks.sumOf { track -> track.sizeBytes.coerceAtLeast(0) },
                ),
            )
        }

    /**
     * One track as a file that has not been fetched yet.
     *
     * [OfflineFile.index] is the position among the **downloadable** tracks rather than
     * [AudioTrackEntity.trackIndex]. The two differ whenever a book has an excluded track, and the manifest's
     * order has to be the order the files are played in — a gap in the sequence would put the storage screen
     * and any future ordering check permanently out of step with reality.
     *
     * `uri` is empty: where a file lives is the storage layer's decision, filled in when it is committed.
     * `expectedBytes` is the scan's size, which the transfer will replace with the server's own
     * `Content-Length` — an estimate is enough to weight a progress bar and to check free space.
     */
    private fun AudioTrackEntity.toPlannedFile(index: Int) = OfflineFile(
        remoteFileId = remoteFileId,
        index = index,
        uri = "",
        state = DownloadState.Queued,
        expectedBytes = sizeBytes.takeIf { it > 0 },
        downloadedBytes = 0,
        mimeType = mimeType,
        duration = durationMillis.coerceAtLeast(0).milliseconds,
        eTag = null,
        lastModified = null,
    )
}
