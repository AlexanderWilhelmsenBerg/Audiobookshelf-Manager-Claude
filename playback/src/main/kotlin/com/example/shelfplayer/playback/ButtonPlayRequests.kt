package com.example.shelfplayer.playback

import android.view.KeyEvent

internal data class ButtonController(val pkg: String, val uid: Int)
internal data class ButtonPlayScope(val position: MutedPositionEffects.Context, val selection: Long?, val book: Long?)
internal data class ButtonDown(
    val code: Int,
    val caller: ButtonController,
    val isAuto: Boolean,
    val scope: ButtonPlayScope,
    val candidates: Set<String>,
    val at: Long,
    val soleHeadset: String?,
)

internal data class ButtonCommand(val play: Boolean, val caller: ButtonController?, val notificationRelay: Boolean)

internal sealed interface ButtonDestination {
    val evidence: String get() = when (this) {
        Car -> "android-auto"
        is Headset -> if (exact) "sony-tap" else "single-headset"
        Refused -> "inventory-changed"
    }
    data object Car : ButtonDestination
    data class Headset(val id: String, val exact: Boolean) : ButtonDestination
    data object Refused : ButtonDestination
}

/** Temporary input evidence only. Media3 still resolves the key, including double taps, into one transport command. */
internal class ButtonPlayRequests {
    private data class Tap(val id: String, val scope: ButtonPlayScope, val at: Long)
    internal class Claim(val key: ButtonDown, val target: ButtonDestination, var immediateRelay: Boolean)
    private var tap: Tap? = null
    private var pending: Claim? = null

    fun clear() {
        tap = null
        pending = null
    }

    fun sonyTap(id: String, scope: ButtonPlayScope, now: Long) {
        tap = Tap(id, scope, now)
    }

    fun down(key: ButtonDown): Claim? {
        val physical = tap
        clear()
        val play = key.code == KeyEvent.KEYCODE_MEDIA_PLAY
        val toggle = key.code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key.code == KeyEvent.KEYCODE_HEADSETHOOK
        if (!play && !toggle) return null
        val target = when {
            key.isAuto -> ButtonDestination.Car

            key.caller.pkg !in HEADSET_DISPATCHERS -> null

            key.caller.pkg == GOOGLE && toggle && physical?.scope == key.scope &&
                key.at - physical.at in 0..TAP_WINDOW_MS ->
                ButtonDestination.Headset(physical.id, true)

            else -> key.soleHeadset?.let { ButtonDestination.Headset(it, false) }
        }
        return target?.let { Claim(key, it, play).also { claim -> pending = claim } }
    }

    /** Media3 may forward explicit PLAY synchronously via its notification controller. */
    fun endDispatch(claim: Claim?) {
        if (pending === claim) claim?.immediateRelay = false
    }

    fun consume(
        command: ButtonCommand,
        scope: ButtonPlayScope,
        candidates: Set<String>,
        now: Long,
    ): ButtonDestination? {
        val claim = pending
        clear()
        if (!command.play || claim == null) return null
        val fresh = claim.key.scope == scope && now - claim.key.at in 0..TRANSPORT_WINDOW_MS
        val relayed = command.notificationRelay && claim.immediateRelay
        if (!fresh || (command.caller != claim.key.caller && !relayed)) return null
        val target = claim.target
        if (target !is ButtonDestination.Headset) return target
        val available = target.id in claim.key.candidates && target.id in candidates
        return if (available && (target.exact || candidates.size == 1)) target else ButtonDestination.Refused
    }

    private companion object {
        const val GOOGLE = "com.google.android.googlequicksearchbox"
        val HEADSET_DISPATCHERS = setOf("com.android.bluetooth", GOOGLE)
        const val TAP_WINDOW_MS = 750L
        const val TRANSPORT_WINDOW_MS = 1000L
    }
}
