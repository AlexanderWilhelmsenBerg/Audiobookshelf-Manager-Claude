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
 * toggle in the app process is consumed only when Bluetooth is already enumerated and no car
 * is connected. Keeping this one-shot gate process-scoped means stopping a suppressed cold
 * MediaButtonReceiver start cannot reset the window forever. A later intentional button press
 * is not suppressed. Explicit PLAY (126), app controls, and Android Auto remain untouched.
 * The short cold-start trade-off is documented in #273.
 */
@Singleton
internal class ColdStartToggleGate @Inject constructor() {
    private val window: Duration = 3.seconds
    private var awaitingUp = false
    private var consumedFirstToggle = false

    fun consume(
        sinceServiceStart: Duration,
        keyCode: Int,
        keyAction: Int,
        inputDeviceId: Int?,
        hasBluetooth: Boolean,
        emptyQueue: Boolean,
        carConnected: Boolean,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
        if (keyAction == KeyEvent.ACTION_UP && awaitingUp) {
            awaitingUp = false
            return true
        }
        if (keyAction != KeyEvent.ACTION_DOWN || consumedFirstToggle) return false
        val cold = sinceServiceStart >= Duration.ZERO && sinceServiceStart <= window
        val suspect = cold && inputDeviceId != null && inputDeviceId <= 0 &&
            hasBluetooth && emptyQueue && !carConnected
        if (suspect) {
            awaitingUp = true
            consumedFirstToggle = true
        }
        return suspect
    }
}
