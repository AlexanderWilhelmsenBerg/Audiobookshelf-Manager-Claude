package com.example.shelfplayer.playback

import androidx.media3.common.Player

/** One explicitly started debug run. Only a measured playing headset can arm its next displacement. */
internal class BookHeadsetHoldRun(val record: (String) -> Unit) {
    private class Heard(val player: Player, val id: String, val book: Long?, val selection: Long?) {
        fun matches(current: Player, target: String, state: OutputIntentOwner.Snapshot): Boolean =
            player === current && id == target && book == state.activeBookGeneration &&
                selection == state.selection?.sequence
    }

    private var heard: Heard? = null

    fun measured(player: Player, id: String, state: OutputIntentOwner.Snapshot) {
        if (heard?.matches(player, id, state) == true) return
        heard = Heard(player, id, state.activeBookGeneration, state.selection?.sequence)
        record("headset-armed")
    }

    fun consume(player: Player, id: String, state: OutputIntentOwner.Snapshot): Boolean {
        if (heard?.matches(player, id, state) != true) return false
        heard = null
        return true
    }
}
