package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo

/** Diagnostic correlation only. No name matching, routing decisions or exported identity material. */
internal class SystemRouteProbeEvidence {
    private val aliases = linkedMapOf<String, String>()
    private var next = 0L
    val size: Int get() = aliases.size

    fun alias(identity: String): String {
        val alias = aliases.getOrPut(identity) { "b${++next}" }
        while (aliases.size > MAX_PEERS) aliases.remove(aliases.keys.first())
        return alias
    }

    fun clear() = aliases.clear()

    fun rows(
        profiles: List<BluetoothProfileProbe.Snapshot>,
        outputs: List<LiveOutputEndpointRegistry.Observation>,
        target: String?,
        hasTarget: Boolean,
        outputAlias: (Int) -> String,
    ): List<String> {
        val counts = outputs.mapNotNull { it.identity }.groupingBy { it }.eachCount()
        return profiles.flatMap { profileRows(it, counts, target, hasTarget) } +
            outputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }.map { output ->
                val key = output.identity
                val match = targetMatch(key, target, counts[key] ?: 0, hasTarget)
                val peer = key?.let(::alias) ?: "unavailable"
                "endpoint-system-evidence output=${outputAlias(output.platformId)} " +
                    "identityCapability=${output.capability} peer=$peer targetMatch=$match evidence=identity-only"
            }
    }

    private fun profileRows(
        snapshot: BluetoothProfileProbe.Snapshot,
        counts: Map<String, Int>,
        target: String?,
        hasTarget: Boolean,
    ): List<String> = listOf(
        "bluetooth-profile profile=${snapshot.profile} status=${snapshot.status} count=${snapshot.peers.size} " +
            "peers=${snapshot.peers.map {
                it.identity?.let(::alias) ?: "unavailable"
            }.sorted().joinToString("+").ifEmpty { "none" }}",
    ) + snapshot.peers.map { peer ->
        val key = peer.identity
        val match = targetMatch(key, target, counts[key] ?: 0, hasTarget)
        val label = key?.let(::alias) ?: "unavailable"
        "bluetooth-peer peer=$label profile=${snapshot.profile} " +
            "streaming=${peer.streaming?.toString() ?: "unknown"} " +
            "endpointMatches=${counts[key] ?: 0} targetMatch=$match evidence=profile-only"
    }

    companion object {
        private const val MAX_PEERS = 64

        fun targetMatch(identity: String?, target: String?, occurrences: Int, hasTarget: Boolean): String = when {
            !hasTarget -> "no-target"
            identity == null || target == null -> "unavailable"
            occurrences > 1 -> "ambiguous"
            identity == target -> "matches"
            else -> "different"
        }
    }
}
