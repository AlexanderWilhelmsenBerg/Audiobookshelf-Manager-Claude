package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Application entry point; observes existing owners, never issues a player command. */
@Singleton
class GarminBridge @Inject internal constructor(
    private val sdk: GarminMobileSdk,
    private val projector: GarminSnapshotProjector,
    private val privacyPolicy: GarminPrivacyPolicy,
    private val delivery: GarminDeliverySession,
    private val playback: GarminPlaybackSource,
    private val profiles: ProfileRepository,
    private val locks: ProfileLockRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val dispatcher: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow<GarminBridgeState>(GarminBridgeState.SdkUnavailable("NOT_STARTED"))
    val state: StateFlow<GarminBridgeState> = mutableState.asStateFlow()
    private var jobs: List<Job> = emptyList()
    private var input: ProjectionInput? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            applicationScope.launch(dispatcher) {
                sdk.state.collect { next ->
                    refreshProjection()
                    delivery.connection(next, sdk::send)
                    publish()
                }
            },
            applicationScope.launch(dispatcher) {
                sdk.incomingMessages.collect { raw ->
                    refreshProjection()
                    delivery.receive(raw, sdk::send)
                    publish()
                }
            },
            applicationScope.launch(dispatcher) {
                combine(playback.state, profiles.observeActiveProfile(), locks.observeLockState()) {
                        state,
                        profile,
                        lock,
                    ->
                    ProjectionInput(profile, lock, profiles.activeProfileGeneration())
                }.collect {
                    input = it
                    refreshProjection()
                    publish()
                }
            },
            applicationScope.launch(dispatcher) {
                while (isActive) {
                    delay(TICK_MS)
                    refreshProjection()
                    delivery.tick(sdk::send)
                    publish()
                }
            },
        )
        sdk.start()
    }

    fun forceSync() {
        if (jobs.isEmpty()) return
        applicationScope.launch(dispatcher) {
            refreshProjection()
            delivery.retry(sdk::send)
            publish()
        }
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        input = null
        delivery.stop()
        sdk.shutdown()
        publish()
    }

    private suspend fun refreshProjection() {
        val current = input
        val generation = profiles.activeProfileGeneration()
        val profile = current?.profile
        val allowed = current != null && profile != null && current.generation == generation &&
            profiles.activeProfileId() == profile.id && privacyPolicy.mayExpose(profile, current.lock) &&
            !locks.isLocked(profile.id) && profiles.activeProfileGeneration() == generation
        val snapshot = if (allowed) {
            // Read the current session, so a queued profile Flow cannot reuse another queue's metadata.
            projector.project(playback.state.value, profile)
        } else {
            null
        }
        delivery.select(snapshot, generation, sdk::send)
    }

    private fun publish() {
        mutableState.value = delivery.state
    }

    private data class ProjectionInput(val profile: Profile?, val lock: ProfileLockState, val generation: Long)

    private companion object {
        const val TICK_MS = 1_000L
    }
}
