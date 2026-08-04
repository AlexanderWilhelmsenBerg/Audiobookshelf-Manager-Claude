package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.data.library.mapper.EntityMappers
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 20, Phase 0 — "the app opens a fake library; no real credentials needed".
 *
 * On first launch this writes the fixture server and profile into Room, selects that profile, and
 * runs one refresh through the same [LibraryRepository] the real gateway will use in Phase 1. From
 * then on the UI is reading ordinary cached state and knows nothing about fixtures.
 *
 * It runs at most once, guarded by a persisted flag rather than by "is the database empty?", so a
 * user who clears the demo content does not have it silently restored.
 */
@Singleton
class FixtureLibraryBootstrapper @Inject constructor(
    private val gateway: AudiobookshelfGateway,
    private val profileDao: ProfileDao,
    private val libraryRepository: LibraryRepository,
    private val settings: AppSettingsDataSource,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun seedIfNeeded(): AppResult<SeedOutcome> = withContext(ioDispatcher) {
        if (settings.settings.first().fixtureLibrarySeeded) {
            return@withContext AppResult.Success(SeedOutcome.AlreadySeeded)
        }

        val seeded = gateway.account.currentServer().flatMap { server ->
            gateway.account.currentProfile().flatMap { profile ->
                profileDao.upsertServer(EntityMappers.toEntity(server, clock.now()))
                profileDao.upsertProfile(EntityMappers.toEntity(profile))
                settings.setActiveProfile(profile.id)
                libraryRepository.refresh(profile.id)
            }
        }

        when (seeded) {
            is AppResult.Failure -> {
                logger.warn(
                    LogCategory.App,
                    "Demo library seeding failed",
                    LogField.Public("errorCode", seeded.error.code),
                )
                seeded
            }

            is AppResult.Success -> {
                // Marked only after the refresh committed, so a failure part-way through is retried
                // on the next launch instead of leaving a half-populated library forever.
                settings.markFixtureLibrarySeeded()
                logger.info(
                    LogCategory.App,
                    "Demo library seeded",
                    LogField.Count("books", seeded.value),
                )
                AppResult.Success(SeedOutcome.Seeded(seeded.value))
            }
        }
    }
}

sealed interface SeedOutcome {
    data object AlreadySeeded : SeedOutcome

    data class Seeded(val bookCount: Int) : SeedOutcome
}
