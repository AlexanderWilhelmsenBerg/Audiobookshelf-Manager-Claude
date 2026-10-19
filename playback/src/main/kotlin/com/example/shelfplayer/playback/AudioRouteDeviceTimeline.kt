package com.example.shelfplayer.playback

/**
 * Process-scoped alias for an Android output handle. Android IDs are not persisted or exported;
 * a new process starts with fresh aliases. No Bluetooth address, name or stable hardware ID is used.
 * The logical output ID is kept privately to detect when distinct live handles collapse to one
 * BookWave selection; it never leaves this classifier.
 */
internal class AudioRouteDeviceTimeline {
    data class Handle(val platformId: Int, val logicalId: String, val role: String)
    private data class Entry(val alias: String, val logicalId: String, val role: String)

    private val aliases = mutableMapOf<Pair<Int, String>, String>()
    private var nextAlias = 0
    private var connected = emptyMap<Int, Entry>()

    @Synchronized
    fun observe(devices: List<Handle>, selectedId: String?): List<String> {
        val current = devices.associate { device ->
            device.platformId to Entry(
                alias = aliases.getOrPut(device.platformId to device.logicalId) { "d${++nextAlias}" },
                logicalId = device.logicalId,
                role = device.role,
            )
        }
        val changes = mutableListOf<String>()
        connected.forEach { (id, previous) ->
            if (id !in current) changes += "device-disappeared alias=${previous.alias} role=${previous.role}"
        }
        current.forEach { (id, now) ->
            if (id !in connected) changes += "device-appeared alias=${now.alias} role=${now.role}"
        }
        val collisions = current.values.groupBy { it.logicalId }.values.sumOf { (it.size - 1).coerceAtLeast(0) }
        val matching = current.values.filter { it.logicalId == selectedId }
        val selection = when {
            selectedId == null -> "Automatic"
            matching.size == 1 -> "present"
            matching.isEmpty() -> "absent"
            else -> "ambiguous"
        }
        if (changes.isNotEmpty() || collisions > 0) {
            changes += "device-snapshot count=${current.size} collisions=$collisions selection=$selection"
        }
        connected = current
        return changes
    }

    @Synchronized
    fun aliasFor(platformId: Int): String = connected[platformId]?.alias ?: "unmapped"

    @Synchronized
    fun snapshot(): String = connected.values.joinToString(",") { "${it.alias}:${it.role}" }.ifEmpty { "none" }
}
