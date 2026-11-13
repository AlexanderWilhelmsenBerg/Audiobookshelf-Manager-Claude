package com.example.shelfplayer.playback

import android.view.KeyEvent

/** Capture-local timing only. A preceding key is not proof that it caused a command or identifies a headset. */
internal class MediaCommandTiming(private val uptimeMillis: () -> Long) {
    private data class Key(val sequence: Long, val received: Long)
    private var sequence = 0L
    private var preceding: Key? = null

    fun key(event: KeyEvent?): String {
        val now = uptimeMillis()
        val id = ++sequence
        val age = event?.eventTime?.takeIf { it > 0 && it <= now }?.let { now - it }
        preceding = if (age != null && age <= WINDOW_MS) Key(id, now) else null
        return "inputSequence=$id receivedUptimeMs=$now eventUptimeMs=${event?.eventTime ?: "unknown"} " +
            "deliveryAgeMs=${age ?: "unknown"}"
    }

    fun transport(): String {
        val now = uptimeMillis()
        val key = preceding?.takeIf { now - it.received in 0..WINDOW_MS }
        preceding = null
        return "inputSequence=${++sequence} receivedUptimeMs=$now precedingKey=${key?.sequence ?: "none"} " +
            "sinceKeyReceiptMs=${key?.let { now - it.received } ?: "unknown"} correlation=temporal-only"
    }

    private companion object {
        const val WINDOW_MS = 1000L
    }
}
