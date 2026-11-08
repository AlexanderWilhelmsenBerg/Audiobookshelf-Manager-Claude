package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Opt-in evidence adapter. Owns no player, target, focus request or endpoint-registry mutation. */
class SystemAudioProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: LiveOutputEndpointRegistry,
    private val clock: AppClock,
) {
    private val main = Handler(Looper.getMainLooper())
    private val bluetooth = BluetoothProfileProbe(context, registry, main)
    private val evidence = SystemRouteProbeEvidence()
    private var changed: (() -> Unit)? = null
    private var playback: AudioManager.AudioPlaybackCallback? = null
    private var mode: AudioManager.OnModeChangedListener? = null
    private var previous = emptySet<String>()
    private var generation = 0L
    private val refresh = Runnable { changed?.invoke() }

    internal fun start(onChanged: () -> Unit) {
        if (changed != null) return
        changed = onChanged
        val epoch = ++generation
        val request = { if (generation == epoch && changed != null) requestRefresh() }
        bluetooth.start(request)
        observeAudio(request)
    }

    internal fun stop() {
        changed = null
        generation++
        main.removeCallbacks(refresh)
        bluetooth.stop()
        val manager = context.getSystemService(AudioManager::class.java)
        playback?.let { runCatching { manager?.unregisterAudioPlaybackCallback(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mode?.let { runCatching { manager?.removeOnModeChangedListener(it) } }
        }
        playback = null
        mode = null
        previous = emptySet()
        evidence.clear()
    }

    internal fun sample(outputs: List<AudioDeviceInfo>, targetId: String?, alias: (Int) -> String): List<String> {
        if (changed == null) return emptyList()
        val allowed = context.hasBluetoothIdentityAccess()
        bluetooth.start { requestRefresh() }
        val profiles = bluetooth.snapshot()
        val observations = outputs.map { device ->
            if (allowed) {
                registry.observationOf(device)
            } else {
                LiveOutputEndpointRegistry.Observation(
                    device.id,
                    device.type,
                    null,
                    capability = BluetoothIdentityCapability.PermissionMissing,
                )
            }
        }
        val target = registry.diagnosticIdentity(targetId).takeIf { allowed }
        val rows = listOf(audioSnapshot()) + evidence.rows(profiles, observations, target, targetId != null, alias)
        val changes = rows.filter { it !in previous }
        previous = rows.toSet()
        return changes.map { "$it elapsedRealtime=${clock.elapsed().inWholeMilliseconds}ms" }
    }

    private fun requestRefresh() {
        if (changed == null) return
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    private fun observeAudio(request: () -> Unit) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val playbackListener = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = request()
        }
        runCatching { manager.registerAudioPlaybackCallback(playbackListener, main) }
            .onSuccess { playback = playbackListener }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { request() }
            runCatching { manager.addOnModeChangedListener({ main.post(it) }, listener) }.onSuccess { mode = listener }
        }
    }

    private fun audioSnapshot(): String {
        val manager = context.getSystemService(AudioManager::class.java)
        val audioMode = runCatching { manager?.mode }.getOrNull()?.toString() ?: "unavailable"
        val count = runCatching { manager?.activePlaybackConfigurations?.size }.getOrNull()?.toString() ?: "unavailable"
        return "system-audio mode=$audioMode visiblePlayers=$count playbackObserver=${playback != null} " +
            "modeObserver=${mode != null} focusOwner=unavailable playerIdentity=unavailable evidence=public-snapshot"
    }
}
