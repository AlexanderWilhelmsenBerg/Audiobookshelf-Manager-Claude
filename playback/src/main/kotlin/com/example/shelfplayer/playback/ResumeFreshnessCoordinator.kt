package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidence
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Why a pending externally requested Play stopped owning the next movement. */
internal enum class ResumeInvalidation {
    Pause,
    Seek,
    NotificationSkip,
    AutoRewind,
    Stop,
    MediaChanged,
}

/**
 * One fully evaluated Play request.
 *
 * The token is deliberately separate from [AcknowledgedPause.generation]. The baseline generation answers
 * whether the pause evidence is still true; [requestGeneration] answers whether this particular Play is
 * still the newest command. A second Play or an explicit Pause/seek must supersede an older network answer
 * even when both requests started from the same acknowledged pause.
 */
internal data class ResumeFreshnessPlan(
    val requestGeneration: Long,
    val bookId: LibraryItemId,
    val profileId: ProfileId,
    val baselineGeneration: Long?,
    val localPosition: Duration,
    val decision: ResumeFreshnessDecision,
)

/** The result of preparing one externally requested Play. */
internal sealed interface ResumePlayPreparation {
    /** The loaded item has no BookWave ownership metadata, so preserve Media3's old behaviour unchanged. */
    data object Bypass : ResumePlayPreparation

    /** A newer command/profile/book/baseline took ownership while this request was suspended. */
    data object Superseded : ResumePlayPreparation

    data class Ready(val plan: ResumeFreshnessPlan) : ResumePlayPreparation
}

/**
 * Issue #91 — the single freshness owner for every standard Play command reaching the media session.
 *
 * The coordinator never moves the player from a realtime push. The socket only supplies a candidate paired
 * with the acknowledged-pause generation that existed when it arrived. Movement happens later, and only
 * from [preparePlay], after the same checks that a REST result must pass.
 *
 * Mutable state is main-thread confined. Network work happens outside that thread, then the request token,
 * loaded owner/book and baseline generation are checked again before a plan may be returned.
 */
@Singleton
internal class ResumeFreshnessCoordinator @Inject constructor(
    private val playback: PlaybackRepository,
    private val profiles: ProfileRepository,
    private val baseline: ResumeBaseline,
    private val realtime: RealtimeProgressEvidenceStore,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) {
    private var player: Player? = null
    private var openedSession: OpenedSession? = null
    private var realtimeCandidate: RealtimeResumeCandidate? = null
    private var requestGeneration: Long = 0
    private var evidenceWatch: Job? = null

    /** The raw service-owned player whose loaded owner/book is the identity boundary. */
    fun attach(player: Player?) {
        this.player = player
        evidenceWatch?.cancel()
        evidenceWatch = null
        if (player == null) {
            openedSession = null
            realtimeCandidate = null
            requestGeneration += 1
            return
        }
        evidenceWatch = applicationScope.launch {
            realtime.updates.collect { evidence ->
                withContext(mainDispatcher) { rememberEvidence(evidence) }
            }
        }
    }

    /**
     * Captures the Audiobookshelf playback-session id before the item reaches Media3.
     *
     * That id is the only way to reject the server echo of BookWave's own session without putting a server
     * session identifier into `MediaMetadata.extras`, where external controllers could read it.
     */
    suspend fun onSessionOpened(session: PlaybackSession, invalidatePendingRequest: Boolean = true) =
        withContext(mainDispatcher) {
            openedSession = OpenedSession(
                profileId = session.profileId,
                bookId = session.bookId,
                remoteSessionId = session.id.takeIf(String::isNotBlank),
            )
            realtimeCandidate = null
            if (invalidatePendingRequest) requestGeneration += 1
        }

    /** Invalidates a suspended decision before the underlying explicit command is forwarded. */
    fun invalidate(origin: ResumeInvalidation) {
        requestGeneration += 1
        realtimeCandidate = null
        logger.debug(
            LogCategory.Playback,
            "A pending resume-freshness decision was invalidated",
            LogField.Public("generation", requestGeneration.toString()),
            LogField.Public("origin", origin.name),
        )
    }

    /**
     * Evaluates realtime first and REST second, under the same two-minute product rule.
     *
     * A missing baseline is intentionally fast: there is no agreed position against which a remote value can
     * be interpreted, so the old local resume behaviour is preserved with [FreshnessEvidenceSource.LocalUnverified].
     */
    suspend fun preparePlay(): ResumePlayPreparation {
        val context = withContext(mainDispatcher) { beginRequest() } ?: return ResumePlayPreparation.Bypass
        val acknowledged = context.baseline
        if (acknowledged == null) {
            return ResumePlayPreparation.Ready(
                context.plan(ResumeFreshnessDecision.Current(FreshnessEvidenceSource.LocalUnverified)),
            )
        }

        val realtimeDecision = context.remoteSessionId?.let { sessionId ->
            ResumeFreshnessPolicy.realtime(
                loadedProfile = context.profileId,
                loadedBook = context.bookId,
                loadedSessionId = sessionId,
                baseline = acknowledged,
                candidate = context.candidate,
            )
        }
        if (realtimeDecision != null) {
            return finish(context, realtimeDecision)
        }

        // PlaybackRepository resolves the active profile internally. Refuse to ask it under a different
        // profile than the item owns; otherwise a late Play during a profile switch can compare two accounts.
        if (profiles.activeProfileId() != context.profileId) return ResumePlayPreparation.Superseded
        val checked = withTimeoutOrNull(SERVER_CHECK_TIMEOUT) {
            playback.checkServerPosition(context.bookId, acknowledged)
        } ?: ExternalSessionCheck.Unavailable
        return finish(context, ResumeFreshnessPolicy.rest(acknowledged, checked))
    }

    /** Full validation before a plan is allowed to move or start audio. */
    suspend fun isCurrent(plan: ResumeFreshnessPlan): Boolean = profiles.activeProfileId() == plan.profileId &&
        withContext(mainDispatcher) {
            isCurrentOnMain(plan, requireBaseline = true)
        }

    /**
     * Runs the service-owned movement only after the final generation/profile/book/baseline check on the
     * player's main thread. The block begins in the same main-dispatch turn as that check, so a delayed REST
     * or realtime answer cannot win a gap between validation and the first raw player command.
     */
    suspend fun <T> withCurrentPlan(
        plan: ResumeFreshnessPlan,
        requireBaseline: Boolean = true,
        block: suspend () -> T,
    ): T? {
        if (profiles.activeProfileId() != plan.profileId) return null
        return withContext(mainDispatcher) {
            if (!isCurrentOnMain(plan, requireBaseline)) null else block()
        }
    }

    /**
     * Legacy gate for the removed second-`/play` adoption recovery.
     *
     * Review of PR #93 found that a fallback `/play` was not a side-effect-free candidate: the repository can
     * clear a finished flag before returning, and `BookChanges.onBookOpened` can suspend after committing part
     * of the replacement session. A request-generation check around those calls therefore cannot make the
     * transaction safe. The recovery is deliberately closed instead of letting an obsolete Play mutate local
     * progress/session/baseline/chapter state after a newer Pause, seek, Stop or media change won ownership.
     *
     * Keep the method while `PlaybackService` still carries the old fallback call site; it always returns
     * false, so that call site cannot reach `/play` or `BookChanges`. A failed adopted seek remains paused and
     * is diagnosable rather than being "recovered" by a stale state-changing request.
     */
    suspend fun requestStillCurrent(plan: ResumeFreshnessPlan): Boolean {
        val wasStillCurrent = profiles.activeProfileId() == plan.profileId &&
            withContext(mainDispatcher) {
                isCurrentOnMain(plan, requireBaseline = false)
            }
        if (wasStillCurrent) {
            logger.debug(
                LogCategory.Playback,
                "A failed adopted seek stayed paused; server-session reopen is disabled",
                LogField.Public("generation", plan.requestGeneration.toString()),
            )
        }
        return false
    }

    private suspend fun finish(context: RequestContext, decision: ResumeFreshnessDecision): ResumePlayPreparation {
        val plan = context.plan(decision)
        if (!isCurrent(plan)) return ResumePlayPreparation.Superseded
        logger.debug(
            LogCategory.Playback,
            "A resume-freshness decision completed",
            LogField.Public("generation", plan.requestGeneration.toString()),
            LogField.Public("source", decision.source.name),
            LogField.Public("movement", movementOf(context.baseline, decision)),
        )
        return ResumePlayPreparation.Ready(plan)
    }

    private fun beginRequest(): RequestContext? {
        val current = player ?: return null
        if (current.mediaItemCount == 0) return null
        val item = current.currentMediaItem ?: return null
        val profileId = MediaItems.ownerOf(item) ?: return null
        val bookId = MediaItems.bookIdOf(item)
        requestGeneration += 1
        val session = openedSession?.takeIf { it.profileId == profileId && it.bookId == bookId }
        return RequestContext(
            requestGeneration = requestGeneration,
            profileId = profileId,
            bookId = bookId,
            remoteSessionId = session?.remoteSessionId,
            localPosition = current.currentPosition.coerceAtLeast(0).milliseconds,
            baseline = baseline.acknowledged(bookId),
            // Without our own remote session id, a socket candidate cannot prove it is not our echo.
            candidate = realtimeCandidate.takeIf { session?.remoteSessionId != null },
        )
    }

    private fun rememberEvidence(evidence: RealtimeProgressEvidence) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        if (current.mediaItemCount == 0) return
        val profileId = MediaItems.ownerOf(item) ?: return
        val bookId = MediaItems.bookIdOf(item)
        val session = openedSession ?: return
        if (session.profileId != profileId || session.bookId != bookId) return
        if (evidence.profileId != profileId || evidence.progress.bookId != bookId) return
        val acknowledged = baseline.acknowledged(bookId) ?: return
        realtimeCandidate = RealtimeResumeCandidate(evidence, acknowledged.generation)
        logger.debug(
            LogCategory.Playback,
            "Realtime progress became resume evidence",
            LogField.Public("generation", acknowledged.generation.toString()),
            LogField.Public("origin", evidenceOrigin(session.remoteSessionId, evidence.sessionId)),
        )
    }

    private fun evidenceOrigin(localSessionId: String?, evidenceSessionId: String?): String = when {
        localSessionId == null || evidenceSessionId == null -> "unknownSession"
        evidenceSessionId == localSessionId -> "ownSession"
        else -> "otherSession"
    }

    private fun isCurrentOnMain(plan: ResumeFreshnessPlan, requireBaseline: Boolean): Boolean {
        if (requestGeneration != plan.requestGeneration) return false
        val current = player ?: return false
        if (current.mediaItemCount == 0) return false
        val item = current.currentMediaItem ?: return false
        if (MediaItems.bookIdOf(item) != plan.bookId || MediaItems.ownerOf(item) != plan.profileId) return false
        if (!requireBaseline || plan.baselineGeneration == null) return true
        return baseline.acknowledged(plan.bookId)?.generation == plan.baselineGeneration
    }

    private fun movementOf(acknowledged: AcknowledgedPause?, decision: ResumeFreshnessDecision): String =
        when (decision) {
            is ResumeFreshnessDecision.Current ->
                if (decision.source == FreshnessEvidenceSource.LocalUnverified) "unverified" else "local"
            is ResumeFreshnessDecision.Adopt -> when {
                acknowledged == null -> "adopt"
                decision.position > acknowledged.position -> "forward"
                decision.position < acknowledged.position -> "rewind"
                else -> "local"
            }
        }

    private data class OpenedSession(val profileId: ProfileId, val bookId: LibraryItemId, val remoteSessionId: String?)

    private data class RequestContext(
        val requestGeneration: Long,
        val profileId: ProfileId,
        val bookId: LibraryItemId,
        val remoteSessionId: String?,
        val localPosition: Duration,
        val baseline: AcknowledgedPause?,
        val candidate: RealtimeResumeCandidate?,
    ) {
        fun plan(decision: ResumeFreshnessDecision) = ResumeFreshnessPlan(
            requestGeneration = requestGeneration,
            bookId = bookId,
            profileId = profileId,
            baselineGeneration = baseline?.generation,
            localPosition = localPosition,
            decision = decision,
        )
    }

    private companion object {
        val SERVER_CHECK_TIMEOUT: Duration = 2.seconds
    }
}
