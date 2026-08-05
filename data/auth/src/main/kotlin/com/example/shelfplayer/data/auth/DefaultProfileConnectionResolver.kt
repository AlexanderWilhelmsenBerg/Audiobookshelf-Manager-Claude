package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 5.2 — resolves a profile to the address, credential and grant a request needs.
 *
 * Lives in `:data:auth` because it reads the profile row and the encrypted token, and `:core:network`
 * names neither. This is the third instance of the pattern `docs/architecture/module-boundaries.md`
 * records, after `DatabaseTransactionRunner` and `TokenProvider`.
 *
 * The token is read from storage rather than from the in-memory cache. That is what makes a sync for a
 * profile that is *not* the active one correct rather than silently signed with the wrong credential —
 * the cache holds one profile's token, and a request that names another must not receive it.
 */
@Singleton
class DefaultProfileConnectionResolver @Inject constructor(
    private val profileDao: ProfileDao,
    private val sessionTokens: SessionTokenProvider,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : ProfileConnectionResolver {

    override suspend fun connectionFor(profileId: ProfileId): ProfileConnection? = withContext(ioDispatcher) {
        val profile = profileDao.findProfile(profileId.value) ?: return@withContext null
        val server = profileDao.findServer(profile.serverId) ?: return@withContext null
        // A profile marked for reauthentication is not refused here. Its token may still be valid — the
        // mark can outlive the failure that set it — and AUTH-004 requires the `401` path to run and
        // attempt a renewal rather than being short-circuited into "no connection".
        val token = sessionTokens.accessTokenFor(profileId) ?: return@withContext null

        ProfileConnection(
            profileId = profileId,
            serverId = ServerId(server.serverId),
            serverUrl = server.baseUrl,
            accessToken = token,
            access = AuthEntityMappers.toLibraryAccess(profile),
        )
    }
}
