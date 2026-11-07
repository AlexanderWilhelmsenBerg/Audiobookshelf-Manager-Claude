package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer

/**
 * Synchronous arbitration on Media3's player thread. Specialists retain their state and policies;
 * this owner orders their cancellation before a new command can produce playback effects.
 * It never queues a safety command behind suspended network, database or route work.
 */
internal class PlaybackGovernor(private val source: Source, private val resumeEffects: PlaybackResumeEffects) {
    interface Source {
        fun player(): ExoPlayer?
        fun authority(): MutedPositionEffects.Context
        fun advanceCommand()
        fun finishMovement(movement: PlaybackMovement): Boolean
        fun clearButtonInput()
        fun invalidateResume(origin: ResumeInvalidation)
        fun invalidateSleep(origin: ResumeInvalidation)
        fun transportIntent()
        fun stopped()
        fun cancelContinuity(preserveHeard: Boolean)
        fun cancelRetry()
        fun prepareTransport(play: Boolean, player: ExoPlayer): () -> Boolean
        fun synchronizeOutput()
        fun cancelResumeEffect()
        fun observedPlay()
        fun paused(userInitiated: Boolean)
        fun platformInterruption(reason: Int)
        fun safetyInterruption()
    }

    private var closed = false
    val acceptingCommands: Boolean get() = !closed

    /** Session and sleep-grace Play share one content/freshness path and revocable intent scope. */
    suspend fun resume(explicit: Boolean, bypassFreshness: Boolean, stillAuthorized: () -> Boolean): Boolean {
        val player = source.player() ?: return false
        val expected = source.authority()
        val authorized = {
            val actual = source.authority()
            !closed && source.player() === player && actual.command == expected.command &&
                actual.profile == expected.profile && stillAuthorized()
        }
        return authorized() && resumeEffects.resume(explicit, bypassFreshness, authorized)
    }

    fun invalidate(origin: ResumeInvalidation): Boolean {
        if (closed) return false
        source.clearButtonInput()
        val movement = when (origin) {
            ResumeInvalidation.MediaChanged -> PlaybackMovement.MediaChanged
            ResumeInvalidation.Seek, ResumeInvalidation.NotificationSkip -> PlaybackMovement.Seek
            ResumeInvalidation.Pause, ResumeInvalidation.Stop, ResumeInvalidation.AutoRewind -> null
        }
        if (movement != null && !source.finishMovement(movement)) return false
        source.advanceCommand()
        val player = source.player()
        val expected = source.authority()
        if (origin == ResumeInvalidation.Stop) source.stopped()
        source.invalidateResume(origin)
        source.invalidateSleep(origin)
        cancelPending()
        return current(player, expected)
    }

    /** Called even for duplicate Pause; a raw player callback is not evidence of listener intent. */
    fun transport(play: Boolean, player: ExoPlayer): Boolean {
        if (closed || source.player() !== player) return false
        val route = source.prepareTransport(play, player)
        source.advanceCommand()
        val expected = source.authority()
        source.cancelRetry()
        source.transportIntent()
        source.cancelContinuity(preserveHeard = play)
        if (!current(player, expected)) return false
        // Routing may synchronously invoke Media3 listeners and newer commands.
        val accepted = route()
        return accepted && current(player, expected)
    }

    /** Explicit UI and dashboard output commands share cancellation, including unchanged selections. */
    fun output(effect: () -> Boolean): Boolean {
        if (closed) return false
        val player = source.player()
        val expected = source.authority()
        source.clearButtonInput()
        source.transportIntent()
        cancelPending()
        if (!current(player, expected)) return false
        val accepted = effect()
        source.synchronizeOutput()
        return accepted && !closed
    }

    /** Item/profile/queue boundaries revoke work; their specialists still own bookkeeping. */
    fun contextChanged() {
        source.clearButtonInput()
        cancelPending()
    }

    /** Raw observations carry cause, never a guessed physical controller identity. */
    fun observeTransport(input: PlaybackTransportInput.Interpretation, routeSafetyPause: Boolean) {
        if (closed) return
        source.cancelResumeEffect()
        if (input.isPlay) {
            source.observedPlay()
        } else {
            source.paused(input.isUserPause)
            val reason = input.interruptionReason
            when {
                reason != null -> source.platformInterruption(reason)
                routeSafetyPause -> source.safetyInterruption()
                else -> cancelPending()
            }
        }
    }

    fun close() {
        closed = true
        source.advanceCommand()
        contextChanged()
    }

    private fun cancelPending() {
        source.cancelRetry()
        source.cancelContinuity(preserveHeard = false)
    }

    private fun current(player: ExoPlayer?, expected: MutedPositionEffects.Context): Boolean =
        !closed && source.player() === player && source.authority() == expected
}
