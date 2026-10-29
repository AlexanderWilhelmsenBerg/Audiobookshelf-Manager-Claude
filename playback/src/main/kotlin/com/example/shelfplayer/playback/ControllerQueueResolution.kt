package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
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
    private val profileGeneration: () -> Long,
    private val explicitSelectionSequence: () -> Long,
    private val serviceOwner: () -> Any?,
    private val activeProfileId: suspend () -> ProfileId?,
    private val isProfileLocked: suspend () -> Boolean,
    private val prepare: suspend (LibraryItemId, Duration?) -> CarPostConnectRestorer.Prepared?,
    private val accept: suspend (CarPostConnectRestorer.Prepared, suspend () -> Boolean, () -> Unit) -> Boolean,
) {
    class Claim internal constructor(
        internal val commandGeneration: Long,
        internal val profileGeneration: Long,
        internal val selectionSequence: Long,
        internal val owner: Any?,
    )

    fun capture() = Claim(commandGeneration(), profileGeneration(), explicitSelectionSequence(), serviceOwner())

    /** A locked/no-candidate reply must still belong to the request that asked for it. */
    fun requireUnchanged(claim: Claim) {
        if (!isUnchanged(claim)) superseded()
    }

    /** Check before and after other suspending candidate reads, not only after `/play`. */
    suspend fun requireCurrent(claim: Claim): ProfileId {
        val profile = activeProfileId()
        if (profile == null || !isCurrent(claim, profile)) superseded()
        return profile
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
        val profile = requireCurrent(claim)
        val prepared = prepare(bookId, startAt)
        // A failed network request is still stale if Stop or a different request won while it failed.
        requireCurrent(claim)
        if (prepared == null) return null
        if (prepared.session.profileId != profile) superseded()
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
