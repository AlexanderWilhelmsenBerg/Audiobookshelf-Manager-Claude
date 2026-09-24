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
import com.example.shelfplayer.core.model.playback.ShakeSensitivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Sensor seam owned by the sleep-timer lifecycle.
 *
 * The concrete Android implementation is [ShakeDetector]; the interface keeps timer behavior testable
 * without pretending a JVM test can produce real accelerometer events.
 */
interface ShakeSource {
    val isSensing: Boolean

    fun start(sensitivity: ShakeSensitivity, onShake: () -> Unit): Boolean

    fun stop()
}

/**
 * PRODUCT_SPEC PLAY-008 / BW-SLEEP-02 — shake-to-restart sensing is bounded to an active timer or its
 * configured post-expiry grace window.
 *
 * [start] registers the listener and [stop] unregisters it. The only lifecycle owner is
 * `SleepTimerController`, which reconciles those calls from the active timer/grace state and persisted
 * opt-in. There is no "enabled" flag here that could leave a sensor running after both owners are gone.
 *
 * ### What counts as a shake
 *
 * The accelerometer reports gravity as well as movement, so a phone at rest reads about `9.81` on
 * whichever axis is down. Subtracting gravity from the magnitude gives movement alone, and
 * [ShakeSensitivity.movementThreshold] maps the listener's Low / Normal / High choice to a deterministic
 * movement threshold. Normal preserves the original 12 m/s²-above-gravity behavior.
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
) : ShakeSource {
    private val sensors: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var listener: SensorEventListener? = null
    private var lastShakeAt = 0L

    /** @return whether motion sensing actually started. `false` on a device with no accelerometer. */
    override fun start(sensitivity: ShakeSensitivity, onShake: () -> Unit): Boolean {
        stop()
        val manager = sensors ?: return false
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val registered = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (isShake(event, sensitivity)) onShake()
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

    override fun stop() {
        val current = listener ?: return
        sensors?.unregisterListener(current)
        listener = null
        lastShakeAt = 0L
        logger.debug(LogCategory.Playback, "Motion sensing stopped")
    }

    /** Whether sensing is running, so a caller can tell "shook" from "could not sense". */
    override val isSensing: Boolean get() = listener != null

    private fun isShake(event: SensorEvent, sensitivity: ShakeSensitivity): Boolean {
        val values = event.values
        if (values.size < AXES) return false
        val magnitude = sqrt(
            (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble(),
        )
        val movement = magnitude - SensorManager.GRAVITY_EARTH
        if (movement < sensitivity.movementThreshold()) return false
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

        /** One shake swings the phone several times. This is how long before another one counts. */
        const val QUIET_PERIOD_MS = 1_000L
    }
}

/**
 * Metres per second squared above gravity required for one shake.
 *
 * High is intentionally easier to trigger, Low harder. Normal is the pre-BW-SLEEP-02 threshold and therefore
 * preserves existing behavior for every listener who never opens the new setting.
 */
private const val LOW_SHAKE_THRESHOLD = 16.0
private const val NORMAL_SHAKE_THRESHOLD = 12.0
private const val HIGH_SHAKE_THRESHOLD = 8.0

internal fun ShakeSensitivity.movementThreshold(): Double = when (this) {
    ShakeSensitivity.Low -> LOW_SHAKE_THRESHOLD
    ShakeSensitivity.Normal -> NORMAL_SHAKE_THRESHOLD
    ShakeSensitivity.High -> HIGH_SHAKE_THRESHOLD
}
