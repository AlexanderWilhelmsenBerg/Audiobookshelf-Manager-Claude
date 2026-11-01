package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android media-output access, without selection, playback policy or route-proof authority.
 * Inventory outlives a player attachment; its callback belongs to the application scope.
 * LiveOutputEndpointRegistry remains the sole owner of endpoint appearance identity.
 */
@OptIn(UnstableApi::class)
internal class AndroidLocalOutputAdapter(
    private val context: Context,
    private val registry: LiveOutputEndpointRegistry,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var live: Map<String, AudioDeviceInfo> = emptyMap()
    private val _identityCapability = MutableStateFlow(BluetoothIdentityCapability.Empty)
    val identityCapability = _identityCapability.asStateFlow()

    @Volatile private var callback: AudioDeviceCallback? = null
    val endpointIds: Set<String> get() = live.keys
    fun nativeHandle(id: String): Int? = live[id]?.id

    /** Privacy-safe capability count; no identity material leaves this adapter. */
    val identifiedEndpointCount: Int get() = live.values.count { registry.find(it)?.identity != null }
    var collidingOutputIds: Set<String> = emptySet()
        private set

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    fun start(changed: () -> Unit) {
        if (callback != null || scope.coroutineContext[Job]?.isActive == false) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val registered = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = dispatch()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = dispatch()

            private fun dispatch() {
                val source = this
                scope.launch(dispatcher) {
                    if (callback === source) changed()
                }
            }
        }
        callback = registered
        manager.registerAudioDeviceCallback(registered, null)
    }

    fun close() {
        val retired = callback ?: return
        callback = null
        context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(retired)
    }

    fun refresh() {
        registry.updateIdentityAccess(context.hasBluetoothIdentityAccess())
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val observations = devices.map(registry::observationOf)
        val entries = registry.reconcile(observations)
        _identityCapability.value = identityCapability(observations)
        val byHandle = devices.associateBy(AudioDeviceInfo::getId)
        live = entries.mapNotNull { endpoint -> byHandle[endpoint.platformId]?.let { endpoint.id to it } }.toMap()
        collidingOutputIds = entries.groupBy { it.knownDeviceId }.values.filter { it.size > 1 }
            .flatMap { group -> group.map { it.id } }.toSet()
    }

    private fun identityCapability(
        observations: List<LiveOutputEndpointRegistry.Observation>,
    ): BluetoothIdentityCapability {
        if (!registry.identityAccess) return BluetoothIdentityCapability.PermissionMissing
        val bluetooth = observations.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val duplicate = bluetooth.mapNotNull { it.identity }.groupingBy { it }.eachCount().any { it.value > 1 }
        return when {
            duplicate -> BluetoothIdentityCapability.Duplicate

            bluetooth.any {
                it.capability == BluetoothIdentityCapability.Verified
            } -> BluetoothIdentityCapability.Verified

            else -> bluetooth.firstOrNull()?.capability ?: BluetoothIdentityCapability.Empty
        }
    }

    fun retainsIdentity(id: String): Boolean = context.hasBluetoothIdentityAccess() && registry.retainsIdentity(id)

    fun outputs(): List<AudioOutput> = live.mapNotNull { (id, info) ->
        registry.find(info)?.output?.copy(id = id)
    }

    /** A preference request is never actual-track evidence. Missing explicit targets fail closed. */
    fun requestPreference(player: ExoPlayer, outputId: String?): Boolean {
        val device = outputId?.let(live::get)
        if (outputId != null && device == null) return false
        player.setPreferredAudioDevice(device)
        return true
    }

    /** Framework policy prediction only; actual playing-track measurement belongs to the monitor. */
    fun predictedIds(): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return manager.getAudioDevicesForAttributes(attributes).mapNotNull { info ->
            registry.find(info)?.id
        }.toSet()
    }
}
