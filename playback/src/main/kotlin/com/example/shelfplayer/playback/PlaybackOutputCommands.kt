package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.OutputDestination

/**
 * In-process command port, not a playback state owner. Detached selection keeps the existing
 * intent-only behavior; it cannot create a service or enqueue a future Play.
 */
internal class PlaybackOutputCommands(private val outputs: AudioOutputRouter) {
    private var handler: ((() -> Boolean) -> Boolean)? = null

    fun attach(dispatch: (() -> Boolean) -> Boolean) {
        handler = dispatch
    }

    fun detach(dispatch: (() -> Boolean) -> Boolean) {
        if (handler === dispatch) handler = null
    }

    fun select(id: String?, destination: OutputDestination): Boolean = dispatch {
        when (destination) {
            OutputDestination.Automatic -> outputs.select(null)
            OutputDestination.Car -> outputs.selectCar()
            OutputDestination.Output -> if (id != null) outputs.select(id) else return@dispatch false
        }
        true
    }

    fun keepHere(): Boolean = dispatch { outputs.keepAudioHere() }

    private fun dispatch(effect: () -> Boolean): Boolean = handler?.invoke(effect) ?: effect()
}
