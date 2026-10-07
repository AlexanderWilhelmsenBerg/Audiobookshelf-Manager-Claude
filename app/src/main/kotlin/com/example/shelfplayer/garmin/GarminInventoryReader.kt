package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile

internal data class WatchInventoryReport(
    val rows: List<WatchDownload>,
    val at: Long,
    val synced: Long?,
    val completedRequest: String?,
    val failedRequest: String?,
)
internal class GarminInventoryReader(private val port: GarminProviderPort) {
    suspend fun read(profile: Profile): AppResult<WatchInventoryReport> {
        val rows = mutableListOf<WatchDownload>()
        var more = true
        var report: WatchInventoryReport? = null
        while (more) {
            when (val result = page(profile, rows.size)) {
                is AppResult.Failure -> return result

                is AppResult.Success -> {
                    rows.addAll(result.value.report.rows)
                    report = result.value.report
                    more = result.value.more
                }
            }
            if (rows.size > MAX_BOOKS) return invalid()
        }
        return report?.let { AppResult.Success(it.copy(rows = rows)) } ?: invalid()
    }

    // Required wire fields each have an explicit rejection before any cache can be replaced.
    @Suppress("ReturnCount")
    private suspend fun page(profile: Profile, offset: Int): AppResult<Page> {
        val reply =
            port.exchange(profile, "inventory", mapOf("offset" to offset))
                ?: return AppResult.Failure(AppError.Network("The watch did not reply."))
        if (reply["t"] != "inventory" || GarminProviderCodec.number(
                reply["offset"],
            ) != offset.toLong()
        ) {
            return invalid()
        }
        val raw = reply["rows"] as? List<*> ?: return invalid()
        val more = reply["more"] as? Boolean ?: return invalid()
        if (raw.size > 1 || (raw.isEmpty() && more)) return invalid()
        val rows = raw.map { GarminProviderCodec.inventory(it) ?: return invalid() }
        val at = GarminProviderCodec.seconds(reply["at"]) ?: return invalid()
        val report = WatchInventoryReport(
            rows,
            at,
            GarminProviderCodec.seconds(reply["synced"]),
            GarminProviderCodec.text(reply["syncRequest"], GarminProviderCodec.MAX_CORRELATION),
            GarminProviderCodec.text(reply["failedSync"], GarminProviderCodec.MAX_CORRELATION),
        )
        return AppResult.Success(Page(report, more))
    }
    private fun invalid() = AppResult.Failure(AppError.ApiCompatibility("The watch inventory is incompatible."))
    private data class Page(val report: WatchInventoryReport, val more: Boolean)
    private companion object {
        const val MAX_BOOKS = 200
    }
}
