package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.playback.AudioRouteLab
import com.example.shelfplayer.playback.AudioRouteLabState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** UI adapter; the player and the incident file remain owned by :playback. */
@HiltViewModel
class AudioRouteLabViewModel @Inject constructor(private val lab: AudioRouteLab) : ViewModel() {
    val state: StateFlow<AudioRouteLabState> = lab.state

    fun start() = lab.start()

    fun stop() = lab.stop()

    fun clear() = lab.clear()

    fun mark(step: String) = lab.mark(step)

    fun heardAt(destination: String) = lab.heardAt(destination)

    fun report(version: String): String = lab.report(version)
}
