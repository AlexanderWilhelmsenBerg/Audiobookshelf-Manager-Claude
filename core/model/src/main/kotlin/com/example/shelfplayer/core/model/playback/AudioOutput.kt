package com.example.shelfplayer.core.model.playback

/**
 * PRODUCT_SPEC PLAY-002 — one output the platform currently reports for media.
 *
 * [kind] is the transport/classification Android exposes. [role] is the thing BookWave may safely mean by
 * an action such as *Headset* or *Car*. Those are deliberately separate: classic Bluetooth A2DP can be
 * earbuds, a speaker, or a dashboard, so transport alone cannot honestly decide the product role.
 */
data class AudioOutput(
    val id: String,
    val displayName: String,
    val kind: DeviceKind,
    val isActive: Boolean = false,
    val role: AudioOutputRole = kind.defaultOutputRole,
) {
    /** The built-in/known speaker class. Observed for safety, never offered as a BookWave destination. */
    val isSpeaker: Boolean get() = role == AudioOutputRole.Speaker

    /** A definite wearable output. Ambiguous classic A2DP is intentionally not called a headset here. */
    val isHeadset: Boolean get() = role == AudioOutputRole.Headset

    /**
     * A destination the headset action may consider when Android cannot tell us more.
     *
     * [AudioOutputRole.Ambiguous] remains a candidate so ordinary classic-Bluetooth earbuds keep working
     * without a Nearby Devices permission. It is never *classified* as a headset; hardware testing or a
     * future persisted role override can narrow it. Speakers and cars are excluded structurally.
     */
    val isHeadsetCandidate: Boolean
        get() = role == AudioOutputRole.Headset || role == AudioOutputRole.Ambiguous
}

/**
 * What an output means to BookWave, independent of how Android transports audio to it.
 *
 * Classic A2DP is [Ambiguous] because the public audio-device type cannot distinguish headphones from a
 * Bluetooth speaker or many projected-car audio links without additional Bluetooth information.
 */
enum class AudioOutputRole {
    Headset,
    Car,
    Speaker,
    Ambiguous,
    Other,
}

/** Default semantic role when only the older [DeviceKind] is available. */
private val DeviceKind.defaultOutputRole: AudioOutputRole
    get() = when (this) {
        DeviceKind.Wired,
        DeviceKind.HearingAid,
        -> AudioOutputRole.Headset

        DeviceKind.Car -> AudioOutputRole.Car
        DeviceKind.Speaker -> AudioOutputRole.Speaker
        DeviceKind.Bluetooth -> AudioOutputRole.Ambiguous
        DeviceKind.Other -> AudioOutputRole.Other
    }
