package com.example.shelfplayer.sync

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveRealtimeUpdatesUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC LIB-001 / SYNC-002 — process-foreground owner for live library progress.
 *
 * Lifecycle transitions are serialized through a channel instead of directly replacing jobs. That detail is
 * what guarantees a rapid background -> foreground transition cannot briefly leave two authenticated sockets
 * alive: the previous foreground job is cancelled and joined before a replacement starts.
 *
 * Profile changes use [collectLatest] for the same reason. Profile A is fully cancelled before profile B's
 * collector becomes authoritative, and every repository write still carries the originating profile id.
 */
@Singleton
class ForegroundRealtimeSyncCoordinator internal constructor(
    private val profiles: ProfileRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val observeRealtime: suspend (ProfileId) -> Unit,
    private val reconcile: suspend (ProfileId) -> Unit,
    private val logger: Logger,
) {
    @Inject
    constructor(
        profiles: ProfileRepository,
        @ApplicationScope applicationScope: CoroutineScope,
        observeRealtime: ObserveRealtimeUpdatesUseCase,
        reconcile: SyncAccountUseCase,
        logger: Logger,
    ) : this(
        profiles = profiles,
        applicationScope = applicationScope,
        observeRealtime = observeRealtime::invoke,
        reconcile = { profileId ->
            reconcile(profileId)
            Unit
        },
        logger = logger,
    )

    private val lifecycle = Channel<Boolean>(capacity = Channel.UNLIMITED)

    init {
        applicationScope.launch {
            var foreground = false
            var foregroundJob: Job? = null
            for (requestedForeground in lifecycle) {
                if (requestedForeground == foreground) continue
                foreground = requestedForeground
                foregroundJob?.cancel()
                foregroundJob?.join()
                foregroundJob = null

                if (foreground) {
                    logger.info(LogCategory.Sync, "Foreground realtime ownership started")
                    foregroundJob = launch { collectActiveProfile() }
                } else {
                    logger.info(LogCategory.Sync, "Foreground realtime ownership released")
                }
            }
        }
    }

    fun onForegrounded() {
        lifecycle.trySend(true)
    }

    fun onBackgrounded() {
        lifecycle.trySend(false)
    }

    private suspend fun collectActiveProfile() {
        profiles.observeActiveProfile()
            .map { it?.id }
            .distinctUntilChanged()
            .collectLatest { profileId ->
                if (profileId == null) {
                    logger.info(LogCategory.Sync, "Foreground realtime idle without an active profile")
                    return@collectLatest
                }

                coroutineScope {
                    // Socket replay is not assumed. Reconcile once whenever this profile becomes the
                    // foreground authority, including every foreground return and every profile switch.
                    launch { reconcile(profileId) }
                    try {
                        logger.info(LogCategory.Sync, "Foreground realtime collector active")
                        observeRealtime(profileId)
                    } finally {
                        logger.info(LogCategory.Sync, "Foreground realtime collector stopped")
                    }
                }
            }
    }
}
