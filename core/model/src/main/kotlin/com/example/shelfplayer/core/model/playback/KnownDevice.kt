package com.example.shelfplayer.core.model.playback

import java.time.Instant

/**
 * PRODUCT_SPEC ROUTE-002 — what this app is allowed to do when an output device connects.
 *
 * Ordered from least to most surprising, which is also the order the picker shows them in. The default is
 * [ArmOnly] and not [AutoPlay], because auto-play is the only thing this app can do that makes noise
 * without anybody pressing anything.
 */
enum class DevicePolicy {

    /** Nothing happens. For a device somebody uses for something other than listening to books. */
    Never,

    /**
     * The last book is loaded and left **paused**. The requirement's default.
     *
     * Pressing play on the headset then starts it instantly, with no app to open and no book to find —
     * which is most of the value of auto-play, without the part that can start audio in a quiet room.
     */
    ArmOnly,

    /** The last book resumes by itself. Chosen per device, never globally, and never as a default. */
    AutoPlay,

    /** A notification offers to resume. For a device where the answer is usually but not always yes. */
    Ask,
    ;

    companion object {
        /** ROUTE-002: *"Default policy for every new device is `Arm only`."* */
        val Default: DevicePolicy = ArmOnly
    }
}

/**
 * PRODUCT_SPEC ROUTE-002 — the kind of thing that connected.
 *
 * It decides two things a policy alone cannot: how the device is *identified*, and whether starting
 * explicit content on it needs a second thought.
 */
enum class DeviceKind {

    /**
     * A wired headset or headphones.
     *
     * ROUTE-002: *"Wired headset is represented as a device category because a stable device identity may
     * be unavailable."* A 3.5mm jack reports no name and no address; there is one wired policy, not one per
     * pair of headphones.
     */
    Wired,

    /** A Bluetooth headset, earbuds or speaker, identified by the name it advertises. */
    Bluetooth,

    /** A car, which reaches this app as a media controller rather than as an audio device. */
    Car,

    /** A hearing aid. ROUTE-002 asks for a policy of its own, and this is what makes that possible. */
    HearingAid,

    /**
     * A speaker — the device's own, or a Bluetooth one that announces itself as one.
     *
     * ROUTE-002: *"Auto-play never starts explicit content when the device is classified as a speaker
     * unless separately confirmed."* A speaker is the case where starting a book affects people who did not
     * choose to hear it.
     */
    Speaker,

    /** Anything else that can play audio. */
    Other,
}

/**
 * PRODUCT_SPEC ROUTE-002 — one device this app has seen, and what it may do when it comes back.
 *
 * ### The id is not a hardware address
 *
 * ROUTE-002 asks for the minimum Nearby Devices permission, and the minimum turns out to be **none**:
 * `AudioDeviceInfo` reports a product name without any permission, while `getAddress` returns nothing
 * useful for a Bluetooth device unless `BLUETOOTH_CONNECT` is granted. So [id] is derived from the kind and
 * the advertised name, and the app never asks for a permission it would only use to tell two identically
 * named headsets apart.
 *
 * The consequence, stated rather than hidden: two pairs of earbuds with the same name share one policy.
 * That is a better trade than a runtime permission prompt on a listening app.
 *
 * @property displayName what the device calls itself, shown as-is. ROUTE-002 forbids showing hardware
 *   addresses in ordinary UI, and this is not one.
 * @property lastSeenAt when it last connected, so the settings list can be ordered by recency and a device
 *   nobody has used in a year is recognisable as such.
 */
data class KnownDevice(
    val id: String,
    val displayName: String,
    val kind: DeviceKind,
    val policy: DevicePolicy = DevicePolicy.Default,
    val lastSeenAt: Instant,
) {

    /**
     * Whether auto-play on this device needs the extra confirmation ROUTE-002 asks for.
     *
     * A speaker fills a room. Everything else is in or on one person's ears.
     */
    val isSpeaker: Boolean get() = kind == DeviceKind.Speaker

    companion object {
        /**
         * The single identity every wired headset shares.
         *
         * Not a constant for tidiness — it is the requirement. A wired connection has no name to key on, so
         * there is one wired policy and the settings row says "Wired headphones" rather than pretending to
         * know which pair.
         */
        const val WIRED_ID: String = "wired"

        /** The car's identity, for the same reason: it arrives as a controller, not as a named device. */
        const val CAR_ID: String = "car"

        /**
         * What the car row is called before anything supplies a better name — which nothing does.
         *
         * Beside [CAR_ID] because the two are one fact. A car offers no stable name, so both the row the
         * media session creates and the row a retired global setting seeds have to call it the same thing or
         * Settings would show two cars. Never a hardware address (ROUTE-002, 14.5); Settings renders the
         * localised *kind* label over this, and this is the stored fallback.
         */
        const val CAR_DISPLAY_NAME: String = "Car audio"
    }
}
