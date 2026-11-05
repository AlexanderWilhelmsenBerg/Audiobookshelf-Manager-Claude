package com.example.shelfplayer.playback

import android.view.KeyEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A narrow cold-start safety guard for the Android/system virtual PLAY_PAUSE event observed
 * immediately after a headset was switched on. It does not identify the physical button:
 * virtual media keys have no trustworthy A/B attribution.
 *
 * During the first three seconds of a newly constructed, empty player, the first virtual
 * toggle for the current live Bluetooth appearances is consumed only when Bluetooth is
 * enumerated and no car is connected. The process-scoped gate remembers opaque endpoint
 * tokens across service recreation, so a later deliberate press on the same connection
 * stays reachable. A newly enumerated appearance rearms protection using the existing
 * LiveOutputEndpointRegistry tokens; these tokens do not identify the button's origin.
 * Explicit PLAY (126), app controls, warm/loaded queues and detected car requests pass.
 * The short cold-start trade-off is documented in #273.
 */
@Singleton
internal class ColdStartToggleGate @Inject constructor() {
    private val window: Duration = 3.seconds
    private var awaitingUp = false
    private var lastSuppressedEndpoints: Set<String>? = null

    fun consume(
        sinceServiceStart: Duration,
        keyCode: Int,
        keyAction: Int,
        inputDeviceId: Int?,
        bluetoothEndpoints: Set<String>,
        emptyQueue: Boolean,
        carConnected: Boolean,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
        if (keyAction == KeyEvent.ACTION_UP && awaitingUp) {
            awaitingUp = false
            return true
        }
        if (keyAction != KeyEvent.ACTION_DOWN) return false
        val cold = sinceServiceStart >= Duration.ZERO && sinceServiceStart <= window
        val suspect = cold && inputDeviceId != null && inputDeviceId <= 0 &&
            bluetoothEndpoints.isNotEmpty() && emptyQueue && !carConnected &&
            bluetoothEndpoints != lastSuppressedEndpoints
        if (suspect) {
            awaitingUp = true
            lastSuppressedEndpoints = bluetoothEndpoints.toSet()
        }
        return suspect
    }
}
