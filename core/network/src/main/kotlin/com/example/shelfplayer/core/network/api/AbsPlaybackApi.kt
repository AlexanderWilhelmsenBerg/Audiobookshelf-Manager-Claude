package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
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
    }
}
