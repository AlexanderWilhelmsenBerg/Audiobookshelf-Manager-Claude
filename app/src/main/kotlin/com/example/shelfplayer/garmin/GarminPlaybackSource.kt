package com.example.shelfplayer.garmin

import com.example.shelfplayer.playback.PlaybackController
import com.example.shelfplayer.playback.PlaybackUiState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only adapter to the existing Media3 owner; no second state holder or playback actions. */
internal interface GarminPlaybackSource {
    val state: StateFlow<PlaybackUiState>
}

@Singleton
internal class Media3GarminPlaybackSource @Inject constructor(controller: PlaybackController) : GarminPlaybackSource {
    override val state: StateFlow<PlaybackUiState> = controller.state
}

@Module
@InstallIn(SingletonComponent::class)
internal interface GarminPlaybackSourceModule {
    @Binds
    @Singleton
    fun bindPlaybackSource(implementation: Media3GarminPlaybackSource): GarminPlaybackSource
}
