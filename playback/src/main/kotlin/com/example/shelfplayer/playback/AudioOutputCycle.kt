package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput

/**
 * PRODUCT_SPEC PLAY-002 — one press moves the book to the next output, because a list is not available.
 *
 * ### Why a cycle rather than the menu the phone has
 *
 * The Android Auto player screen can hold an app's button — a `CommandButton` in `SLOT_OVERFLOW` reaches it
 * as a `PlaybackStateCompat` custom action. What that button **cannot** do is open a list: a custom action
 * sends a session command and nothing more, no API pushes the car to a browse node, and the player template
 * has no submenu. ADR-0027 recorded the missing button as unbuildable; only the *list from the button* was.
 *
 * So the button steps. That is the shape a car already uses for stateful controls — shuffle and repeat are
 * the same one press, next value — and the browse tab still holds the full list for a deliberate choice.
 *
 * ### The order, and why *Automatic* is in it
 *
 * `[Automatic] + the connected outputs, in the order the platform reports them`. *Automatic* is a real
 * destination rather than a null state: it is the only way back to letting the system route, and a cycle
 * that could not reach it would strand a listener on a device they picked once.
 */
internal object AudioOutputCycle {

    /**
     * The id to select next, or `null` for *Automatic*.
     *
     * An id that is not in [outputs] — the device left between the press and this call — returns *Automatic*
     * rather than guessing a neighbour. The list it would have indexed into no longer contains it, so any
     * neighbour would be an arbitrary device, and arbitrarily moving a book's audio is the thing this whole
     * feature exists not to do.
     */
    fun next(outputs: List<AudioOutput>, selectedId: String?): String? {
        if (outputs.isEmpty()) return null
        val order: List<String?> = listOf(null) + outputs.map(AudioOutput::id)
        val index = order.indexOf(selectedId)
        if (index == -1) return null
        return order[(index + 1) % order.size]
    }

    /**
     * What the button should be labelled with: where the audio **is**, falling back to what was asked for.
     *
     * The route first, because that is the fact a listener wants from a control that names a destination,
     * and since ADR-0027's amendment the app can read it on API 33+. Below that — or when the platform
     * reports a route this app does not recognise — the choice stands in, and `null` means *Automatic*:
     * nothing chosen and nothing readable, which is the honest answer rather than a guessed device.
     */
    fun current(outputs: List<AudioOutput>, selectedId: String?): AudioOutput? =
        outputs.firstOrNull(AudioOutput::isActive) ?: outputs.firstOrNull { it.id == selectedId }
}
