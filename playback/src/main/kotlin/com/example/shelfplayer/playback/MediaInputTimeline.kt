package com.example.shelfplayer.playback

/** Input appearances and descriptor equality are diagnostic hints, never an input-to-output mapping. */
internal class MediaInputTimeline {
    private data class Entry(val alias: String, val descriptor: String, val facts: String)
    private val descriptors = MediaInputAliases()
    private var connected = emptyMap<Int, Entry>()
    private var nextAlias = 0
    val current: List<String> get() = connected.values.map(Entry::facts)

    fun observe(devices: List<MediaInputDevice>): List<String> {
        val unique = devices.groupBy(MediaInputDevice::id).filterValues { it.size == 1 }
            .values.map(List<MediaInputDevice>::single).sortedBy(MediaInputDevice::id).take(MAX_INPUTS)
        val descriptorCounts = unique.map { descriptors.descriptor(it.descriptor) }.groupingBy { it }.eachCount()
        val updated = unique.associate { device ->
            val descriptor = descriptors.descriptor(device.descriptor)
            val previous = connected[device.id]?.takeIf { it.descriptor == descriptor }
            val alias = previous?.alias ?: "i${++nextAlias}"
            device.id to Entry(alias, descriptor, facts(device, alias, descriptor, descriptorCounts[descriptor] ?: 0))
        }
        val changes = changes(updated).toMutableList()
        connected = updated
        changes += "input-snapshot observed=${devices.size} recorded=${unique.size} " +
            "duplicateIds=${devices.size - devices.map(MediaInputDevice::id).distinct().size} " +
            "truncated=${unique.size < devices.size}"
        return changes
    }

    fun eventDevice(device: MediaInputDevice): String {
        val descriptor = descriptors.descriptor(device.descriptor)
        val matching = connected[device.id]?.takeIf { it.descriptor == descriptor }
        val count = connected.values.count { it.descriptor == descriptor }
        return facts(device, matching?.alias ?: "unmapped", descriptor, count)
    }

    private fun changes(updated: Map<Int, Entry>): List<String> = buildList {
        connected.forEach { (id, old) ->
            if (updated[id]?.alias != old.alias) add("input-disappeared ${old.facts}")
        }
        updated.forEach { (id, entry) ->
            when {
                connected[id]?.alias != entry.alias -> add("input-appeared ${entry.facts}")
                connected[id]?.facts != entry.facts -> add("input-changed ${entry.facts}")
            }
        }
    }

    private fun facts(device: MediaInputDevice, alias: String, descriptor: String, count: Int): String =
        "inputAlias=$alias inputId=${device.id} deviceSources=${device.sources} " +
            "keyboardType=${device.keyboardType} virtual=${device.virtual} external=${device.external ?: "unknown"} " +
            "descriptorAlias=$descriptor descriptorMatches=${if (descriptor.startsWith("s")) count else 0} " +
            "association=Unproven"

    private companion object {
        const val MAX_INPUTS = 64
    }
}
