package com.example.shelfplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Issue #138 — a debug fault-injection seam for the one cold Media3 playback-resumption callback.
 *
 * This is deliberately not a resume policy. It may replace only the start position handed from
 * `MediaSession.Callback.onPlaybackResumption(isForPlayback = true)` to Media3; the genuine `/play`
 * session remains untouched so [ResumeFreshnessCoordinator] can compare that server evidence with the
 * trusted progress source before audio starts.
 */
interface ColdResumeDiagnostic {
    val state: StateFlow<ColdResumeDiagnosticState>

    /** Arms exactly one future cold playback resumption. Release builds bind a disabled implementation. */
    fun arm()

    /**
     * Consumes the diagnostic only at the cold playback-resumption call site.
     *
     * Foreground Play, an already loaded media session and metadata-only resumption never call this method.
     */
    fun consumeForColdPlaybackResumption(): Boolean
}

enum class ColdResumeDiagnosticState {
    NotArmed,
    Armed,
    Consumed,
}

/**
 * The release/benchmark implementation. Keeping the disabled behavior in main makes its safety contract
 * directly unit-testable even though normal verification intentionally builds the debug variant.
 */
class DisabledColdResumeDiagnostic @Inject constructor() : ColdResumeDiagnostic {
    override val state: StateFlow<ColdResumeDiagnosticState> =
        MutableStateFlow(ColdResumeDiagnosticState.NotArmed)

    override fun arm() = Unit

    override fun consumeForColdPlaybackResumption(): Boolean = false
}

/**
 * The only transformation the diagnostic is allowed to perform.
 *
 * The selector is called only by the `isForPlayback = true` Media3 resumption path. It never changes the
 * opened Audiobookshelf session or the staged resume baseline; it changes only Media3's initial local
 * position, which is exactly the failure #138 needs to reproduce deterministically.
 */
class ColdResumeStartPosition @Inject constructor(private val diagnostic: ColdResumeDiagnostic) {
    fun forPlaybackResumption(realStartPositionMs: Long): Long = if (diagnostic.consumeForColdPlaybackResumption()) {
        0L
    } else {
        realStartPositionMs.coerceAtLeast(0L)
    }
}

/**
 * Whether the service may turn the player's current position into durable progress.
 *
 * Zero is deliberately not durable: it can mean an item has only just been installed, including the debug
 * reproduction of #138. A single-file fallback is also not a book-global position (R-61). Keeping this as the
 * predicate used by `PlaybackService.positionSnapshot` makes the zero-write safety property directly testable.
 */
internal fun isPersistablePlaybackPosition(positionMs: Long, singleFileFallback: Boolean): Boolean =
    positionMs > 0L && !singleFileFallback
