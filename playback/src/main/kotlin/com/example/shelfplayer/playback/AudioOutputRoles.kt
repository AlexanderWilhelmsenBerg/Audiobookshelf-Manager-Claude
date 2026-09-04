package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind

/**
 * PRODUCT_SPEC PLAY-002 — which output the car's two buttons mean, and what each press does.
 *
 * ### Why two role buttons rather than one cycle
 *
 * The player screen in Android Auto can hold an app's buttons but cannot open a list — a custom action
 * sends a session command and nothing more, no API pushes the car to a browse node, and the player template
 * has no submenu. ADR-0027's second amendment recorded that, and answered it with **one** button that
 * stepped through `[Automatic] + every connected output`.
 *
 * A device run found the cost of *every*: the phone's own speaker is an output, so a driver reaching for
 * their earbuds could land the book in the phone's speaker, in a car, at speed. Filtering the speaker out
 * of the cycle would have fixed that one case and left the underlying problem — a driver cannot see what
 * the next press selects, which ADR-0027 admitted when it accepted cycling.
 *
 * So the buttons name **roles** instead. *Car* is one destination and always the same one; *Headset* is a
 * short cycle over things a person is wearing, which is the only place a cycle earns its ambiguity because
 * every stop on it is somewhere the listener chose to put a device. The phone speaker is not reachable from
 * either button — not by a filter that a later edit could drop, but because no button names it. The browse
 * tab still lists every output including the speaker, for the listener who is parked and choosing.
 *
 * ### Everything here is a decision about a list
 *
 * Which is why it is a pure object with no `AudioManager` and no `Player`. What needs a car is that Android
 * Auto draws the buttons at all, and §2.11 covers that on hardware.
 */
internal object AudioOutputRoles {

    /**
     * Where the audio **is**, falling back to what was asked for.
     *
     * The route first, because that is the fact a listener wants from a control that names a destination,
     * and since ADR-0027's amendment the app can read it on API 33+. Below that — or when the platform
     * reports a route this app does not recognise — the choice stands in, and `null` means nothing chosen
     * and nothing readable, which is the honest answer rather than a guessed device.
     */
    fun current(outputs: List<AudioOutput>, selectedId: String?): AudioOutput? =
        outputs.firstOrNull(AudioOutput::isActive) ?: outputs.firstOrNull { it.id == selectedId }

    /** Everything a person could be wearing, in the order the platform reports it. */
    fun headsets(outputs: List<AudioOutput>): List<AudioOutput> = outputs.filter(AudioOutput::isHeadset)

    /**
     * The headset the book is coming out of, or was pointed at. Never one that is merely plugged in.
     *
     * The distinction is the whole of *Keep sound in the headset*: holding a route means keeping the sound
     * where it already is, and a headset sitting in a pocket is not where it already is.
     */
    fun activeHeadset(outputs: List<AudioOutput>, selectedId: String?): AudioOutput? =
        current(outputs, selectedId)?.takeIf(AudioOutput::isHeadset)

    /**
     * The car's audio bus, if the platform reports one.
     *
     * [DeviceKind.Car] is `AudioDeviceInfo.TYPE_BUS`, and a great many cars never produce it: projected
     * Android Auto reaches this app as a media *controller*, and its audio link may arrive as ordinary
     * Bluetooth A2DP that nothing in the public API distinguishes from a headset. That is why [carTarget]
     * exists and why this returns `null` rather than guessing which Bluetooth device is the dashboard.
     */
    fun car(outputs: List<AudioOutput>): AudioOutput? = outputs.firstOrNull { it.kind == DeviceKind.Car }

    /**
     * What the car button selects: the car's own bus where there is one, otherwise *Automatic*.
     *
     * *Automatic* is not a shrug here. Clearing the preferred device hands routing back to the platform,
     * and while a car is connected the platform's own answer **is** the car — that is exactly the behaviour
     * *Keep sound in the headset* exists to override. So the button does what it says on every car this app
     * can be run in, and on the ones that report a bus it says it with a specific device.
     *
     * `null` is therefore a legitimate return and means *Automatic*, the same as everywhere else in this
     * feature. It is never the string "car" invented from nothing.
     */
    fun carTarget(outputs: List<AudioOutput>): String? = car(outputs)?.id

    /**
     * The next headset to select, or `null` when there is not one to select.
     *
     * Three cases, and the middle one is the one worth reading. If the book is already in a headset, this
     * steps to the next one and wraps — which with a single pair of earbuds re-selects the same pair, a
     * harmless no-op that keeps the button from being dead. If the book is somewhere else — the car, a
     * speaker, nothing chosen — the **first** headset is the answer, because the press means *put it in my
     * ears* and stepping from a position that is not on the list would be a guess.
     *
     * Never *Automatic*. Unlike the old cycle this button is not the only way back to letting the system
     * route: the car button is, and the browse tab is.
     */
    fun nextHeadset(outputs: List<AudioOutput>, selectedId: String?): String? {
        val wearable = headsets(outputs)
        if (wearable.isEmpty()) return null
        val here = activeHeadset(outputs, selectedId) ?: return wearable.first().id
        val index = wearable.indexOfFirst { it.id == here.id }
        if (index == -1) return wearable.first().id
        return wearable[(index + 1) % wearable.size].id
    }

    /**
     * What the two buttons should look like right now.
     *
     * One value rather than three reads, so `PlaybackService` can compare it against the last one it
     * published and rewrite the notification only when something a listener would see has moved. Media3
     * pushes every button set to every controller, and a device change that does not touch either button is
     * not a reason to redraw the car's player screen.
     *
     * @param carConnected whether a car is bound to the media session right now. Passed in because it is a
     *   fact about controllers rather than about audio devices, and this object is deliberately about lists.
     */
    fun buttons(outputs: List<AudioOutput>, selectedId: String?, carConnected: Boolean): OutputButtons = OutputButtons(
        // A car button with no car is a control that would hand routing back to the system for no
        // stated reason, so it is absent rather than inert.
        showCar = carConnected || car(outputs) != null,
        showHeadset = headsets(outputs).isNotEmpty(),
        headsetName = activeHeadset(outputs, selectedId)?.displayName,
    )
}

/**
 * PRODUCT_SPEC PLAY-002 — the state of the car's two output buttons.
 *
 * @property showCar whether the car button is published at all.
 * @property showHeadset whether the headset button is published at all.
 * @property headsetName the headset the book is actually in, for the button's own label, or `null` when the
 *   sound is somewhere else. The name a device advertises is shown to the listener and never logged (14.5).
 */
internal data class OutputButtons(val showCar: Boolean, val showHeadset: Boolean, val headsetName: String?) {
    companion object {
        /** Nothing connected and no car bound: neither button is published. */
        val None = OutputButtons(showCar = false, showHeadset = false, headsetName = null)
    }
}
