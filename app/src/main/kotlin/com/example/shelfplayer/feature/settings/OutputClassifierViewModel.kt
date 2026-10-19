package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.playback.OutputClassifier
import com.example.shelfplayer.playback.OutputClassifierState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OutputClassifierViewModel @Inject constructor(private val classifier: OutputClassifier) : ViewModel() {
    val state: StateFlow<OutputClassifierState> = classifier.state
    fun start() = classifier.start()
    fun stop() = classifier.stop()
    fun clear() = classifier.clear()
    fun refresh() = classifier.refresh()
    fun report(version: String): String = classifier.report(version)
}
