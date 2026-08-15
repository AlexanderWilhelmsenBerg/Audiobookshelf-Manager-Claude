package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.NewServerUser
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerUser
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.ServerUserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC EPIC USER — server accounts, read fresh and never stored.
 *
 * ### The permission is checked here as well as by the server
 *
 * PRODUCT_SPEC principle 4 enforces permissions twice, and this is the second place. The server refuses a
 * non-admin with a `403`, and that would be enough to keep the data safe — but a screen that opens, spins
 * and then says "forbidden" is worse than one that never offers itself, and an administrator's account list
 * is the last screen anybody should have to probe for.
 *
 * The check is on the account **type** rather than on any grant, matching what the server actually gates
 * on: an account with every permission set is still refused if its type is `user`.
 */
@Singleton
class DefaultServerUserRepository @Inject constructor(
    private val gateway: AudiobookshelfGateway,
    private val profiles: ProfileRepository,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : ServerUserRepository {

    override suspend fun list(): AppResult<List<ServerUser>> = withAdmin { profileId ->
        gateway.management.listUsers(profileId)
    }

    override suspend fun create(user: NewServerUser): AppResult<ServerUser> = withAdmin { profileId ->
        gateway.management.createUser(profileId, user)
    }

    override suspend fun setActive(userId: String, isActive: Boolean): AppResult<Unit> = withAdmin { profileId ->
        gateway.management.setUserActive(profileId, userId, isActive)
    }

    private suspend fun <T> withAdmin(block: suspend (ProfileId) -> AppResult<T>): AppResult<T> =
        withContext(ioDispatcher) {
            val profile = profiles.observeActiveProfile().first()
                ?: return@withContext AppError.Authentication(summary = "Sign in to a server first.").asFailure()
            if (profile.role != ProfileRole.Admin) {
                return@withContext AppError.Authorization(
                    summary = "Only an administrator can manage accounts on this server.",
                    missingPermission = "user.manage",
                ).asFailure()
            }
            block(profile.id)
        }
}
