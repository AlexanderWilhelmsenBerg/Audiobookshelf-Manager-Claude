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
import com.example.shelfplayer.core.model.playback.ListeningSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.ServerProgress
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
import kotlin.time.Duration

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
            is AppResult.Success -> sessionFrom(profileId, connection, bookId, transported.value).also(::logOpened)
        }
    }

    private fun sessionFrom(
        profileId: ProfileId,
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
        return PlaybackMapper.toSession(profileId, connection.serverId, connection.serverUrl, bookId, body)
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

    /**
     * PRODUCT_SPEC PLAY-004 — the explicit finished flag, on the captured progress route.
     *
     * Goes through the same [send] helper as the session calls, so the failure mapping, the `Retry-After`
     * handling and the redaction are one implementation rather than three. The book id is **not** logged
     * (14.5); the flag is, because "the user marked something finished and the server refused" is a support
     * report that needs the direction to be readable.
     */
    override suspend fun setFinished(
        profileId: ProfileId,
        bookId: LibraryItemId,
        isFinished: Boolean,
        position: Duration,
    ): AppResult<Unit> = send(profileId, if (isFinished) "markFinished" else "markUnfinished") { service, bearer ->
        service.updateProgress(
            bearer = bearer,
            itemId = bookId.value,
            request = MediaProgressUpdateDto(
                currentTime = position.inWholeMilliseconds.coerceAtLeast(0) / MILLIS_PER_SECOND,
                isFinished = isFinished,
            ),
        )
    }

    /**
     * PRODUCT_SPEC SYNC-002 — the server's stored position for one book.
     *
     * **Retried like the other read in this class and unlike the writes**, for the same reason
     * [listeningSessions] is: a retried read cannot produce a second session for one tap. It reaches the
     * retry through `AudiobookshelfServiceFactory`.
     *
     * Nothing about the book or the position is logged. The book id is private self-hosted data (14.5) and
     * a position is what somebody is listening to; the caller records the *outcome* of the comparison in
     * the book's own history, which is where it belongs and where it is not a shared log.
     */
    override suspend fun serverProgress(profileId: ProfileId, bookId: LibraryItemId): AppResult<ServerProgress> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        val transported = resultOf(onError = errors::fromThrowable) {
            services.playbackService(connection.serverUrl).mediaProgress(
                bearer = bearerOf(connection.accessToken.value),
                itemId = bookId.value,
            )
        }
        return when (transported) {
            is AppResult.Failure -> AppResult.Failure(transported.error)
            is AppResult.Success -> progressFrom(transported.value)
        }
    }

    /**
     * The response half of [serverProgress], split out for the reason [sessionsFrom] is: the transport and
     * the reading of what came back are two different failures.
     *
     * **A non-`200` — a `404` included — is a failure and not "no progress".** What the server answers for a
     * book it has never recorded progress for is unobserved (PRODUCT_SPEC 22.4), and of the two guesses only
     * "could not tell" is safe for a loaded player.
     */
    private fun progressFrom(response: Response<MediaProgressDto>): AppResult<ServerProgress> {
        if (!response.isSuccessful) return AppResult.Failure(errors.fromStatus(response.code()))
        val body = response.body()
            ?: return AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "The server answered the stored-progress read with no body",
                    missingField = "currentTime",
                ),
            )
        return AppResult.Success(PlaybackMapper.toServerProgress(body))
    }

    /**
     * PRODUCT_SPEC PLAY-003 / 14.5 — the account's listening sessions.
     *
     * **Retried, unlike everything else in this class.** The rest of this file is writes — opening a
     * session, syncing one, closing one — where a retry produces a second session for one tap. This is a
     * read, and the caller is a history pane the user just opened; the ordinary transport retry is right
     * here and is what the rest of the module does for reads. It reaches the retry through
     * `AudiobookshelfServiceFactory`, the same as `AbsLibraryApi`'s reads.
     *
     * Logged with a count and nothing else: a session carries a book title and a device name, both private
     * self-hosted data (14.5).
     */
    override suspend fun listeningSessions(
        profileId: ProfileId,
        page: Int,
        itemsPerPage: Int,
    ): AppResult<List<ListeningSession>> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        val transported = resultOf(onError = errors::fromThrowable) {
            services.playbackService(connection.serverUrl).listeningSessions(
                bearer = bearerOf(connection.accessToken.value),
                page = page.coerceAtLeast(0),
                itemsPerPage = itemsPerPage.coerceAtLeast(1),
            )
        }
        return when (transported) {
            is AppResult.Failure -> AppResult.Failure(transported.error)
            is AppResult.Success -> sessionsFrom(transported.value)
        }
    }

    /**
     * The response half of [listeningSessions], split out for the same reason [resultsFrom] is: the
     * transport and the reading of what came back are two different failures, and keeping them in one
     * function makes a ladder of guarded returns out of what is really two steps.
     *
     * Logged with a count and nothing else. A session carries a book title and a device name, both private
     * self-hosted data (14.5).
     */
    private fun sessionsFrom(response: Response<ListeningSessionsResponseDto>): AppResult<List<ListeningSession>> {
        if (!response.isSuccessful) return AppResult.Failure(errors.fromStatus(response.code()))
        val body = response.body()
            ?: return AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "The server answered the listening-sessions read with no body",
                    missingField = "sessions",
                ),
            )
        val sessions = ListeningSessionMapper.toSessions(body)
        logger.debug(
            LogCategory.Playback,
            "Read the account's listening sessions",
            LogField.Count("sessions", sessions.size),
        )
        return AppResult.Success(sessions)
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
