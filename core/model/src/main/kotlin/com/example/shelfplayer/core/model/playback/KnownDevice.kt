package com.example.shelfplayer.core.model.playback

import java.time.Instant

/** PRODUCT_SPEC ROUTE-002 — what this app is allowed to do when an output device connects. */
enum class DevicePolicy {
    Never,
    ArmOnly,
    AutoPlay,
    Ask,
    ;

    companion object {
        val Default: DevicePolicy = ArmOnly
    }
}

/**
 * PRODUCT_SPEC ROUTE-002 — the transport-shaped kind Android reports for a remembered connection.
 *
 * This is deliberately not the same thing as [AudioOutputRole]. In particular, [Bluetooth] means only that
 * Android reported a Bluetooth transport; classic A2DP does not prove headset versus speaker versus car.
 */
enum class DeviceKind {
    Wired,
    Bluetooth,
    Car,
    HearingAid,
    Speaker,
    Other,
}

/** PRODUCT_SPEC ROUTE-002 — one device this app has seen, and its connection policy. */
data class KnownDevice(
    val id: String,
    val displayName: String,
    val kind: DeviceKind,
    val policy: DevicePolicy = DevicePolicy.Default,
    val lastSeenAt: Instant,
) {
    /** A definite speaker for explicit-content auto-play safeguards. */
    val isSpeaker: Boolean get() = kind == DeviceKind.Speaker

    companion object {
        /** One policy identity for unnamed wired headphones. */
        const val WIRED_ID: String = "wired"

        /** Projected/native car controller identity. */
        const val CAR_ID: String = "car"

        /** Stored fallback label for the controller-defined car row. */
        const val CAR_DISPLAY_NAME: String = "Car audio"
    }
}
