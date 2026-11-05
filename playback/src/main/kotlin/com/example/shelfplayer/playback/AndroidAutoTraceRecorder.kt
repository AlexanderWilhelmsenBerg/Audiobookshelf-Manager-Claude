package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.time.AppClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns one correlation clock and all Android Auto trace formatting formerly embedded in PlaybackService.
 *
 * Reads only safe, already-projected evidence. It does not observe Media3 on a separate dispatcher,
 * choose a route, attribute a virtual media key or authorize Play. The service supplies the current
 * privacy-safe snapshot only when opening a new trace; no mutable playback state is copied here.
 */
internal class AndroidAutoTraceRecorder(
    private val logger: Logger,
    private val clock: AppClock,
    private val openingFields: () -> List<LogField>,
) {
    data class Trace(val id: Long, val startedAt: Duration, val source: String) {
        val correlationId: String get() = "auto-$id"
    }

    private var sequence = 0L
    var activeTrace: Trace? = null
        private set

    fun ensure(source: String): Trace {
        val now = clock.elapsed()
        val previous = activeTrace
        if (previous != null && now - previous.startedAt <= REUSE_WINDOW) return previous

        val trace = Trace(++sequence, now, source)
        activeTrace = trace
        log(
            "Android Auto trace started",
            trace,
            listOf(LogField.Public("source", source)) + openingFields(),
        )
        return trace
    }

    fun forController(controllerPackage: String, carPackages: Set<String>): Trace? =
        activeTrace?.takeIf { controllerPackage in carPackages }

    fun log(
        message: String,
        trace: Trace? = activeTrace,
        fields: List<LogField> = emptyList(),
        level: LogLevel = LogLevel.Info,
    ) {
        val timed = if (trace == null) {
            emptyList()
        } else {
            listOf(
                LogField.Public("traceSource", trace.source),
                LogField.Millis(
                    "afterTrace",
                    (clock.elapsed() - trace.startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                ),
            )
        }
        logger.log(
            LogEvent(
                level = level,
                category = LogCategory.AndroidAuto,
                message = message,
                fields = timed + fields,
                correlationId = trace?.correlationId,
            ),
        )
    }

    fun logItems(
        trace: Trace?,
        callback: String,
        direction: String,
        items: List<MediaItem>,
        extraFields: List<LogField> = emptyList(),
    ) {
        if (trace == null) return
        val playable = items.filter { item ->
            item.mediaMetadata.isPlayable == true || MediaItems.isReadyToPlay(item)
        }
        log(
            "Android Auto media item handoff",
            trace,
            buildList {
                add(LogField.Public("callback", callback))
                add(LogField.Public("direction", direction))
                add(LogField.Count("items", items.size))
                add(LogField.Count("playableItems", playable.size))
                addAll(extraFields)
            },
        )
        items.take(AndroidAutoDiagnostics.MAX_MEDIA_HANDOFF_ITEMS).forEachIndexed { index, item ->
            log(
                "Android Auto media row handed off",
                trace,
                buildList {
                    add(LogField.Public("callback", callback))
                    add(LogField.Public("direction", direction))
                    add(LogField.Public("index", index))
                    addAll(AndroidAutoDiagnostics.itemFields(item))
                    addAll(AndroidAutoDiagnostics.metadataPresenceFields(item))
                } + extraFields,
            )
        }
    }

    private companion object {
        /** Arrival and focus callbacks within this window belong to one ignition trace. */
        val REUSE_WINDOW: Duration = 10.seconds
    }
}
