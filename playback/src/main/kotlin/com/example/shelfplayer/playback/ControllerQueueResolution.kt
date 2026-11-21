package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.PlaybackOpenOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration

/**
 * PLAY-001 / AUTH-002 / #289 — asynchronous controller resolution owns a request, never live playback.
 *
 * Capture on the Media3 application thread before launching the callback future. The service's existing
 * command, profile and selection identities invalidate suspended work, including an A→B→A profile switch. Preparing
 * a remote queue cannot change History, sleep, freshness, or the live session until guarded acceptance.
 * A superseded request fails its future rather than returning an empty successful queue, which Media3
 * would install over the newer listener choice.
 */
internal class ControllerQueueResolution(
    private val commandGeneration: () -> Long,
    private val profileGeneration: () -> Long?,
    private val explicitSelectionSequence: () -> Long,
    private val serviceOwner: () -> Any?,
    private val activeProfileId: suspend () -> ProfileId?,
    private val isProfileLocked: suspend () -> Boolean,
    private val prepare: suspend (PlaybackOpenOwner, LibraryItemId, Duration?) -> CarPostConnectRestorer.Prepared?,
    private val accept: suspend (CarPostConnectRestorer.Prepared, suspend () -> Boolean, () -> Unit) -> Boolean,
) {
    class Claim internal constructor(
        internal val commandGeneration: Long,
        internal val profileGeneration: Long?,
        internal val selectionSequence: Long,
        internal val owner: Any?,
    )

    fun capture() = Claim(commandGeneration(), profileGeneration(), explicitSelectionSequence(), serviceOwner())

    /** A locked/no-candidate reply must still belong to the request that asked for it. */
    fun requireUnchanged(claim: Claim) {
        if (!isUnchanged(claim)) superseded()
    }

    /** Check before and after other suspending candidate reads, not only after `/play`. */
    suspend fun requireCurrent(claim: Claim): PlaybackOpenOwner {
        val profile = activeProfileId() ?: superseded()
        val generation = claim.profileGeneration ?: superseded()
        if (!isCurrent(claim, profile)) superseded()
        return PlaybackOpenOwner(profile, generation)
    }

    fun requireItemOwner(item: MediaItem, owner: PlaybackOpenOwner) {
        val generation = item.requestMetadata.extras?.getLong(MediaItems.KEY_OPEN_PROFILE_GENERATION, -1L)
        if (MediaItems.ownerOf(item) != owner.profileId || generation != owner.generation) superseded()
    }

    suspend fun resolve(claim: Claim, bookId: LibraryItemId, startAt: Duration?): MediaItems.Queue? {
        val prepared = prepareForRequest(claim, bookId, startAt) ?: return null
        acceptPrepared(claim, prepared)
        return prepared.queue
    }

    /** Batch callbacks prepare all candidates first; they accept only the first resolved/current book. */
    suspend fun prepareForRequest(
        claim: Claim,
        bookId: LibraryItemId,
        startAt: Duration?,
    ): CarPostConnectRestorer.Prepared? {
        val owner = requireCurrent(claim)
        val prepared = prepare(owner, bookId, startAt)
        // A failed network request is still stale if Stop or a different request won while it failed.
        requireCurrent(claim)
        if (prepared == null) return null
        if (prepared.session.profileId != owner.profileId) superseded()
        return prepared
    }

    suspend fun acceptPrepared(claim: Claim, prepared: CarPostConnectRestorer.Prepared) {
        val accepted = accept(prepared, { isCurrent(claim, prepared.session.profileId) }) {}
        if (!accepted) superseded()
    }

    private suspend fun isCurrent(claim: Claim, profile: ProfileId): Boolean {
        currentCoroutineContext().ensureActive()
        val allowed = activeProfileId() == profile && !isProfileLocked()
        currentCoroutineContext().ensureActive()
        return allowed && isUnchanged(claim)
    }

    fun isUnchanged(claim: Claim): Boolean = claim.owner != null && serviceOwner() === claim.owner &&
        commandGeneration() == claim.commandGeneration && profileGeneration() == claim.profileGeneration &&
        explicitSelectionSequence() == claim.selectionSequence

    private fun superseded(): Nothing = throw CancellationException("Controller media request superseded")
}
