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

    /** Framework-reported media route first; explicit choice is the best fallback below API 33. */
    fun current(outputs: List<AudioOutput>, selectedId: String?): AudioOutput? =
        outputs.firstOrNull(AudioOutput::isActive) ?: outputs.firstOrNull { it.id == selectedId }

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
            // Evidence rather than "not the headset". A review found the complement lighting the car while
            // the *speaker* carried the audio — reachable, because `AudioOutputRouter.select` deliberately
            // accepts the speaker so the phone's own chooser works. Three conditions, and the first two are
            // the ones the complement skipped: the route has to be known, and it has to not be a speaker.
            onCar = current != null && !current.isSpeaker && headsetRoute == null,
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
 * **They are not complements, and a review is why.** `onCar` began as `!onHeadset`, which lit the car
 * whenever the book was anywhere BookWave could not call a headset — including the phone speaker, which
 * `AudioOutputRouter.select` accepts so the phone's own chooser works, and including a route the platform
 * had not reported at all. Both cases drew a confident car glyph over something that was not the car.
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
