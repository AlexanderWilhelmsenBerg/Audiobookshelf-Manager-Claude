package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import kotlin.time.Duration.Companion.nanoseconds

/** Test-only, bounded observations. Recording never queries a player or advances either clock. */
internal class Media3TransferTrace(private val virtualMillis: () -> Long, private val wallNanos: () -> Long) {
    private val virtualStart = virtualMillis()
    private val wallStart = wallNanos()
    private val rows = ArrayDeque<String>()
    private var sequence = 0L
    private var dropped = 0

    @Synchronized
    fun record(event: String) {
        val virtual = virtualMillis() - virtualStart
        val wall = (wallNanos() - wallStart).nanoseconds.inWholeMicroseconds
        if (rows.size == MAX_ROWS) {
            rows.removeFirst()
            dropped++
        }
        rows.addLast("${++sequence} virtualMs=$virtual wallUs=$wall ${event.take(MAX_EVENT_LENGTH)}")
    }

    fun log(event: LogEvent) {
        val fields = event.fields.filterIsInstance<LogField.Public>().filter { it.key in PUBLIC_KEYS }
            .joinToString(" ") { "${it.key}=${it.value}" }
        record("${event.message} $fields")
    }

    @Synchronized
    fun render(): String = "Media3 transfer trace: dropped=$dropped; observational, not a refusal diagnosis\n" +
        rows.joinToString("\n")

    /** Keep the original failure and attach evidence before fixture teardown erases transient state. */
    fun attachTo(failure: Throwable, snapshot: () -> Unit) {
        try {
            snapshot()
        } catch (diagnosticFailure: Exception) {
            record("failure-snapshot-unavailable type=${diagnosticFailure.javaClass.simpleName}")
        }
        failure.addSuppressed(AssertionError(render()).apply { stackTrace = emptyArray() })
    }

    private companion object {
        const val MAX_ROWS = 128
        const val MAX_EVENT_LENGTH = 512
        val PUBLIC_KEYS =
            setOf("stage", "proofEpoch", "confirmation", "destination", "requestGeneration", "requestSelection")
    }
}
