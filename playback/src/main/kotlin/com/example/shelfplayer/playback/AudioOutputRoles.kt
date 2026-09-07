package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind

/**
 * PRODUCT_SPEC PLAY-002 — what the car's two routing buttons mean.
 *
 * The actions name intent rather than Android transport types. *Car* releases BookWave's per-track
 * preference and lets Android Auto own media routing. *Headset* targets only wearable outputs plus classic
 * A2DP devices Android cannot classify more precisely. The built-in phone speaker is never a target.
 */
internal object AudioOutputRoles {

    /**
     * Framework-reported media route first; explicit choice is the best fallback below API 33.
     *
     * **A reported speaker never masks a reported anything-else.** `getAudioDevicesForAttributes` may name
     * more than one device, and the order is `AudioManager.getDevices`' iteration order — so taking the
     * first active output made the answer depend on how the platform happened to enumerate. When the
     * built-in speaker came first, a connected dashboard or headset was invisible and both output glyphs
     * went dark, which is one of the ways a device run found the Car action never lighting.
     *
     * Only speakers are demoted, deliberately. Ordering car ahead of headset would change which device
     * `activeHeadset` reports while both are live, and that is exactly the fact `HeadsetHold` uses to stop
     * a car stealing a book out of someone's ears.
     */
    fun current(outputs: List<AudioOutput>, selectedId: String?): AudioOutput? {
        val active = outputs.filter(AudioOutput::isActive)
        return active.firstOrNull { !it.isSpeaker }
            ?: active.firstOrNull()
            ?: outputs.firstOrNull { it.id == selectedId }
    }

    /**
     * Everything the headset action may consider.
     *
     * [AudioOutputRole.Ambiguous] is deliberately a candidate rather than a headset classification: classic
     * A2DP is the transport AirPods use, but it is also used by speakers and some cars. Keeping the ambiguity
     * in the model lets a later role override narrow it without changing routing policy again.
     */
    fun headsets(outputs: List<AudioOutput>): List<AudioOutput> = outputs.filter(AudioOutput::isHeadsetCandidate)

    /** The wearable/candidate route the book is actually using or was explicitly pointed at. */
    fun activeHeadset(outputs: List<AudioOutput>, selectedId: String?): AudioOutput? =
        current(outputs, selectedId)?.takeIf(AudioOutput::isHeadsetCandidate)

    /** A platform audio bus is useful evidence and diagnostics, even though the Car action does not force it. */
    fun car(outputs: List<AudioOutput>): AudioOutput? = outputs.firstOrNull { it.kind == DeviceKind.Car }

    /**
     * Car always means *release BookWave's preferred output*.
     *
     * `null` is ExoPlayer's Automatic route. Android Auto/AAOS already owns normal car routing, so clearing
     * the app-specific preference is more robust than guessing which A2DP device is the dashboard.
     */
    // Constant by design, and a function so the decision has a name, a call site and a test. Inlining the
    // `null` would leave `audioOutputs.select(null)` in PlaybackService with nothing saying why.
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun carTarget(outputs: List<AudioOutput>): String? = null

    /**
     * The next headset candidate, or `null` when there is nothing useful to target.
     *
     * When Android Auto is active and the framework has moved media onto an ambiguous A2DP route, that
     * active ambiguous route is skipped if another candidate exists. This is the common projected-car case:
     * the active A2DP endpoint is the dashboard, while the inactive one is the headset the listener can
     * return to. A single ambiguous endpoint remains a harmless re-selection for stale controls.
     */
    fun nextHeadset(outputs: List<AudioOutput>, selectedId: String?): String? {
        val candidates = headsets(outputs)
        if (candidates.isEmpty()) return null

        val here = activeHeadset(outputs, selectedId)
        // The framework moved us onto an ambiguous A2DP route we did not ask for: on a projected car that
        // is the dashboard, so step past it rather than re-selecting it.
        val hereLooksLikeTheDashboard = here != null &&
            here.role == AudioOutputRole.Ambiguous &&
            here.isActive &&
            selectedId != here.id
        if (hereLooksLikeTheDashboard) {
            val alternatives = candidates.filterNot { it.id == here?.id }
            if (alternatives.isNotEmpty()) return alternatives.first().id
        }
        // -1 covers both "the book is not on a headset" and "it is on one that has since gone".
        val index = here?.let { active -> candidates.indexOfFirst { it.id == active.id } } ?: -1
        if (index == -1) return candidates.first().id
        return candidates[(index + 1) % candidates.size].id
    }

    /**
     * PRODUCT_SPEC PLAY-002 / ROUTE-002 — the output to pin when a book starts, or `null` to leave it alone.
     *
     * ### The request, and what is actually knowable
     *
     * The owner asked for *"if play button comes from headset, start in that headset."* **Which device sent
     * a play cannot be known.** A Bluetooth AVRCP passthrough loses the device before the framework sees
     * it: the Bluetooth stack synthesises `KeyEvent(action, keyCode)`, whose two-argument constructor hard
     * codes `deviceId` to `KeyCharacterMap.VIRTUAL_KEYBOARD`, and the JNI upcall carrying the press into
     * Java takes no `BluetoothDevice` at all. Media3's own notification buttons synthesise a byte-identical
     * event, and `ControllerInfo` carries a package name and no device. So earbuds pressing play and a car
     * stereo pressing play are indistinguishable, and any policy reading a media button as *headset
     * evidence* would let a car stereo pull the book onto whatever this app thinks is a headset.
     *
     * ### What is done instead
     *
     * The route is read rather than the presser. When a book starts and the platform is already routing to
     * a headset, the only thing this does is **retract BookWave's own disagreement** with that route. It can
     * never move audio somewhere the platform was not already sending it, which is what makes it safe.
     *
     * Every guard below is load-bearing:
     * - a route BookWave cannot call a headset candidate is left alone, which structurally excludes the
     *   phone speaker, a car bus, a dock and an unknown sink;
     * - the ambiguous-dashboard case is excluded by the same predicate [buttons] uses, so a projected car's
     *   A2DP link is never mistaken for earbuds;
     * - and with no selection, or one that already agrees, there is nothing to correct.
     *
     * **Below API 33 this is inert by construction, and that is correct rather than a gap.** `isActive`
     * degenerates to "the output this app chose", so the disagreement test can never fire — and the
     * platform reports no route on those releases, so anything else would be a guess that moves a book to a
     * device nobody asked for. Do not "fix" the inertness.
     */
    fun startTarget(outputs: List<AudioOutput>, selectedId: String?, carConnected: Boolean): String? {
        val routed = outputs.firstOrNull(AudioOutput::isActive) ?: return null
        if (!routed.isHeadsetCandidate) return null
        val looksLikeDashboard = carConnected &&
            routed.role == AudioOutputRole.Ambiguous &&
            routed.id != selectedId
        if (looksLikeDashboard) return null
        return routed.id.takeIf { selectedId != null && selectedId != it }
    }

    /** What Android Auto/notification should publish right now. */
    fun buttons(outputs: List<AudioOutput>, selectedId: String?, carConnected: Boolean): OutputButtons {
        val candidates = headsets(outputs)
        val current = current(outputs, selectedId)
        val activeLooksLikeCar = carConnected &&
            current?.role == AudioOutputRole.Ambiguous &&
            current.id != selectedId
        val availableHeadsets = if (activeLooksLikeCar) {
            candidates.filterNot { it.id == current?.id }
        } else {
            candidates
        }
        val headsetRoute = current?.takeIf { output ->
            output.isHeadset ||
                (output.role == AudioOutputRole.Ambiguous && (!carConnected || output.id == selectedId))
        }
        return OutputButtons(
            showCar = carConnected || car(outputs) != null,
            showHeadset = availableHeadsets.isNotEmpty(),
            headsetName = headsetRoute?.displayName,
            onHeadset = headsetRoute != null,
            // **Positive evidence for a car**, not the absence of everything else. This predicate has now
            // been narrowed twice from the same mistake: it began as `!onHeadset`, which lit the car for the
            // phone speaker, and the speaker guard that replaced it still lit the car for every *known
            // non-car* route — `OutputDevices.roleOf` sends USB devices, USB accessories, docks and HDMI to
            // `Other` through its `else`, so a DAC or a dock carrying the book drew a confident car glyph.
            //
            // Only two things are evidence. A `TYPE_BUS` route **is** the car's own audio bus, whether or
            // not a controller is bound. And the dashboard case: an ambiguous A2DP route nobody selected
            // while a car is bound, which ADR-0029 §4 already refuses to call a headset.
            //
            // Everything else leaves both glyphs dark, which the [OutputButtons] KDoc explains is a
            // legitimate state — an indicator that is sometimes silent beats one that is sometimes wrong.
            onCar = current?.role == AudioOutputRole.Car || activeLooksLikeCar,
        )
    }
}

/**
 * Visible state of the two output actions.
 *
 * [onHeadset] and [onCar] are *which one is lit*, and they answer the device report that the current output
 * could not be seen anywhere on the car's player. Both are fields rather than derived at the call site:
 * `onHeadset` could be read as `headsetName != null`, and the two happen to agree today only because a
 * route BookWave is confident about always has an advertised name — too thin a coincidence for a light.
 *
 * **They are not complements, and two reviews are why.** `onCar` began as `!onHeadset`, which lit the car
 * whenever the book was anywhere BookWave could not call a headset — including the phone speaker, which
 * `AudioOutputRouter.select` accepts so the phone's own chooser works, and including a route the platform
 * had not reported at all. Excluding those two still left every *known non-car* route lighting the car: a
 * USB DAC, a dock and an HDMI sink all reach `AudioOutputRole.Other` through `roleOf`'s `else`. Each round
 * drew a confident car glyph over something that was not the car, so [onCar] now asks for evidence *of a
 * car* — the `TYPE_BUS` bus, or the ambiguous dashboard — rather than for the failure of other tests.
 *
 * So **neither being lit is a legitimate state**: the speaker carries the audio, or the route is unknown.
 * An indicator that is sometimes silent is worth more than one that is always sure and sometimes wrong.
 */
internal data class OutputButtons(
    val showCar: Boolean,
    val showHeadset: Boolean,
    val headsetName: String?,
    val onHeadset: Boolean = false,
    val onCar: Boolean = false,
) {
    companion object {
        val None = OutputButtons(
            showCar = false,
            showHeadset = false,
            headsetName = null,
            onHeadset = false,
            onCar = false,
        )
    }
}
