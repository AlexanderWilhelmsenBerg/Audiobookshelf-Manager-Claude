package com.example.shelfplayer.playback

import android.media.AudioTrack
import androidx.media3.common.Player

/** Diagnostic snapshot only; never authorizes playback, routing, focus or progress writes. */
internal data class AudioTrackPlaybackSnapshot(
    val playWhenReady: Boolean,
    val state: Int,
    val suppression: Int,
    val muted: Boolean,
    val positionMs: Long,
    val bufferedMs: Long,
    val loading: Boolean,
    val generation: Long?,
    val selection: Long,
    val automaticTakeBack: Boolean? = null,
    val itemIndex: Int? = null,
) {
    fun timingEvidence(): String = "playerState=$state playWhenReady=$playWhenReady " +
        "suppression=$suppression muted=$muted positionMs=$positionMs bufferedMs=$bufferedMs loading=$loading " +
        "itemIndex=${itemIndex ?: "unknown"} generation=${generation ?: "unknown"} selectionSequence=$selection"
}

/** Frame consumption is separate from route identity and is still not proof of acoustic audibility. */
internal class AudioTrackProgressEvidence {
    enum class Kind { Baseline, Advancing, CounterReset, NoFrameProgress }

    data class Observation(
        val kind: Kind,
        val sample: AudioTrackTimingSample,
        val playback: AudioTrackPlaybackSnapshot,
        val stationaryMs: Long,
        val frameDelta: Long,
    ) {
        fun evidence(): String = "track-progress event=${kind.name} epoch=${sample.observerEpoch} " +
            "stationaryMs=$stationaryMs headFrames=${sample.headFrames.measured()} frameDelta=$frameDelta " +
            "nativeState=${sample.playState.measured()} playerState=${playback.state} " +
            "playWhenReady=${playback.playWhenReady} suppression=${playback.suppression} muted=${playback.muted} " +
            "positionMs=${playback.positionMs} bufferedMs=${playback.bufferedMs} loading=${playback.loading} " +
            "generation=${playback.generation.measured()} selectionSequence=${playback.selection} " +
            "automaticTakeBack=${playback.automaticTakeBack.measured()} evidence=frame-consumption-only"

        private fun Any?.measured(): String = this?.toString() ?: "unknown"
    }

    private data class Scope(
        val capture: Long,
        val epoch: Long,
        val generation: Long?,
        val selection: Long,
        val automaticTakeBack: Boolean?,
    )
    private var scope: Scope? = null
    private var head: Long? = null
    private var advancedAt = 0L
    private var kind: Kind? = null

    @Synchronized
    fun observe(capture: Long, sample: AudioTrackTimingSample, playback: AudioTrackPlaybackSnapshot?): Observation? =
        when {
            playback == null -> reset()
            sample.headFrames == null -> reset()
            !eligible(sample, playback) -> reset()
            else -> update(capture, sample, playback)
        }

    private fun update(capture: Long, sample: AudioTrackTimingSample, state: AudioTrackPlaybackSnapshot): Observation? {
        val frames = requireNotNull(sample.headFrames)
        val next = Scope(capture, sample.observerEpoch, state.generation, state.selection, state.automaticTakeBack)
        if (needsBaseline(next, sample.monotonicNs)) return baseline(next, sample, state, Kind.Baseline)
        if (frames < requireNotNull(head)) return baseline(next, sample, state, Kind.CounterReset)
        return movement(sample, state, frames)
    }

    private fun needsBaseline(next: Scope, now: Long): Boolean = scope != next || head == null || now < advancedAt

    private fun reset(): Observation? {
        scope = null
        head = null
        kind = null
        return null
    }

    private fun baseline(
        next: Scope,
        sample: AudioTrackTimingSample,
        state: AudioTrackPlaybackSnapshot,
        observed: Kind,
    ): Observation {
        scope = next
        head = sample.headFrames
        advancedAt = sample.monotonicNs
        kind = observed
        return Observation(observed, sample, state, 0, 0)
    }

    private fun movement(
        sample: AudioTrackTimingSample,
        state: AudioTrackPlaybackSnapshot,
        frames: Long,
    ): Observation? {
        val delta = frames - requireNotNull(head)
        val stationary = sample.monotonicNs - advancedAt
        head = frames
        val observed = when {
            delta > 0 -> Kind.Advancing
            stationary >= STATIONARY_NS -> Kind.NoFrameProgress
            else -> return null
        }
        if (delta > 0) advancedAt = sample.monotonicNs
        if (kind == observed) return null
        kind = observed
        return Observation(observed, sample, state, if (delta > 0) 0 else stationary / NS_PER_MS, delta)
    }

    private fun eligible(sample: AudioTrackTimingSample, playback: AudioTrackPlaybackSnapshot): Boolean =
        playback.playWhenReady && !playback.muted && playback.suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            playback.state in PLAYABLE_STATES && sample.playState in NATIVE_STATES

    private companion object {
        const val STATIONARY_NS = 2_000_000_000L
        const val NS_PER_MS = 1_000_000L
        val PLAYABLE_STATES = setOf(Player.STATE_READY, Player.STATE_BUFFERING)

        // Brief renderer pauses during READY/BUFFERING flapping must not reset a still-requested Play.
        val NATIVE_STATES = setOf(AudioTrack.PLAYSTATE_PLAYING, AudioTrack.PLAYSTATE_PAUSED)
    }
}
