package com.example.shelfplayer.garmin

internal data class WatchDownload(
    val id: String,
    val title: String,
    val state: String,
    val done: Long,
    val total: Long,
    val position: Long?,
    val listenedAt: Long?,
    val fromSeconds: Long = 0,
)
internal data class WatchListen(
    val id: String,
    val bookId: String,
    val position: Long,
    val duration: Long,
    val at: Long,
    val kind: String,
    val finished: Boolean,
    val profileId: String,
)

/** Separate provider app/protocol. Unknown fields tolerated; missing required fields rejected. */
// Immediate guard returns keep each malformed required field explicit at the trust boundary.
@Suppress("ReturnCount")
internal object GarminProviderCodec {
    const val MAX_CORRELATION = 64
    private const val MAX_TYPE = 24
    private const val MAX_BOOK_ID = 128
    private const val MAX_TITLE = 512
    private const val MAX_PARTS = 1500
    fun decode(raw: Any, profile: String, nonce: String?, request: String): Map<*, *>? {
        val map = raw as? Map<*, *> ?: return null
        if (number(map["v"]) != 1L || text(map["r"], MAX_CORRELATION) != request ||
            text(map["p"], MAX_CORRELATION) != profile
        ) {
            return null
        }
        if (
            text(map["t"], MAX_TYPE) == null
        ) {
            return null
        }
        if (nonce != null && text(map["n"], MAX_CORRELATION) != nonce) return null
        if (text(map["n"], MAX_CORRELATION) == null) return null
        return map
    }
    fun inventory(raw: Any?): WatchDownload? {
        val m = raw as? Map<*, *> ?: return null
        val id = text(m["b"], MAX_BOOK_ID) ?: return null
        val title = text(m["title"], MAX_TITLE) ?: return null
        val state =
            text(m["state"], MAX_TYPE)?.takeIf { it in setOf("queued", "partial", "downloaded", "failed") }
                ?: return null
        val done = number(m["done"]) ?: return null
        val total = number(m["total"]) ?: return null
        if (done < 0 || total < 0) return null
        if (done > MAX_PARTS || total > MAX_PARTS) return null
        return WatchDownload(id, title, state, done, total, number(m["pos"]), seconds(m["at"]), number(m["from"]) ?: 0)
    }
    fun event(raw: Any?): WatchListen? {
        val m = raw as? Map<*, *> ?: return null
        val id = text(m["id"], MAX_CORRELATION) ?: return null
        val profile = text(m["p"], MAX_CORRELATION) ?: return null
        val book = text(m["b"], MAX_BOOK_ID) ?: return null
        val pos = number(m["pos"])?.takeIf { it >= 0 } ?: return null
        val dur = number(m["dur"])?.takeIf { it >= 0 } ?: return null
        val at = seconds(m["at"]) ?: return null
        val kind =
            text(m["k"], MAX_TYPE)?.takeIf { it in setOf("checkpoint", "pause", "stop", "complete", "part_boundary") }
                ?: return null
        val finished = m["finished"] as? Boolean ?: return null
        if (dur > 0 && pos > dur) return null
        return WatchListen(id, book, pos, dur, at, kind, finished, profile)
    }
    fun text(value: Any?, max: Int): String? = (value as? String)?.takeIf { it.isNotBlank() && it.length <= max }
    fun number(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> null
    }
    fun seconds(value: Any?): Long? = number(value)?.takeIf { it in 1..MAX_EPOCH_SECONDS }?.times(MILLIS_PER_SECOND)
    private const val MAX_EPOCH_SECONDS = 9_999_999_999L
    private const val MILLIS_PER_SECOND = 1_000L
}
