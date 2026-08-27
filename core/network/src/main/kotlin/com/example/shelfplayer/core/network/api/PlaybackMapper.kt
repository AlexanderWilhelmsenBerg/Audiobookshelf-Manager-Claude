package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.ListeningSession
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Turns an open session into the `:core:model` type the player consumes.
 *
 * ### The one thing this mapper does that the others do not
 *
 * It resolves URLs. The server sends `contentUrl` as a **server-relative path** —
 * `/api/items/{itemId}/file/{ino}` — and `coverPath` as a path on the server's *filesystem*, which is
 * not an address at all. Both are turned into absolute URLs here, against the base URL of the
 * connection that opened the session, because that is the only place both facts are in scope. A
 * relative path escaping this boundary is a path waiting to be resolved against the wrong server
 * (PRODUCT_SPEC 5.2).
 *
 * The whole of `contentUrl` is kept, appended to the base URL, rather than rebuilt from its last
 * segment. Rebuilding would bake today's route shape into the app; appending survives the server moving
 * the route, and costs nothing (PRODUCT_SPEC 22.4).
 *
 * ### What counts as missing
 *
 * Narrow, as in [LibraryMapper]: a session with no id cannot be synced or closed, and one with no
 * tracks cannot be played. Everything else has a defensible fallback — an untitled book still plays.
 */
internal object PlaybackMapper {

    fun toSession(
        profileId: ProfileId,
        serverId: ServerId,
        serverUrl: String,
        bookId: LibraryItemId,
        dto: PlaybackSessionDto,
    ): AppResult<PlaybackSession> {
        val id = dto.id?.takeIf(String::isNotBlank)
            ?: return AppResult.Failure(missingField("id"))
        val base = serverUrl.trimEnd('/')
        val tracks = dto.audioTracks.map { track ->
            val path = track.contentUrl?.takeIf(String::isNotBlank)
                ?: return AppResult.Failure(missingField("audioTracks[].contentUrl"))
            PlayableTrack(
                index = track.index,
                url = base + path.prefixedWithSlash(),
                startOffset = seconds(track.startOffset),
                duration = seconds(track.duration),
                mimeType = track.mimeType?.takeIf(String::isNotBlank),
                isExcluded = track.exclude,
            )
        }
        if (tracks.none { !it.isExcluded }) {
            return AppResult.Failure(missingField("audioTracks"))
        }
        return AppResult.Success(
            PlaybackSession(
                id = id,
                // PRODUCT_SPEC 6.5 — the account this session was opened as, carried by the session itself
                // so nothing downstream has to ask who is signed in *now*.
                profileId = profileId,
                bookId = bookId,
                title = dto.displayTitle?.takeIf(String::isNotBlank) ?: bookId.value,
                author = dto.displayAuthor?.takeIf(String::isNotBlank),
                // `coverPath` is a path on the server's filesystem and is only ever a *flag* that a
                // cover exists — the same rule `CoverUrls` records for the browse screens. The address
                // is the item's cover endpoint, which is what the notification loads artwork from.
                coverUrl = dto.coverPath?.takeIf(String::isNotBlank)
                    ?.let { "$base/api/items/${bookId.value}/cover" },
                startAt = seconds(dto.startTime),
                duration = seconds(dto.duration),
                tracks = tracks,
                chapters = dto.chapters.mapIndexed { index, chapter ->
                    chapterOf(serverId, bookId, index, chapter)
                },
            ),
        )
    }

    private fun chapterOf(serverId: ServerId, bookId: LibraryItemId, index: Int, dto: ChapterDto) = Chapter(
        serverId = serverId,
        bookId = bookId,
        index = index,
        title = dto.title.orEmpty(),
        start = seconds(dto.start),
        end = seconds(dto.end),
    )

    /** Both fixtures send a leading slash; a server that stopped would otherwise produce `hostapi/…`. */
    private fun String.prefixedWithSlash(): String = if (startsWith("/")) this else "/$this"

    /**
     * Seconds to a [Duration], rounded to the nearest millisecond — see `LibraryMapper.seconds`.
     *
     * `internal` rather than private because [ListeningSessionMapper] converts the same units from the same
     * server and must round them the same way. One function, so a session's position and a history row's
     * position cannot disagree by a millisecond for no reason.
     */
    internal fun seconds(value: Double): Duration =
        if (value.isFinite() && value > 0) (value * MILLIS_PER_SECOND).roundToLong().milliseconds else Duration.ZERO

    private fun missingField(field: String): AppError = AppError.ApiCompatibility(
        summary = "This server's playback response is missing information this app needs.",
        missingField = field,
    )

    private const val MILLIS_PER_SECOND = 1_000
}

/**
 * PRODUCT_SPEC PLAY-003 / 14.5 — `GET /api/me/listening-sessions` into the domain.
 *
 * ### What is dropped, and why each one is a decision
 *
 * **`deviceInfo.ipAddress`** is never modelled at all, so it cannot reach here — see
 * [ListeningSessionDeviceDto]. **`chapters`, `mediaMetadata`, `coverPath`** are in the response and dropped:
 * the app already holds them for a book it knows, and a history row is the wrong source for a book it does
 * not.
 *
 * **A session with no id or no `libraryItemId` is dropped rather than failed.** Unlike a playback session —
 * where `PlaybackMapper.toSession` returns `AppError.ApiCompatibility`, because a session that cannot be
 * synced must not be played — this is a *history read*. One malformed row out of ten should show the other
 * nine, not turn the pane into an error. The id is required because it is what makes importing idempotent,
 * and the item id because there is nothing to attach the row to without it.
 */
internal object ListeningSessionMapper {

    fun toSessions(dto: ListeningSessionsResponseDto): List<ListeningSession> =
        dto.sessions.orEmpty().mapNotNull(::toSession)

    private fun toSession(dto: ListeningSessionDto): ListeningSession? {
        val id = dto.id?.takeIf(String::isNotBlank) ?: return null
        val bookId = dto.libraryItemId?.takeIf(String::isNotBlank) ?: return null
        return ListeningSession(
            id = id,
            bookId = LibraryItemId(bookId),
            deviceId = dto.deviceInfo?.deviceId?.takeIf(String::isNotBlank),
            deviceName = dto.deviceInfo?.deviceName?.takeIf(String::isNotBlank),
            clientName = dto.deviceInfo?.clientName?.takeIf(String::isNotBlank),
            // Seconds on the wire, all three of them. Absent reads as zero rather than failing: a session
            // the server sent without a `timeListening` is filtered out downstream for having listened to
            // nothing, which is the same outcome and one fewer error path (SYNC-001).
            listened = PlaybackMapper.seconds(dto.timeListening ?: 0.0),
            startedFrom = PlaybackMapper.seconds(dto.startTime ?: 0.0),
            reachedAt = PlaybackMapper.seconds(dto.currentTime ?: 0.0),
            // Epoch **milliseconds**, unlike the three above. Getting this pair backwards puts a history row
            // in 1970 or claims a four-second session lasted an hour; the capture is what settled it.
            startedAt = Instant.ofEpochMilli(dto.startedAt ?: dto.updatedAt ?: 0L),
        )
    }
}
