package com.example.shelfplayer.garmin

import org.json.JSONObject

/** Local document codec: only validated, bounded wire values are admitted into Room. */
internal object GarminDeviceDocuments {
    fun read(payload: String): JSONObject = try {
        JSONObject(payload)
    } catch (_: org.json.JSONException) {
        JSONObject()
    }
    fun encode(download: WatchDownload): String =
        JSONObject().put("b", download.id).put("title", download.title).put("state", download.state)
            .put(
                "done",
                download.done,
            ).put(
                "total",
                download.total,
            ).put("from", download.fromSeconds).put("pos", download.position).put("at", download.listenedAt).toString()
    fun encode(event: WatchListen): String =
        JSONObject().put("id", event.id).put("b", event.bookId).put("pos", event.position).put("dur", event.duration)
            .put("at", event.at).put("k", event.kind).put("finished", event.finished).toString()
    fun ui(
        rows: List<com.example.shelfplayer.core.model.garmin.GarminRecord>,
        books: List<com.example.shelfplayer.core.model.library.Book>,
        completed: Set<com.example.shelfplayer.core.model.LibraryItemId>,
        canDownload: Boolean,
    ): GarminDeviceUi {
        val selected = rows.firstOrNull { it.kind == "device" && read(it.payload).optBoolean("selected") }
            ?: rows.firstOrNull { it.kind == "device" }
        val metadata = selected?.let { read(it.payload) } ?: JSONObject()
        val device = selected?.deviceId
        val current = rows.filter { it.deviceId == device }
        return GarminDeviceUi(
            deviceId = device,
            name = metadata.optString("name", "Garmin"), connected = metadata.optBoolean("connected"),
            paired = metadata.optBoolean(
                "paired",
            ),
            lastConnected = metadata.time("lastConnected"), lastSynced = metadata.time("lastSynced"),
            inventoryAt = metadata.time(
                "inventoryAt",
            ),
            ready = metadata.optBoolean(
                "ready",
            ),
            configured = metadata.optBoolean("configured"),
            pairingCode = metadata.optString("pairingCode").takeIf(String::isNotBlank),
            downloads = downloads(current, books),
            listens = listens(current, books),
            choices = if (canDownload) {
                books.filter {
                    it.id in completed
                }
                    .sortedBy { it.title }.map { GarminBookChoice(it.id.value, it.title) }
            } else {
                emptyList()
            },
            pending = current.count {
                it.kind == "command" &&
                    read(it.payload).optString("state") in
                    setOf("pending", "accepted")
            },
            historyGap = metadata.optBoolean("historyGap"),
        )
    }
    private fun downloads(
        rows: List<com.example.shelfplayer.core.model.garmin.GarminRecord>,
        books: List<com.example.shelfplayer.core.model.library.Book>,
    ) = rows.filter {
        it.kind ==
            "inventory"
    }.map { row ->
        val item = read(row.payload)
        GarminDownloadRow(
            books.firstOrNull {
                it.id.value == item.optString("b")
            }?.title,
            item.optString("state"),
            item.optLong("done"),
            item.optLong("total"),
            item.optLong("from"),
        )
    }
    private fun listens(
        rows: List<com.example.shelfplayer.core.model.garmin.GarminRecord>,
        books: List<com.example.shelfplayer.core.model.library.Book>,
    ) = rows.filter {
        it.kind ==
            "event"
    }.take(HISTORY_DISPLAY_LIMIT).map { row ->
        val item = read(row.payload)
        GarminListenRow(
            item.optString("b"),
            item.optLong("pos"),
            item.optLong("dur"),
            item.optLong("at"),
            item.optString("k"),
            books.firstOrNull {
                it.id.value ==
                    item.optString("b")
            }?.title,
        )
    }
    private fun JSONObject.time(key: String): Long? = optLong(key).takeIf { it > 0 }
    private const val HISTORY_DISPLAY_LIMIT = 100
}

data class GarminDownloadRow(
    val title: String?,
    val state: String,
    val partsDone: Long,
    val partsTotal: Long,
    val fromSeconds: Long = 0,
)
data class GarminListenRow(
    val bookId: String,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val at: Long,
    val kind: String,
    val bookTitle: String? = null,
)
data class GarminBookChoice(val id: String, val title: String)
data class GarminDeviceUi(
    val name: String = "Garmin",
    val deviceId: String? = null,
    val connected: Boolean = false,
    val paired: Boolean = false,
    val lastConnected: Long? = null,
    val lastSynced: Long? = null,
    val inventoryAt: Long? = null,
    val downloads: List<GarminDownloadRow> = emptyList(),
    val listens: List<GarminListenRow> = emptyList(),
    val choices: List<GarminBookChoice> = emptyList(),
    val pending: Int = 0,
    val historyGap: Boolean = false,
    val ready: Boolean = false,
    val pairingCode: String? = null,
    val configured: Boolean = false,
    val username: String = "",
    val profileId: String? = null,
)
