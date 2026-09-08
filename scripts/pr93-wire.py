from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/BookChanges.kt",
    """    private val autoRewind: AutoRewindController,
    private val resumeBaseline: ResumeBaseline,
) {""",
    """    private val autoRewind: AutoRewindController,
    private val resumeBaseline: ResumeBaseline,
    private val resumeFreshness: ResumeFreshnessCoordinator,
) {""",
)
replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/BookChanges.kt",
    """        resumeBaseline.stageServerPosition(
            bookId = session.bookId,
            position = MediaItems.serverStartPositionFor(session),
        )
        sleepTimer.onBookChanged(session.chapters)""",
    """        resumeBaseline.stageServerPosition(
            bookId = session.bookId,
            position = MediaItems.serverStartPositionFor(session),
        )
        // Issue #91 — keep the remote ABS session id service-side so realtime evidence can reject
        // BookWave's own sync echo without exposing that identifier through MediaMetadata extras.
        resumeFreshness.onSessionOpened(session)
        sleepTimer.onBookChanged(session.chapters)""",
)
replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/BookChanges.kt",
    "Four singletons need the same news, in the same order, every time a session opens:",
    "Five singletons need the same news, in the same order, every time a session opens:",
)

replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt",
    """    @Inject
    internal lateinit var resumeBaseline: ResumeBaseline

    @Inject
    internal lateinit var logger: Logger""",
    """    @Inject
    internal lateinit var resumeBaseline: ResumeBaseline

    /** Issue #91 — one freshness decision shared by every standard Media3 Play surface. */
    @Inject
    internal lateinit var resumeFreshness: ResumeFreshnessCoordinator

    @Inject
    internal lateinit var logger: Logger""",
)
replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt",
    """        val exoPlayer = players.create(buffer = settings.buffer, focus = settings.focusBehaviour)
            .also { player = it }
        exoPlayer.addListener(PlayerEvents())
        session = MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())""",
    """        val exoPlayer = players.create(buffer = settings.buffer, focus = settings.focusBehaviour)
            .also { player = it }
        exoPlayer.addListener(PlayerEvents())
        resumeFreshness.attach(exoPlayer)
        val sessionPlayer = ResumeFreshnessPlayer(
            delegate = exoPlayer,
            preparePlay = { future { handleFreshnessPlay() } },
            invalidate = resumeFreshness::invalidate,
        )
        // Issue #91 — controllers see the forwarding player; service-owned timers/sync/routing below keep
        // the raw ExoPlayer so internal atomic operations cannot recursively enter the external Play gate.
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())""",
)
replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt",
    """        sessionSync.onShutdown()
        sessionSync.attach(null)
        autoRewind.attach(null)""",
    """        sessionSync.onShutdown()
        sessionSync.attach(null)
        resumeFreshness.attach(null)
        autoRewind.attach(null)""",
)

marker = """    /**
     * PRODUCT_SPEC SYNC-002 — the whole adopt-a-remote-position operation, on the player this service owns.
"""
insertion = """    /**
     * Issue #91 — handles one standard Play after [ResumeFreshnessPlayer] intercepted it.
     *
     * No controller identity is needed here: every controller that can send standard Play reaches the same
     * forwarding player, which is the point of putting the decision at the Media3 boundary rather than in a
     * screen. A superseded request does nothing; the newer seek/Pause/book/profile command already owns the
     * player.
     */
    private suspend fun handleFreshnessPlay() {
        when (val prepared = resumeFreshness.preparePlay()) {
            ResumePlayPreparation.Bypass -> resumeLoadedCurrent()
            ResumePlayPreparation.Superseded -> Unit
            is ResumePlayPreparation.Ready -> applyFreshnessPlan(prepared.plan)
        }
    }

    private suspend fun applyFreshnessPlan(plan: ResumeFreshnessPlan) {
        if (!resumeFreshness.isCurrent(plan)) return
        applicationScope.launch { recordFreshnessHistory(plan) }
        when (val decision = plan.decision) {
            is ResumeFreshnessDecision.Current -> resumeLoadedCurrent()
            is ResumeFreshnessDecision.Adopt -> {
                val outcome = resumeAt(plan.bookId, decision.position)
                if (outcome != ResumeOutcome.Resumed && resumeFreshness.requestStillCurrent(plan)) {
                    reopenFreshnessFromServer(plan.bookId)
                }
            }
        }
    }

    /** The old direct Play behaviour, now used only after the shared freshness decision says to stay local. */
    private suspend fun resumeLoadedCurrent() = withContext(mainDispatcher) {
        val current = player ?: return@withContext
        if (current.mediaItemCount == 0) return@withContext
        if (current.playbackState == Player.STATE_IDLE || current.playerError != null) current.prepare()
        current.play()
    }

    /**
     * The fallback when the service-owned atomic seek could not prove it landed.
     *
     * Reopening obtains a fresh `/play` session and therefore a fresh server-chosen position. It is slower
     * than the normal path but never substitutes an unconfirmed seek with stale local audio.
     */
    private suspend fun reopenFreshnessFromServer(bookId: LibraryItemId) {
        val queue = openQueue(bookId, startAt = null) ?: return
        withContext(mainDispatcher) {
            val current = player ?: return@withContext
            current.setMediaItem(queue.item, queue.startPositionMs)
            current.prepare()
            current.play()
        }
        logger.info(LogCategory.Playback, "A failed remote-position adoption was reopened from the server")
    }

    /**
     * Keeps the existing History diagnostics while moving the decision out of PlayerViewModel.
     *
     * Realtime is not recorded as a `ServerCheck*` row because no server check happened. An adopted realtime
     * move still gets the ordinary `RemoteProgress` row. REST keeps the three check outcomes users already
     * see in History.
     */
    private suspend fun recordFreshnessHistory(plan: ResumeFreshnessPlan) {
        val decision = plan.decision
        if (plan.baselineGeneration != null && decision.source != FreshnessEvidenceSource.Realtime) {
            history.record(
                bookId = plan.bookId,
                event = when (decision) {
                    is ResumeFreshnessDecision.Adopt -> PlaybackEvent.ServerCheckAhead
                    is ResumeFreshnessDecision.Current -> when (decision.source) {
                        FreshnessEvidenceSource.LocalUnverified -> PlaybackEvent.ServerCheckUnavailable
                        FreshnessEvidenceSource.Rest -> PlaybackEvent.ServerCheckCurrent
                        FreshnessEvidenceSource.Realtime -> error("Realtime was filtered above")
                    }
                },
                from = null,
                to = plan.localPosition,
                owner = plan.profileId,
            )
        }
        if (decision is ResumeFreshnessDecision.Adopt) {
            history.record(
                bookId = plan.bookId,
                event = PlaybackEvent.RemoteProgress,
                from = plan.localPosition,
                to = decision.position,
                owner = plan.profileId,
            )
        }
    }

"""
replace_once(
    "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt",
    marker,
    insertion + marker,
)

p = Path("app/src/main/kotlin/com/example/shelfplayer/feature/player/PlayerViewModel.kt")
text = p.read_text()
for line in [
    "import com.example.shelfplayer.core.model.playback.ExternalSessionCheck\n",
    "import com.example.shelfplayer.core.model.playback.PlaybackEvent\n",
    "import com.example.shelfplayer.domain.repository.PlaybackRepository\n",
    "import kotlinx.coroutines.Job\n",
    "import kotlinx.coroutines.NonCancellable\n",
    "import kotlinx.coroutines.withContext\n",
    "import kotlinx.coroutines.withTimeoutOrNull\n",
    "import kotlin.time.Duration.Companion.seconds\n",
]:
    text = text.replace(line, "")
start = text.index("/**\n * PRODUCT_SPEC PLAY-003 / SYNC-002 — the two playback repositories")
end = text.index("/**\n * PRODUCT_SPEC PLAY-001 — the screens' view of playback.", start)
text = text[:start] + text[end:]
text = text.replace("    private val playbackData: PlaybackData,\n", "    private val history: PlaybackHistoryRepository,\n", 1)
text = text.replace("playbackData.history", "history")
text = text.replace(
    "    /** At most one server-freshness check may own the next in-app Play. */\n    private var resumeJob: Job? = null\n\n",
    "",
    1,
)
text = text.replace("        cancelResumeCheck()\n        viewModelScope.launch {", "        viewModelScope.launch {", 1)
toggle_start = text.index(
    "    /**\n     * PRODUCT_SPEC SYNC-002 — an in-app Play checks the server's position before resuming, sometimes."
)
toggle_end = text.index(
    "    /** PRODUCT_SPEC PLAY-001 — re-prepares a player the service gave up on. */",
    toggle_start,
)
replacement = """    /**
     * PRODUCT_SPEC SYNC-002 / issue #91 — Play is deliberately just a Media3 Play command here.
     *
     * Freshness belongs to `PlaybackService`'s forwarding player now, so this button, the notification,
     * Bluetooth/headsets and Android Auto all enter the same serialized decision. Keeping any server check
     * here would immediately recreate the split #91 exists to remove.
     */
    fun onTogglePlayPause() = controller.togglePlayPause()

"""
text = text[:toggle_start] + replacement + text[toggle_end:]
text = text.replace("    fun onStop() {\n        cancelResumeCheck()\n", "    fun onStop() {\n", 1)
tail_start = text.index("    private fun cancelResumeCheck() {")
tail_end = text.index("        const val STOP_TIMEOUT_MILLIS = 5_000L\n    }", tail_start)
tail_end += len("        const val STOP_TIMEOUT_MILLIS = 5_000L\n    }")
text = text[:tail_start] + "    private companion object {\n        const val STOP_TIMEOUT_MILLIS = 5_000L\n    }" + text[tail_end:]
p.write_text(text)
