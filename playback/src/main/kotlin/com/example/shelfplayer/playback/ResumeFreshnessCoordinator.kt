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
 * A freshly opened server session is different from a resume of an already loaded paused book. `/play`
 * already chose the authoritative starting position, so its immediate first Play must not spend another
 * network round trip asking the same server the same question. [onSessionOpened] may therefore mint one
 * identity-bound [freshStart] token. That token survives exactly one expected media installation and then
 * [consumeFreshStart] consumes it exactly once at the forwarding-player boundary. A later media replacement
 * invalidates it like every other explicit movement. An armed session never gets that token, so a later
 * headset/car/notification Play still performs normal freshness reconciliation.
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
    private var freshStart: FreshStart? = null
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
            freshStart = null
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
     *
     * [initialPlayWillFollow] is true only when the caller opened this server session as part of the same
     * user action that will immediately issue Play. It is deliberately false for arm-only paths. A blank
     * session id is local/offline and can never mint a fresh-server token because no server chose its start.
     */
    suspend fun onSessionOpened(session: PlaybackSession, initialPlayWillFollow: Boolean = false) =
        withContext(mainDispatcher) {
            val opened = OpenedSession(
                profileId = session.profileId,
                bookId = session.bookId,
                remoteSessionId = session.id.takeIf(String::isNotBlank),
            )
            openedSession = opened
            freshStart = opened
                .takeIf { initialPlayWillFollow && it.remoteSessionId != null }
                ?.let { FreshStart(session = it, awaitingMediaInstall = true) }
            realtimeCandidate = null
            requestGeneration += 1
        }

    /**
     * Consumes the one immediate-Play exemption created by a fresh server `/play` response.
     *
     * Identity is re-read from the raw player at consumption time. The token is useful only after the one
     * media installation that belongs to the session open; a Play before that installation, or after any
     * later replacement, cannot use it. The token is consumed even on mismatch so it can never become a
     * later sticky bypass.
     */
    fun consumeFreshStart(): Boolean {
        val pending = freshStart ?: return false
        freshStart = null
        if (pending.awaitingMediaInstall) return false
        val current = player ?: return false
        if (current.mediaItemCount == 0) return false
        val item = current.currentMediaItem ?: return false
        return MediaItems.ownerOf(item) == pending.session.profileId &&
            MediaItems.bookIdOf(item) == pending.session.bookId
    }

    /** Invalidates a suspended decision before the underlying explicit command is forwarded. */
    fun invalidate(origin: ResumeInvalidation) {
        requestGeneration += 1
        realtimeCandidate = null
        freshStart = freshStartAfter(origin, freshStart)
        logger.debug(
            LogCategory.Playback,
            "A pending resume-freshness decision was invalidated",
            LogField.Public("generation", requestGeneration.toString()),
            LogField.Public("origin", origin.name),
        )
    }

    /**
     * The first MediaChanged after opening is the expected `setMediaItem` that installs that exact session.
     * Every later replacement is a new local ownership decision and therefore clears the exemption.
     */
    private fun freshStartAfter(origin: ResumeInvalidation, current: FreshStart?): FreshStart? = when {
        current == null -> null
        origin != ResumeInvalidation.MediaChanged -> null
        current.awaitingMediaInstall -> current.copy(awaitingMediaInstall = false)
        else -> null
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

    private data class FreshStart(val session: OpenedSession, val awaitingMediaInstall: Boolean)

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
