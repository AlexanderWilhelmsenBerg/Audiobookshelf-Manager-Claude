package com.example.shelfplayer.core.model.playback

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — one output a listener can send this book to, right now.
 *
 * ### Not a [KnownDevice], and the difference matters
 *
 * A [KnownDevice] is a device this app has *ever seen* and holds a policy for: it exists whether or not the
 * thing is plugged in, and its whole subject is what should happen when it connects. An [AudioOutput] is a
 * device that is **connected at this moment** and can be played to now. The settings list is the first; the
 * chooser on the player card is the second.
 *
 * They deliberately share [id]. `OutputDevices` derives both from the same kind-and-name rule, so the pair
 * of earbuds a listener picks here is the same row whose policy they set in Settings. Two identity schemes
 * would have let one feature call a device "Pixel Buds Pro" and the other call it something else, and the
 * only person who could tell would be the one whose policy silently stopped applying.
 *
 * @property id the stable key, shared with [KnownDevice.id]. Never a hardware address (ROUTE-002, 14.5).
 * @property displayName what the device calls itself. Shown as-is; also not an address.
 * @property isActive whether audio is going here now — either because the listener chose it, or because the
 *   system routed here on its own. See `AudioOutputRouter`, which is the only thing that can know.
 */
data class AudioOutput(val id: String, val displayName: String, val kind: DeviceKind, val isActive: Boolean = false) {

    /**
     * Whether choosing this output would move audio into a room rather than into someone's ears.
     *
     * The same question [KnownDevice.isSpeaker] asks, for the same reason, and the chooser uses it to decide
     * whether a selection is worth remembering — see `AudioOutputRouter`.
     */
    val isSpeaker: Boolean get() = kind == DeviceKind.Speaker
}
