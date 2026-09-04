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
 * @property isActive whether media is **actually** coming out here right now, as the platform reports it —
 *   not what the app asked for. `AudioOutputRouter` reads it from `AudioManager.getAudioDevicesForAttributes`
 *   on API 33+, and below that has nothing to read and falls back to the request. The two can disagree: a
 *   preferred device is a request the platform may decline silently, so a chooser that showed only the
 *   request would tick a device the sound is not coming from. Which output was *chosen* is a separate value
 *   the router publishes beside the list.
 */
data class AudioOutput(val id: String, val displayName: String, val kind: DeviceKind, val isActive: Boolean = false) {

    /**
     * Whether choosing this output would move audio into a room rather than into someone's ears.
     *
     * The same question [KnownDevice.isSpeaker] asks, kept here so the pair of types agree about a kind
     * rather than each deciding for itself; `AudioOutputIdentityTest` compares them. It has **no production
     * reader on this type** — the chooser deliberately remembers nothing (ADR-0027 decision 2), and the
     * asking-before-a-room rule lives on [KnownDevice] where the persisted policy is. Said plainly because
     * this KDoc used to claim the chooser used it to decide what to remember, which was never true.
     */
    val isSpeaker: Boolean get() = kind == DeviceKind.Speaker

    /**
     * PRODUCT_SPEC PLAY-002 — whether this is something a listener is wearing.
     *
     * What the car's headset button steps through, and what *Keep sound in the headset* holds on to. The
     * list itself lives on [DeviceKind] so that this type and [KnownDevice] cannot come to disagree about
     * it — the same reason [isSpeaker] is written the way it is (ADR-0027 decision 4).
     */
    val isHeadset: Boolean get() = kind.isHeadset
}
