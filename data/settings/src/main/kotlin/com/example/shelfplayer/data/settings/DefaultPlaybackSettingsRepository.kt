package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.database.dao.BookPlaybackSettingsDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.BookPlaybackSettingsEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — the playback controls, across two stores.
 *
 * The device-wide settings live in the preferences store and the per-book speed in Room, which is why this
 * class names both. They are one repository because the question a caller asks is "how should this book
 * play", and answering it needs both halves; the split underneath is invisible to the domain
 * (PRODUCT_SPEC 9.3).
 *
 * ### Why the per-book speed is in Room
 *
 * A preferences store is one document rewritten on every write, and a map keyed by book in it would grow for
 * the life of the install and be rewritten whole every time anybody changed anything. Room has an index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultPlaybackSettingsRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val bookSettings: BookPlaybackSettingsDao,
    private val profileDao: ProfileDao,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : PlaybackSettingsRepository {

    override fun observeSettings(): Flow<PlaybackSettings> = settings.playback

    override suspend fun setDefaultSpeed(speed: PlaybackSpeed): AppResult<Unit> = write {
        settings.setDefaultSpeed(speed)
        logger.info(
            LogCategory.Settings,
            "The default playback speed changed",
            LogField.Public("speed", speed.label()),
        )
    }

    override suspend fun setSkipIntervals(skips: SkipIntervals): AppResult<Unit> = write {
        settings.setSkipIntervals(skips)
    }

    override suspend fun setAutoRewind(rewind: AutoRewind): AppResult<Unit> = write {
        settings.setAutoRewind(rewind)
        logger.info(
            LogCategory.Settings,
            "Auto-rewind after a pause was changed",
            LogField.Public("enabled", rewind.isEnabled),
        )
    }

    override suspend fun setBufferPreset(preset: BufferPreset): AppResult<Unit> = write {
        settings.setBufferPreset(preset)
        logger.info(
            LogCategory.Settings,
            "The streaming buffer preset changed",
            LogField.Public("preset", preset.name),
        )
    }

    override suspend fun setFinishedThreshold(threshold: Duration): AppResult<Unit> = write {
        settings.setFinishedThreshold(threshold)
        logger.info(
            LogCategory.Settings,
            "The finished threshold changed",
            LogField.Millis("threshold", threshold.inWholeMilliseconds),
        )
    }

    override suspend fun setAutoPlayOnCarConnect(enabled: Boolean): AppResult<Unit> = write {
        settings.setAutoPlayOnCarConnect(enabled)
        logger.info(
            LogCategory.Settings,
            "Auto-play on a car connection was changed",
            LogField.Public("enabled", enabled),
        )
    }

    override fun observeSpeedFor(bookId: LibraryItemId): Flow<PlaybackSpeed?> =
        settings.activeProfileId.flatMapLatest { profileId ->
            if (profileId == null) {
                flowOf(null)
            } else {
                // The book key needs the profile's server, and that is a suspending read — so the flow is
                // built from the profile row rather than from the id alone.
                profileDao.observeProfile(profileId.value).flatMapLatest { profile ->
                    if (profile == null) {
                        flowOf(null)
                    } else {
                        bookSettings
                            .observe(profile.profileId, EntityKey.of(profile.serverId, bookId.value))
                            .map { row -> row?.let { PlaybackSpeed.of(it.speedHundredths / HUNDREDTHS) } }
                    }
                }
            }
        }

    override suspend fun speedFor(bookId: LibraryItemId): PlaybackSpeed = withContext(ioDispatcher) {
        val profile = activeProfile()
        val override = profile?.let {
            bookSettings.find(it.profileId, EntityKey.of(it.serverId, bookId.value))
        }
        override?.let { PlaybackSpeed.of(it.speedHundredths / HUNDREDTHS) }
            ?: settings.playback.first().defaultSpeed
    }

    override suspend fun setSpeedFor(bookId: LibraryItemId, speed: PlaybackSpeed?): AppResult<Unit> =
        withContext(ioDispatcher) {
            val profile = activeProfile()
                ?: return@withContext AppResult.Failure(AppError.Authentication())
            val bookKey = EntityKey.of(profile.serverId, bookId.value)
            if (speed == null) {
                // Cleared rather than stored as the default: "no override" follows a changed profile default
                // and "deliberately 1.0×" does not, and only a missing row can express the first.
                bookSettings.clear(profile.profileId, bookKey)
            } else {
                bookSettings.upsert(
                    BookPlaybackSettingsEntity(
                        settingsKey = EntityKey.scoped(profile.profileId, bookKey),
                        profileId = profile.profileId,
                        bookKey = bookKey,
                        speedHundredths = (speed.value * HUNDREDTHS).toInt(),
                    ),
                )
            }
            // The speed is safe to log and the book is not (PRODUCT_SPEC 14.5).
            logger.info(
                LogCategory.Settings,
                "A book's playback speed was set",
                LogField.Public("speed", speed?.label() ?: "default"),
            )
            AppResult.Success(Unit)
        }

    private suspend fun activeProfile() = settings.current().activeProfileId
        .takeIf(String::isNotBlank)
        ?.let { profileDao.findProfile(it) }

    private suspend fun write(block: suspend () -> Unit): AppResult<Unit> = withContext(ioDispatcher) {
        block()
        AppResult.Success(Unit)
    }

    private companion object {
        /** The speed's storage unit, matching `AppSettingsDataSource`'s. */
        const val HUNDREDTHS = 100f
    }
}
