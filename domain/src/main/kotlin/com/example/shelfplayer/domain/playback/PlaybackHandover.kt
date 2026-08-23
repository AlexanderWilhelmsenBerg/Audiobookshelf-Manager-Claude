package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.ProfileId

/**
 * PRODUCT_SPEC 6.5 steps 2–3 — ending the outgoing account's playback before the context changes.
 *
 * ### What 6.5 asks for, and what the code did instead
 *
 * The requirement is an ordered list: flush the progress, pause, *then* change the profile context. Until
 * this existed `SwitchProfileUseCase` had no playback collaborator at all — it wrote the new selection and
 * left the player running the previous account's book, still journaling a position every five seconds. The
 * order in the requirement is not decoration; it is what stops the outgoing listener's position being
 * written after the incoming listener's row has become the one "the active profile" names.
 *
 * ### A seam, for the reason [StartupPlayer] is one
 *
 * The player lives in `:playback`, which depends on `:domain`. A use case that named it would invert the
 * dependency, so `:app` performs the wiring (PRODUCT_SPEC 9.3) exactly as it does for [StartupPlayer].
 *
 * ### The default does nothing, and doing nothing is safe here
 *
 * A graph with none bound switches profiles without touching the player, which is what the app did before
 * this interface existed. That is the *previous* behaviour rather than a new failure mode — and the other
 * half of 6.5's fix, the owner stamped into each loaded book (`MediaItems.KEY_OWNER_PROFILE_ID`), keeps a
 * write on the right row even when nothing here runs.
 */
interface PlaybackHandover {

    /**
     * Stops [outgoing]'s playback and returns once its last position has been written.
     *
     * **Suspends until the write has landed.** That is the contract: the caller changes the active profile
     * on the next line, and an implementation that launched the flush instead would have re-created the
     * race the interface exists to close.
     *
     * Must not throw. A switch that failed because the player could not be reached would strand the user
     * on an account they were leaving, which is the same reasoning `SwitchProfileUseCase` applies to a
     * credential that will not load.
     */
    suspend fun handOver(outgoing: ProfileId)

    companion object {
        /** What a graph with no player bound uses: the switch happens, the player is left alone. */
        val None: PlaybackHandover = object : PlaybackHandover {
            override suspend fun handOver(outgoing: ProfileId) = Unit
        }
    }
}
