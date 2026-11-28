package com.example.shelfplayer.playback

/**
 * PD-009 experiment seam, 2026-11-16. A car's own Bluetooth was measured taking the active media device from
 * a playing headset for up to four seconds. Debug builds may ask
 * Android's system media router to make that headset active again; release builds bind [NoHeadsetTakeBack].
 *
 * Implementations never press Play, change BookWave's output intent or touch focus. The existing route wait
 * and muted actual-track proof still decide whether and when playback resumes.
 */
internal interface HeadsetTakeBack {
    /** Diagnostic comparison mode; null when this build has no automatic system-route experiment. */
    val automaticTransferEnabled: Boolean? get() = null
    val availableForTrial: Boolean get() = false
    fun armEarly(arrival: EarlyHeadsetArrival) = Unit
    fun onHeadsetStream(playing: Boolean) = Unit
    fun earlyTrackLost() = Unit
    fun onEarlyTrackRouteChanged() = Unit

    /** Playback is measured on the headset right now; remember which system route that is. */
    fun onHeadsetVerified(endpointId: String, mayRemember: () -> Boolean)

    /** A car-class Bluetooth device displaced the verified headset. Returns at once; the outcome is logged. */
    fun onHeadsetDisplaced(
        endpointId: String,
        scope: CarBluetoothArrivalWindow.TakeBackScope,
        isCurrent: () -> Boolean,
    )

    /** New listener or content intent revokes deferred transfers before another system callback can run. */
    fun cancelPending()

    fun close()
}

/** One previously verified output and the listener intent that owns this particular car arrival. */
internal class EarlyHeadsetArrival(
    val endpoint: String,
    val scope: CarBluetoothArrivalWindow.TakeBackScope,
    val isCurrent: () -> Boolean,
    val trackMoved: () -> Boolean,
)

internal object NoHeadsetTakeBack : HeadsetTakeBack {
    override fun onHeadsetVerified(endpointId: String, mayRemember: () -> Boolean) = Unit
    override fun onHeadsetDisplaced(
        endpointId: String,
        scope: CarBluetoothArrivalWindow.TakeBackScope,
        isCurrent: () -> Boolean,
    ) = Unit
    override fun cancelPending() = Unit
    override fun close() = Unit
}
