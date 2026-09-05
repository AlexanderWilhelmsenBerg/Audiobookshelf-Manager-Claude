package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.SleepTimerDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.SleepTimerSessionEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — the sleep timer's settings and history.
 *
 * The settings live in DataStore and history lives in Room. Both are storage boundaries, so methods that
 * promise [AppResult] translate their storage exceptions here instead of leaking platform failures upward.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultSleepTimerRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val sleepTimerDao: SleepTimerDao,
    private val profileDao: ProfileDao,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : SleepTimerRepository {

    override fun observeSettings(): Flow<SleepTimerSettings> = settings.sleepTimer

    override suspend fun setDefaultLength(length: Duration): AppResult<Unit> = write {
        settings.setSleepTimerDefaultLength(length)
    }

    override suspend fun setFadeLength(length: Duration): AppResult<Unit> = write {
        settings.setSleepTimerFadeLength(length)
    }

    override suspend fun setShakeToRestart(enabled: Boolean): AppResult<Unit> = write {
        settings.setSleepTimerShakeToRestart(enabled)
        logger.info(
            LogCategory.Settings,
            "Shake to restart the sleep timer was changed",
            LogField.Public("enabled", enabled),
        )
    }

    override suspend fun setRewindOnStop(length: Duration): AppResult<Unit> = write {
        settings.setSleepTimerRewindOnStop(length)
        logger.info(
            LogCategory.Settings,
            "The sleep timer's rewind-on-stop was changed",
            LogField.Millis("length", length.inWholeMilliseconds),
        )
    }

    override fun observeRecentSessions(limit: Int): Flow<List<SleepTimerSession>> =
        settings.activeProfileId.flatMapLatest { profileId ->
            if (profileId == null) {
                flowOf(emptyList())
            } else {
                sleepTimerDao.observeRecent(profileId.value, limit).map { rows ->
                    rows.map { row -> row.toDomain(profileId) }
                }
            }
        }

    override suspend fun recordStarted(bookId: LibraryItemId, mode: SleepTimerMode): AppResult<String> =
        withContext(ioDispatcher) {
            val stored = resultOf(onError = ::storeFailure) { settings.current() }
            if (stored is AppResult.Failure) return@withContext stored
            val profileId = (stored as AppResult.Success).value.activeProfileId.takeIf(String::isNotBlank)
                ?: return@withContext AppResult.Failure(AppError.Authentication())

            val found = resultOf(onError = ::storeFailure) { profileDao.findProfile(profileId) }
            if (found is AppResult.Failure) return@withContext found
            val profile = (found as AppResult.Success).value
                ?: return@withContext AppResult.Failure(AppError.Authentication())

            resultOf(onError = ::storeFailure) {
                val sessionId = UUID.randomUUID().toString()
                sleepTimerDao.upsert(
                    SleepTimerSessionEntity(
                        sessionId = sessionId,
                        profileId = profileId,
                        bookKey = EntityKey.of(profile.serverId, bookId.value),
                        mode = mode.wireName(),
                        modeLength = when (mode) {
                            is SleepTimerMode.Fixed -> mode.length.inWholeMilliseconds
                            SleepTimerMode.EndOfChapter -> 0L
                        },
                        startedAt = clock.now().toEpochMilli(),
                        endedAt = null,
                        outcome = null,
                        restarts = 0,
                    ),
                )
                sleepTimerDao.prune(profileId, SleepTimerRepository.RETAINED_HISTORY)
                logger.info(
                    LogCategory.Playback,
                    "A sleep timer started",
                    LogField.Public("mode", mode.wireName()),
                )
                sessionId
            }
        }

    override suspend fun recordRestarted(sessionId: String): AppResult<Unit> = withContext(ioDispatcher) {
        when (val found = resultOf(onError = ::storeFailure) { sleepTimerDao.find(sessionId) }) {
            is AppResult.Failure -> found
            is AppResult.Success -> {
                val existing = found.value ?: return@withContext AppResult.Failure(missingSession())
                resultOf(onError = ::storeFailure) {
                    sleepTimerDao.upsert(existing.copy(restarts = existing.restarts + 1))
                }
            }
        }
    }

    override suspend fun recordEnded(sessionId: String, outcome: SleepTimerOutcome): AppResult<Unit> =
        withContext(ioDispatcher) {
            when (val found = resultOf(onError = ::storeFailure) { sleepTimerDao.find(sessionId) }) {
                is AppResult.Failure -> found
                is AppResult.Success -> {
                    val existing = found.value ?: return@withContext AppResult.Failure(missingSession())
                    if (existing.endedAt != null) return@withContext AppResult.Success(Unit)
                    resultOf(onError = ::storeFailure) {
                        sleepTimerDao.upsert(
                            existing.copy(endedAt = clock.now().toEpochMilli(), outcome = outcome.name),
                        )
                        logger.info(
                            LogCategory.Playback,
                            "A sleep timer ended",
                            LogField.Public("outcome", outcome.name),
                            LogField.Count("restarts", existing.restarts),
                        )
                    }
                }
            }
        }

    override suspend fun closeOrphanedSessions(): AppResult<Int> = withContext(ioDispatcher) {
        resultOf(onError = ::storeFailure) {
            val closed = sleepTimerDao.closeOrphaned(
                endedAt = clock.now().toEpochMilli(),
                outcome = SleepTimerOutcome.Abandoned.name,
            )
            if (closed > 0) {
                logger.info(
                    LogCategory.Playback,
                    "Closed sleep timers left running by a previous process",
                    LogField.Count("closed", closed),
                )
            }
            closed
        }
    }

    private suspend fun write(block: suspend () -> Unit): AppResult<Unit> = withContext(ioDispatcher) {
        resultOf(onError = ::storeFailure) { block() }
    }

    private fun storeFailure(throwable: Throwable): AppError {
        logger.warn(LogCategory.Settings, "Sleep timer state could not be written", throwable = throwable)
        return AppError.Storage(summary = "Sleep timer state could not be saved.")
    }

    private fun missingSession(): AppError = AppError.Conflict(
        summary = "That sleep timer is no longer recorded on this device.",
    )

    private fun SleepTimerMode.wireName(): String = when (this) {
        is SleepTimerMode.Fixed -> FIXED
        SleepTimerMode.EndOfChapter -> END_OF_CHAPTER
    }

    private fun SleepTimerSessionEntity.toDomain(profileId: ProfileId) = SleepTimerSession(
        id = sessionId,
        profileId = profileId,
        bookId = LibraryItemId(EntityKey.remoteIdOf(bookKey)),
        mode = if (mode == END_OF_CHAPTER) {
            SleepTimerMode.EndOfChapter
        } else {
            SleepTimerMode.Fixed(modeLength.milliseconds)
        },
        startedAt = Instant.ofEpochMilli(startedAt),
        endedAt = endedAt?.let(Instant::ofEpochMilli),
        outcome = outcome?.let { name -> SleepTimerOutcome.entries.firstOrNull { it.name == name } },
        restarts = restarts,
    )

    private companion object {
        const val FIXED = "Fixed"
        const val END_OF_CHAPTER = "EndOfChapter"
    }
}
