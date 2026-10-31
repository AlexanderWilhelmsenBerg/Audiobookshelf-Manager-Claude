package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live route identity is not a persisted known-device policy identity.
 * An Android device ID is only a process-local handle. BookWave gives each
 * appearance a new opaque route token unless a unique, exact process-private Bluetooth identity
 * reconnects it. Identity retains destination intent only; current inventory and AudioTrack proof
 * are still required. Handles, addresses and tokens are never persisted.
 */
@Singleton
class LiveOutputEndpointRegistry @Inject constructor() {
    data class Observation(val platformId: Int, val type: Int, val productName: String?, val identity: String? = null)

    data class Endpoint(
        val platformId: Int,
        val type: Int,
        val productName: String?,
        val id: String,
        val knownDeviceId: String,
        val output: AudioOutput,
        val identity: String? = null,
    )

    private val processToken = UUID.randomUUID().toString()
    private var next = 0L
    private var connected: Map<Int, Endpoint> = emptyMap()
    private val bluetooth = BluetoothEndpointIdentity()
    private val retained = linkedMapOf<String, String>()

    internal fun bluetoothIdentity(type: Int, address: String?): String? = bluetooth.key(type, address)

    @Synchronized
    fun reconcile(observations: List<Observation>): List<Endpoint> {
        val unique = observations.groupBy(Observation::platformId).filterValues { it.size == 1 }
        val ambiguous = observations.mapNotNull { it.identity }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        ambiguous.forEach(retained::remove)
        val updated = LinkedHashMap<Int, Endpoint>()
        for ((handle, items) in unique) {
            val observation = items.single()
            val output = OutputDevices.outputOf(observation.type, observation.productName) ?: continue
            val identity = observation.identity?.takeUnless { it in ambiguous }
            val existing = connected[handle]?.takeIf {
                it.type == observation.type && it.knownDeviceId == output.id &&
                    it.productName == observation.productName && it.identity == identity &&
                    observation.identity !in ambiguous
            }
            updated[handle] = existing ?: Endpoint(
                platformId = handle,
                type = observation.type,
                productName = observation.productName,
                id = identity?.let(retained::get)
                    ?: (output.kind.name.lowercase() + ":endpoint:" + processToken + ":e" + ++next),
                knownDeviceId = output.id,
                output = output,
                identity = identity,
            )
            if (identity != null) retained[identity] = updated.getValue(handle).id
        }
        // Bounded process memory; eviction loses correlation safely, never grants another device authority.
        while (retained.size > MAX_RETAINED_IDENTITIES) retained.remove(retained.keys.first())
        connected = updated
        return updated.values.toList()
    }

    /** No fallback lookup by product name, type or persisted policy row. */
    @Synchronized
    fun find(platformId: Int, type: Int, productName: String?, identity: String? = null): Endpoint? =
        connected[platformId]?.takeIf {
            it.type == type && it.productName == productName && it.identity == identity
        }

    @Synchronized
    fun clear() {
        connected = emptyMap()
        retained.clear()
    }

    private companion object {
        const val MAX_RETAINED_IDENTITIES = 32
    }
}

internal enum class RouteConfidence { Confirmed, Inferred, Ambiguous, Unknown }

internal enum class RouteEvidenceSource { ActualAudioTrack, AndroidMediaPolicy, ListenerPreference, MediaButtonUnknown }

/** Only a measured playing AudioTrack can positively confirm the physical output. */
internal data class RouteEvidence(
    val source: RouteEvidenceSource,
    val confidence: RouteConfidence,
    val endpointId: String? = null,
) {
    fun confirms(target: String): Boolean = source == RouteEvidenceSource.ActualAudioTrack &&
        confidence == RouteConfidence.Confirmed &&
        endpointId == target
}
