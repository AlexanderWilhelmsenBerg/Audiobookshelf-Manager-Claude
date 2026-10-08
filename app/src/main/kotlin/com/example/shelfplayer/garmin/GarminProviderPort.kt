package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/** One correlated request at a time; replies cannot cross watch, profile, nonce or A-B-A generation. */
internal class GarminProviderPort @Inject constructor(
    private val sdk: GarminMobileSdk,
    private val access: GarminDeviceAccess,
) {
    private val mutex = Mutex()
    private var pending: Pending? = null
    var nonce: String? = null
        private set
    fun reset() {
        nonce = null
        pending?.reply?.complete(null)
        pending = null
    }
    suspend fun receive(raw: Any) {
        val request = pending ?: return
        val device = (sdk.providerState.value as? GarminSdkState.AppAvailable)?.device ?: return
        if (device.identifier != request.device || !access.allowed(request.profile, request.generation)) return
        val value = GarminProviderCodec.decode(raw, request.profile.id.value, request.nonce, request.id) ?: return
        request.reply.complete(value)
    }
    suspend fun exchange(
        profile: Profile,
        type: String,
        fields: Map<String, Any> = emptyMap(),
        id: String = UUID.randomUUID().toString(),
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): Map<*, *>? = mutex.withLock {
        val generation = access.profiles.activeProfileGeneration()
        val device = (sdk.providerState.value as? GarminSdkState.AppAvailable)?.device ?: return@withLock null
        if (!access.allowed(profile, generation) || (type != "hello" && nonce == null)) return@withLock null
        val request = Pending(profile, generation, device.identifier, id, if (type == "hello") null else nonce)
        pending = request
        val message = mutableMapOf<String, Any>("v" to 1, "t" to type, "r" to id, "p" to profile.id.value)
        request.nonce?.let { message["n"] = it }
        message.putAll(fields)
        try {
            if (!sdk.sendProvider(message)) return@withLock null
            val reply = withTimeoutOrNull(timeoutMs) { request.reply.await() }
            if (!access.allowed(profile, generation) ||
                (sdk.providerState.value as? GarminSdkState.AppAvailable)?.device?.identifier != device.identifier
            ) {
                return@withLock null
            }
            if (type == "hello" &&
                reply != null
            ) {
                nonce = GarminProviderCodec.text(reply["n"], GarminProviderCodec.MAX_CORRELATION)
            }
            reply
        } finally {
            if (pending === request) pending = null
        }
    }
    private data class Pending(
        val profile: Profile,
        val generation: Long,
        val device: Long,
        val id: String,
        val nonce: String?,
        val reply: CompletableDeferred<Map<*, *>?> = CompletableDeferred(),
    )
    private companion object {
        const val REPLY_TIMEOUT_MS = 20_000L
    }
}
