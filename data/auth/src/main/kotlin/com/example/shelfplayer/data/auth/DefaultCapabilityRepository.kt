package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.CapabilityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SYNC-001 — runs the handshake and stores it on the server row.
 *
 * The stored form holds only the capabilities that were confirmed, so "unknown means unsupported" is a
 * property of the data rather than a rule every reader has to remember. A name this build does not
 * recognise is dropped on the way out, which matters on a downgrade: an app update rolled back must not
 * turn an unreadable name into an enabled feature.
 */
@Singleton
class DefaultCapabilityRepository @Inject constructor(
    private val gateway: AudiobookshelfGateway,
    private val profileDao: ProfileDao,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : CapabilityRepository {

    override fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?> =
        profileDao.observeServer(serverId.value).map { entity ->
            entity?.let(AuthEntityMappers::toCapabilities)
        }

    override suspend fun capabilities(serverId: ServerId): ServerCapabilities? = withContext(ioDispatcher) {
        profileDao.findServer(serverId.value)?.let(AuthEntityMappers::toCapabilities)
    }

    /**
     * PRODUCT_SPEC DL-001 — one observation, folded into the stored set.
     *
     * Read-modify-write under a mutex. Two files of the same book download one after another rather than
     * at once, so contention is not expected; the lock is here because the alternative to holding it is
     * two concurrent books racing on one row and one of them losing its answer, which would be invisible
     * and would only ever be wrong in the direction of forgetting.
     *
     * Writes nothing when the answer has not changed, so the ordinary case — every file after the first
     * confirming what the first one proved — touches no rows at all.
     */
    override suspend fun record(
        serverId: ServerId,
        capability: ServerCapability,
        isSupported: Boolean,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        observations.withLock {
            val stored = profileDao.findServer(serverId.value)?.let(AuthEntityMappers::toCapabilities)
                ?: return@withContext missingProfile()
            if (stored.supports(capability) == isSupported) return@withContext AppResult.Success(Unit)

            val supported = if (isSupported) stored.supported + capability else stored.supported - capability
            persistSet(stored, supported, clock.now().toEpochMilli())
            logger.info(
                LogCategory.Sync,
                "A server capability was observed rather than probed",
                LogField.Identifier("server", serverId.value),
                // The capability's name describes the software, not the user or their library (14.5).
                LogField.Public("capability", capability.name),
                LogField.Public("supported", isSupported.toString()),
            )
            AppResult.Success(Unit)
        }
    }

    private val observations = Mutex()

    override suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities> = withContext(ioDispatcher) {
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext missingProfile()
        val server = profileDao.findServer(profile.serverId)
            ?: return@withContext missingProfile()
        val serverId = ServerId(server.serverId)

        when (val resolved = gateway.capabilities.resolve(serverId, server.baseUrl)) {
            is AppResult.Failure -> resolved
            is AppResult.Success -> {
                persist(resolved.value, detectedAt = clock.now().toEpochMilli())
                logger.info(
                    LogCategory.Sync,
                    "Completed the server capability handshake",
                    LogField.Identifier("server", serverId.value),
                    // The version and the count are not private: they describe the software, not the
                    // user or their library (PRODUCT_SPEC 14.5).
                    LogField.Public("serverVersion", resolved.value.serverVersion ?: UNREPORTED),
                    LogField.Count("confirmedCapabilities", resolved.value.supported.size),
                )
                resolved
            }
        }
    }

    /**
     * PRODUCT_SPEC SYNC-001 — writes the probe's answer without discarding what the device proved.
     *
     * A handshake replaces the resolved set, and rightly: a server that stopped offering websockets should
     * stop being listed as offering them. But `ServerCapability.ObservedOnly` entries are not in the
     * probe's answer at all — `/status` cannot speak to ranges or validators — so replacing wholesale would
     * silently forget them on every reconnect. They are carried across instead.
     */
    private suspend fun persist(capabilities: ServerCapabilities, detectedAt: Long) {
        val observed = profileDao.findServer(capabilities.serverId.value)
            ?.let(AuthEntityMappers::toCapabilities)
            ?.supported
            ?.filterTo(mutableSetOf()) { it in ServerCapability.ObservedOnly }
            .orEmpty()
        persistSet(capabilities, capabilities.supported + observed, detectedAt)
    }

    private suspend fun persistSet(
        capabilities: ServerCapabilities,
        supported: Set<ServerCapability>,
        detectedAt: Long,
    ) {
        profileDao.updateServerCapabilities(
            serverId = capabilities.serverId.value,
            capabilitiesJson = AuthEntityMappers.capabilitiesJson(supported),
            authMethodsJson = AuthEntityMappers.authMethodsJson(capabilities.authMethods),
            serverVersion = capabilities.serverVersion,
            detectedAt = detectedAt,
        )
    }

    private fun <T> missingProfile(): AppResult<T> = AppError.Validation(
        summary = "That profile is no longer saved on this device.",
    ).asFailure()

    private companion object {
        /** A server that reported no version. Recorded as a constant so the log line stays a constant. */
        const val UNREPORTED = "unreported"
    }
}
