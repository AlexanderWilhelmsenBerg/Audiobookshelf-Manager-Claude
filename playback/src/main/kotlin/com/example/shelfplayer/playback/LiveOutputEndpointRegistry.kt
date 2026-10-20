package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live route identity is not a persisted known-device policy identity.
 * An Android device ID is only a process-local handle. BookWave gives each
 * appearance a new opaque route token and never persists a handle or address.
 */
@Singleton
class LiveOutputEndpointRegistry @Inject constructor() {
    data class Observation(val platformId: Int, val type: Int, val productName: String?)

    data class Endpoint(
        val platformId: Int,
        val type: Int,
        val productName: String?,
        val id: String,
        val knownDeviceId: String,
        val output: AudioOutput,
    )

    private var next = 0L
    private var connected: Map<Int, Endpoint> = emptyMap()

    @Synchronized
    fun reconcile(observations: List<Observation>): List<Endpoint> {
        val unique = observations.groupBy(Observation::platformId).filterValues { it.size == 1 }
        val updated = LinkedHashMap<Int, Endpoint>()
        for ((handle, items) in unique) {
            val observation = items.single()
            val output = OutputDevices.outputOf(observation.type, observation.productName) ?: continue
            val existing = connected[handle]?.takeIf {
                it.type == observation.type && it.knownDeviceId == output.id &&
                    it.productName == observation.productName
            }
            updated[handle] = existing ?: Endpoint(
                platformId = handle,
                type = observation.type,
                productName = observation.productName,
                id = output.kind.name.lowercase() + ":endpoint:e" + ++next,
                knownDeviceId = output.id,
                output = output,
            )
        }
        connected = updated
        return updated.values.toList()
    }

    /** No fallback lookup by product name, type or persisted policy row. */
    @Synchronized
    fun find(platformId: Int, type: Int, productName: String?): Endpoint? =
        connected[platformId]?.takeIf { it.type == type && it.productName == productName }

    @Synchronized
    fun clear() {
        connected = emptyMap()
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
    fun confirms(target: String): Boolean =
        source == RouteEvidenceSource.ActualAudioTrack &&
            confidence == RouteConfidence.Confirmed &&
            endpointId == target
}
