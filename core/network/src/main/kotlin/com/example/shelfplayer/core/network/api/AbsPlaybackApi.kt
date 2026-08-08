package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.network.gateway.PlaybackDevice
import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 — the real [PlaybackApi], against `item-play.json` and `multi-item-play.json`.
 *
 * ### Not retried
 *
 * Every other read in this module goes through [com.example.shelfplayer.core.network.http.RetryPolicy].
 * This one does not, and the difference is deliberate: opening a session is a **write**. The server
 * creates a session row and stamps it with this device, so a retried open produces two sessions for one
 * tap rather than one answer twice. A failure here is reported to the caller, which is holding a user
 * who just pressed play and would rather see an error than a silent five-second wait.
 *
 * ### What is not logged
 *
 * The book id, the title, the author and every track URL. PRODUCT_SPEC 14.5 keeps private self-hosted
 * data out of logs, and a track URL contains the book's path on someone's server. Counts and durations
 * are safe and are what a support report actually needs.
 */
@Singleton
internal class AbsPlaybackApi @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val connections: ProfileConnectionResolver,
    private val identity: PlaybackDeviceIdentity,
    private val errors: NetworkErrorMapper,
    private val logger: Logger,
) : PlaybackApi {

    override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        // `resultOf` is the single exception boundary (ADR-0003); it wraps the transport, and everything
        // after it works on a value.
        val transported = resultOf(onError = errors::fromThrowable) {
            services.playbackService(connection.serverUrl).play(
                bearer = bearerOf(connection.accessToken.value),
                itemId = bookId.value,
                request = requestFor(identity.describe()),
            )
        }
        return when (transported) {
            is AppResult.Failure -> AppResult.Failure(transported.error)
            is AppResult.Success -> sessionFrom(connection, bookId, transported.value).also(::logOpened)
        }
    }

    private fun sessionFrom(
        connection: ProfileConnection,
        bookId: LibraryItemId,
        response: Response<PlaybackSessionDto>,
    ): AppResult<PlaybackSession> {
        if (!response.isSuccessful) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            return AppResult.Failure(errors.fromStatus(response.code(), retryAfterSeconds = retryAfter))
        }
        val body = response.body() ?: return AppResult.Failure(
            AppError.ApiCompatibility(
                summary = "This server answered a play request with no session.",
                missingField = "body",
            ),
        )
        return PlaybackMapper.toSession(connection.serverId, connection.serverUrl, bookId, body)
    }

    private fun logOpened(session: AppResult<PlaybackSession>) {
        if (session !is AppResult.Success) return
        logger.info(
            LogCategory.Playback,
            "Opened a playback session",
            LogField.Count("tracks", session.value.playableTracks.size),
            LogField.Count("chapters", session.value.chapters.size),
            LogField.Millis("startAt", session.value.startAt.inWholeMilliseconds),
        )
    }

    override suspend fun syncSession(
        profileId: ProfileId,
        sessionId: String,
        progress: SessionProgress,
    ): AppResult<Unit> = send(profileId, "syncSession") { service, bearer ->
        service.sync(bearer, sessionId, progress.toRequest())
    }

    override suspend fun closeSession(
        profileId: ProfileId,
        sessionId: String,
        progress: SessionProgress,
    ): AppResult<Unit> = send(profileId, "closeSession") { service, bearer ->
        service.close(bearer, sessionId, progress.toRequest())
    }

    override suspend fun syncOfflineSessions(
        profileId: ProfileId,
        sessions: List<OfflineSession>,
    ): AppResult<List<OfflineSessionResult>> {
        // An empty drain is a success with nothing in it, not a request. A queue that emptied between
        // being read and being sent must not produce a call the server has to answer.
        if (sessions.isEmpty()) return AppResult.Success(emptyList())
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        val transported = resultOf(onError = errors::fromThrowable) {
            val device = identity.describe()
            services.playbackService(connection.serverUrl).syncLocalSessions(
                bearer = bearerOf(connection.accessToken.value),
                request = LocalSessionBatchDto(sessions.map { session -> session.toDto(device) }),
            )
        }
        return when (transported) {
            is AppResult.Failure -> AppResult.Failure(transported.error)
            is AppResult.Success -> resultsFrom(transported.value, sent = sessions.size)
        }
    }

    private fun resultsFrom(
        response: Response<LocalSessionBatchResponseDto>,
        sent: Int,
    ): AppResult<List<OfflineSessionResult>> {
        if (!response.isSuccessful) return AppResult.Failure(errors.fromStatus(response.code()))
        // A result with no id cannot be matched to a queued session, so it is dropped rather than guessed at.
        // The row it belonged to stays queued, which is the safe direction: the drain sends it again.
        val results = response.body()?.results.orEmpty().mapNotNull { result ->
            val id = result.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            OfflineSessionResult(
                id = id,
                wasAccepted = result.success,
                wasProgressApplied = result.progressSynced,
            )
        }
        logger.info(
            LogCategory.Playback,
            "Uploaded sessions recorded on this device",
            LogField.Count("sent", sent),
            LogField.Count("accepted", results.count { it.wasAccepted }),
            // Not a failure. A session older than what the server holds is accepted with its progress
            // declined, which is PLAY-004's conflict rule working — see `LocalSessionResultDto`.
            LogField.Count("progressDeclined", results.count { it.wasAccepted && !it.wasProgressApplied }),
        )
        return AppResult.Success(results)
    }

    /**
     * The two routes that answer `200` with `OK` and nothing else.
     *
     * Shared because they are the same call with a different path, and because the one thing worth
     * getting right — that a `text/plain` body is not treated as a failed JSON parse — should be written
     * once. `Response<Unit>` is what makes that true: Retrofit does not attempt to convert the body.
     */
    private suspend fun send(
        profileId: ProfileId,
        operation: String,
        call: suspend (PlaybackService, String) -> Response<Unit>,
    ): AppResult<Unit> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        val transported = resultOf(onError = errors::fromThrowable) {
            call(services.playbackService(connection.serverUrl), bearerOf(connection.accessToken.value))
        }
        val response = when (transported) {
            is AppResult.Failure -> return AppResult.Failure(transported.error)
            is AppResult.Success -> transported.value
        }
        if (!response.isSuccessful) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            return AppResult.Failure(errors.fromStatus(response.code(), retryAfterSeconds = retryAfter))
        }
        logger.debug(LogCategory.Playback, "Session progress accepted", LogField.Public("call", operation))
        return AppResult.Success(Unit)
    }

    /** Seconds on the wire, as the capture sent them. `Duration` everywhere else. */
    private fun SessionProgress.toRequest() = SessionSyncRequestDto(
        currentTime = position.inWholeMilliseconds / MILLIS_PER_SECOND,
        timeListened = timeListened.inWholeSeconds,
        duration = duration.inWholeMilliseconds / MILLIS_PER_SECOND,
    )

    private fun OfflineSession.toDto(device: PlaybackDevice) = LocalSessionDto(
        id = id,
        libraryItemId = bookId.value,
        mediaItemId = bookId.value,
        // The capture sent `book`, and this app plays books. A podcast episode is a different shape and
        // a different route (PRODUCT_SPEC 22.4).
        mediaItemType = MEDIA_ITEM_TYPE_BOOK,
        displayTitle = title,
        displayAuthor = author,
        duration = progress.duration.inWholeMilliseconds / MILLIS_PER_SECOND,
        currentTime = progress.position.inWholeMilliseconds / MILLIS_PER_SECOND,
        timeListening = progress.timeListened.inWholeSeconds,
        startedAt = startedAt.toEpochMilli(),
        // The honest moment the position was recorded. The server resolves conflicts on this, so a value
        // rounded, defaulted or taken from "now at upload time" would let a stale session win.
        updatedAt = updatedAt.toEpochMilli(),
        mediaPlayer = MEDIA_PLAYER,
        deviceInfo = PlayDeviceInfoDto(
            clientName = device.clientName,
            clientVersion = device.clientVersion,
            deviceId = device.deviceId,
            manufacturer = device.manufacturer,
            model = device.model,
            sdkVersion = device.sdkVersion,
        ),
    )

    /**
     * The request the fixtures were captured with, field for field.
     *
     * [SUPPORTED_MIME_TYPES] is what makes the server direct-play rather than transcode. It lists what
     * ExoPlayer decodes on every supported device without a codec extension: MP3, the MP4 family (AAC
     * and ALAC in an M4A/M4B container, which is what most audiobooks are), and FLAC. Opus and Vorbis
     * are left off — ExoPlayer handles both, but no capture has confirmed how this server answers a
     * request that advertises them, and PRODUCT_SPEC 22.5 does not let the app find out in the field.
     */
    private fun requestFor(device: PlaybackDevice) = PlaySessionRequestDto(
        deviceInfo = PlayDeviceInfoDto(
            clientName = device.clientName,
            clientVersion = device.clientVersion,
            deviceId = device.deviceId,
            manufacturer = device.manufacturer,
            model = device.model,
            sdkVersion = device.sdkVersion,
        ),
        supportedMimeTypes = SUPPORTED_MIME_TYPES,
        mediaPlayer = MEDIA_PLAYER,
        forceDirectPlay = false,
        forceTranscode = false,
    )

    private companion object {
        val SUPPORTED_MIME_TYPES = listOf("audio/mpeg", "audio/mp4", "audio/flac")

        /** What the server records as the player; the official app and the capture both send this. */
        const val MEDIA_PLAYER = "exo-player"

        const val MEDIA_ITEM_TYPE_BOOK = "book"

        /** The wire is seconds and the models are `Duration`; this is the only place the two meet. */
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
