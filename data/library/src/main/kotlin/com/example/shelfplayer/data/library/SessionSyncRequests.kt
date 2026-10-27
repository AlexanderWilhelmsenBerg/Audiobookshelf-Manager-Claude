package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * AUTH-004 network boundary for durable session writes. The repository owns outbox storage and
 * acknowledgement; this owner retries the identical profile-scoped operation after at most one
 * renewal through the shared AuthRepository. It never selects an ambient profile or clears data.
 */
class SessionSyncRequests @Inject constructor(
    private val gateway: AudiobookshelfGateway,
    private val auth: AuthRepository,
) {
    suspend fun sync(profileId: ProfileId, sessionId: String, progress: SessionProgress): AppResult<Unit> =
        sendWithRenewal(profileId) { gateway.playback.syncSession(profileId, sessionId, progress) }

    suspend fun close(profileId: ProfileId, sessionId: String, progress: SessionProgress): AppResult<Unit> =
        sendWithRenewal(profileId) { gateway.playback.closeSession(profileId, sessionId, progress) }

    suspend fun upload(profileId: ProfileId, sessions: List<OfflineSession>): AppResult<List<OfflineSessionResult>> =
        sendWithRenewal(profileId) { gateway.playback.syncOfflineSessions(profileId, sessions) }

    private suspend fun <T> sendWithRenewal(profileId: ProfileId, request: suspend () -> AppResult<T>): AppResult<T> {
        val first = request()
        if (first !is AppResult.Failure || first.error !is AppError.Authentication) return first
        val renewed = auth.renewSession(profileId)
        if (renewed !is AppResult.Success || renewed.value != SessionStatus.Active) return first
        val retried = request()
        if (retried is AppResult.Failure && retried.error is AppError.Authentication) {
            auth.requireReauthentication(profileId)
        }
        return retried
    }
}
