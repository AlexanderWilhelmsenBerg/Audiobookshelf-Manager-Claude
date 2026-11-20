package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.AudioTrackEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.DownloadStorageState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.download.DownloadLocations
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC DL-001 exit criterion 1 / PLAY-005 — a session built entirely from this device.
 *
 * ### Why a downloaded book must not need the server to start
 *
 * `POST /api/items/{id}/play` is how a session normally begins, and it needs a connection. A book that was
 * downloaded specifically so it could be listened to on a plane must not be gated behind the one thing that
 * is unavailable there. Everything the player needs is already local once a download completes — the tracks
 * are on disk, the chapters are in Room, and the resume position is in `media_progress`.
 *
 * ### The session id is blank, and that is a contract
 *
 * `SessionSyncCoordinator` reads `session.id.takeIf(String::isNotBlank)` and files a `null` remote id, which
 * routes the listening through PLAY-005's outbox instead of the live sync. So a blank id here is not a
 * missing value — it is how this layer says *"the server has never heard of this session"*, and it is the
 * reason offline listening is uploaded later rather than lost.
 *
 * ### Local URIs, always, when the file is committed
 *
 * Used for the online case too. A downloaded book streaming over the network while its bytes sit on the
 * device would be the feature failing quietly: the user would see data usage they thought they had avoided,
 * and playback would stall in exactly the places the download was meant to fix.
 */
@Singleton
class OfflineSessionBuilder @Inject constructor(
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val downloads: DownloadRepository,
    private val locations: DownloadLocations,
) {

    /**
     * This device's copy of a book, or `null`.
     *
     * Here rather than injected separately into the playback repository, which already carries ten
     * collaborators. It also keeps the two halves of "is there a local copy" and "what do I do with it" in
     * one object, which is how every caller uses them.
     */
    suspend fun manifestFor(serverId: ServerId, bookId: LibraryItemId): OfflineBook? =
        downloads.observe(serverId, bookId).first()

    /**
     * A session for [bookId] from Room and [manifest], or `null` when a required track cannot play locally.
     *
     * `null` rather than a failure: the caller's next move is the server, and "there is nothing local" is
     * not an error worth reporting to somebody who is about to get a real session anyway.
     */
    suspend fun build(
        profileId: ProfileId,
        serverId: ServerId,
        bookId: LibraryItemId,
        manifest: OfflineBook,
    ): PlaybackSession? {
        if (!manifest.isLocalStorageReachable(locations)) return null
        val bookKey = EntityKey.of(serverId.value, bookId.value)
        val stored = libraryDao.observeBook(profileId.value, bookKey).first() ?: return null
        val tracks = localTracks(stored.tracks, manifest) ?: return null

        val progress = progressDao.findProgress(profileId.value, bookKey)
        return PlaybackSession(
            // Blank on purpose. See the class comment: this is what routes the listening to the outbox.
            id = "",
            // PRODUCT_SPEC 6.5 — an offline session is owned exactly as a server one is. The profile was
            // already an argument here; now it is recorded rather than only used to find the progress row.
            profileId = profileId,
            bookId = bookId,
            title = stored.book.title,
            author = stored.authors.firstOrNull()?.name,
            // The *local* cover where one was fetched. A remote URL here would make an offline player try
            // the network for artwork and show nothing when it failed.
            coverUrl = manifest.coverUri,
            startAt = (progress?.positionMillis ?: 0).coerceAtLeast(0).milliseconds,
            duration = stored.book.durationMillis.coerceAtLeast(0).milliseconds,
            tracks = tracks,
            chapters = stored.chapters.sortedBy { it.chapterIndex }.map { chapter ->
                Chapter(
                    serverId = serverId,
                    bookId = bookId,
                    index = chapter.chapterIndex,
                    title = chapter.title,
                    start = chapter.startMillis.milliseconds,
                    end = chapter.endMillis.milliseconds,
                )
            },
        )
    }

    private fun localTracks(catalogue: List<AudioTrackEntity>, manifest: OfflineBook): List<PlayableTrack>? {
        val local = manifest.files.associateBy { file -> file.remoteFileId }
        val tracks = catalogue.sortedBy(AudioTrackEntity::trackIndex).map { track ->
            if (track.isExcluded) return@map track.toPlayable(url = "")
            // The catalogue can change after the manifest was marked complete. Dropping a required
            // file would also cut that part out of the book's playback timeline.
            val uri = local[track.remoteFileId]?.uri?.takeIf(String::isNotBlank) ?: return null
            track.toPlayable(url = uri)
        }
        return tracks.takeIf { it.any { track -> !track.isExcluded } }
    }

    /**
     * Rewrites a session the **server** opened so its tracks point at local files where they exist.
     *
     * Partial by design: a book halfway through downloading plays its committed files from disk and streams
     * the rest. There is no reason to make a listener wait for the whole book before any of it helps, and the
     * player cannot tell the difference — a `ConcatenatingMediaSource2` is happy to mix a `file://` and an
     * `https://`.
     */
    fun localise(session: PlaybackSession, manifest: OfflineBook?): PlaybackSession {
        if (
            manifest == null ||
            locations.availability(manifest.storageVolumeUuid) == DownloadStorageState.Unavailable
        ) {
            return session
        }
        val local = manifest.files
            .filter { file ->
                file.uri.isNotBlank() &&
                    file.state == DownloadState.Complete &&
                    localDownloadUriReachable(file.uri)
            }
            .associate { file -> file.remoteFileId to file.uri }
        if (local.isEmpty()) return session
        return session.copy(
            tracks = session.tracks.map { track ->
                local[track.remoteFileId()]?.let { uri -> track.copy(url = uri) } ?: track
            },
        )
    }

    /**
     * The server's file id for a track, recovered from the URL it was given.
     *
     * `PlayableTrack` carries a URL rather than a file id, because that is all the player needs — and the
     * stream URL's last path segment *is* the file id: `/api/items/{itemId}/file/{fileId}`. Reading it back
     * here avoids widening the playback model with a field only this one substitution wants.
     *
     * A URL that does not have that shape simply does not match anything local, which is the safe outcome:
     * the track streams.
     */
    private fun PlayableTrack.remoteFileId(): String = url.substringBefore('?').substringAfterLast('/')

    private fun AudioTrackEntity.toPlayable(url: String) = PlayableTrack(
        index = trackIndex,
        url = url,
        startOffset = startOffsetMillis.coerceAtLeast(0).milliseconds,
        duration = durationMillis.coerceAtLeast(0).milliseconds,
        mimeType = mimeType,
        isExcluded = isExcluded,
    )
}

/** #20 pure local-media gate, shared by tests and both playback paths. */
internal fun OfflineBook.isLocalStorageReachable(locations: DownloadLocations): Boolean {
    if (locations.availability(storageVolumeUuid) == DownloadStorageState.Unavailable) return false
    return files
        .filter { it.state == DownloadState.Complete }
        .all { file -> localDownloadUriReachable(file.uri) }
}

internal fun localDownloadUriReachable(uri: String): Boolean = runCatching {
    val parsed = URI(uri)
    parsed.scheme == "file" && File(parsed).isFile
}.getOrDefault(false)
