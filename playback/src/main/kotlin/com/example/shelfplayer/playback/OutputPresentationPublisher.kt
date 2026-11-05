package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutputPresentation
import com.example.shelfplayer.core.model.playback.OutputMeasurement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Publishes derived UI facts on the player dispatcher; owns no routing intent or playback effects. */
internal class OutputPresentationPublisher(
    intent: StateFlow<OutputIntentOwner.Snapshot>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val read: () -> AudioOutputPresentation,
) {
    private val mutable = MutableStateFlow(AudioOutputPresentation(null, null, OutputMeasurement.Unavailable))
    val state: StateFlow<AudioOutputPresentation> = mutable.asStateFlow()

    init {
        scope.launch(dispatcher) { intent.collect { mutable.value = read() } }
    }

    fun refresh() {
        scope.launch(dispatcher) { mutable.value = read() }
    }
}
