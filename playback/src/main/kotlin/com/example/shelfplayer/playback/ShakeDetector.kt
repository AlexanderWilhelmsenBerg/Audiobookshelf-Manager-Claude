package com.example.shelfplayer.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * PRODUCT_SPEC PLAY-008 — "optional shake-to-extend ... must not run motion sensing continuously when
 * no timer is active".
 *
 * That sentence is the whole design. [start] registers the listener and [stop] unregisters it, and the
 * only caller is `SleepTimerController`, which calls them at exactly the moments a timer begins and
 * ends. There is no "enabled" flag inside this class that could leave a sensor running while the
 * feature is off — an unregistered listener costs nothing, which a flag does not guarantee.
 *
 * ### What counts as a shake
 *
 * The accelerometer reports gravity as well as movement, so a phone at rest reads about `9.81` on
 * whichever axis is down. Subtracting gravity from the magnitude gives movement alone, and
 * [SHAKE_THRESHOLD] is well above what putting a phone down produces and below a deliberate shake.
 *
 * Two guards stop one shake counting several times: a single shake swings the phone back and forth and
 * crosses the threshold repeatedly, so [QUIET_PERIOD_MS] must pass before another is reported.
 *
 * ### Why a device without an accelerometer is not an error
 *
 * [start] returns `false` and the caller carries on with a timer that simply cannot be shaken. Emulators
 * and some tablets have no accelerometer, and refusing to set a timer on them would be the feature
 * breaking a requirement it is optional to.
 */
@Singleton
class ShakeDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
) {
    private val sensors: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var listener: SensorEventListener? = null
    private var lastShakeAt = 0L

    /** @return whether motion sensing actually started. `false` on a device with no accelerometer. */
    fun start(onShake: () -> Unit): Boolean {
        stop()
        val manager = sensors ?: return false
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val registered = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (isShake(event)) onShake()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // `SENSOR_DELAY_UI` rather than `_FASTEST`: a shake lasts hundreds of milliseconds and the
        // difference between the two is battery spent sampling a gesture that is already unmistakable.
        val started = manager.registerListener(registered, accelerometer, SensorManager.SENSOR_DELAY_UI)
        if (started) {
            listener = registered
            logger.debug(LogCategory.Playback, "Motion sensing started for the sleep timer")
        }
        return started
    }

    fun stop() {
        val current = listener ?: return
        sensors?.unregisterListener(current)
        listener = null
        lastShakeAt = 0L
        logger.debug(LogCategory.Playback, "Motion sensing stopped")
    }

    /** Whether sensing is running, so a caller can tell "shook" from "could not sense". */
    val isSensing: Boolean get() = listener != null

    private fun isShake(event: SensorEvent): Boolean {
        val values = event.values
        if (values.size < AXES) return false
        val magnitude = sqrt(
            (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble(),
        )
        val movement = magnitude - SensorManager.GRAVITY_EARTH
        if (movement < SHAKE_THRESHOLD) return false
        // The sensor's own timestamp is nanoseconds since boot, which is monotonic — a wall clock here
        // would let a time-zone change or an NTP correction swallow or duplicate a shake.
        val nowMs = event.timestamp / NANOS_PER_MILLI
        if (nowMs - lastShakeAt < QUIET_PERIOD_MS) return false
        lastShakeAt = nowMs
        logger.debug(
            LogCategory.Playback,
            "A shake was detected",
            LogField.Public("movement", movement.toInt()),
        )
        return true
    }

    private companion object {
        const val AXES = 3
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Metres per second squared **above gravity**.
         *
         * Roughly `1.5 g` of movement. A phone set down on a table peaks around `3`; a deliberate shake
         * is comfortably past `12`. Picked to be missed rather than to fire by accident: a timer that
         * restarts itself when the listener rolls over is worse than one that needs a second shake.
         */
        const val SHAKE_THRESHOLD = 12.0

        /** One shake swings the phone several times. This is how long before another one counts. */
        const val QUIET_PERIOD_MS = 1_000L
    }
}
