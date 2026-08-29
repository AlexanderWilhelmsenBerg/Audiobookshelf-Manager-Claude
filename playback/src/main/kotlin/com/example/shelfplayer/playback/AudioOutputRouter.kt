package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.playback.AudioOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-002 — sending the current book to a chosen output, and saying which one that is.
 *
 * ### What actually moves the audio
 *
 * `ExoPlayer.setPreferredAudioDevice`, which sets the preferred device on the underlying `AudioTrack`. It is
 * the only mechanism a media app has: `AudioManager.setCommunicationDevice` exists but governs *voice*
 * routing and does nothing for media, and the system output switcher cannot be driven programmatically.
 *
 * The word is **preferred** and it is not a promise. The platform honours it while the device is connected
 * and available; it silently ignores it otherwise. So this class never reports a route as achieved — it
 * reports what was asked for, which is the honest limit of what the app can know. There is no API that
 * answers "where is media actually coming out right now".
 *
 * ### Why the selection is not remembered across restarts
 *
 * It is deliberately in memory only, and PLAY-002 is the reason. *"Playback never unexpectedly moves from
 * headphones to the phone speaker"* — a remembered speaker choice would do exactly that, weeks later, to
 * somebody who had forgotten making it. A remembered *headphone* choice is harmless, but a rule that
 * remembers some devices and not others is one a listener cannot predict, and an unpredictable routing rule
 * on a book somebody falls asleep to is worse than retyping a choice.
 *
 * This is a *switch output now* control. The thing that persists is ROUTE-002's per-device policy, which is
 * a different question — what should happen when a device connects — and it already has a settings screen.
 *
 * ### Threading
 *
 * `setPreferredAudioDevice` is a `Player` call and ExoPlayer asserts its application thread, which for this
 * app is main ([ShelfDispatcher.MainImmediate]). Every path that touches the player hops there; the device
 * callback does not arrive on it. `docs/risks.md` R-66 is what happens when that is assumed rather than done.
 */
// `setPreferredAudioDevice` is `@UnstableApi`. Opted in here rather than suppressed at the call: it is
// the one Media3 API that can move audio, ADR-0027 says why there is no alternative, and the whole
// class exists to call it.
@OptIn(UnstableApi::class)
@Singleton
class AudioOutputRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) : AutoLibrary.Outputs {

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())

    /** Every output connected right now, for the player's chooser and the car's browse tree. */
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)

    /** The chosen output's [AudioOutput.id], or `null` for *Automatic* — let the system route. */
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private var player: ExoPlayer? = null
    private var callback: AudioDeviceCallback? = null

    /**
     * The live platform handles, keyed by the stable id.
     *
     * `AudioDeviceInfo.getId` is *not* stable across a disconnect, so it cannot be what a selection stores;
     * the map is rebuilt on every device change and the selection is resolved through it at the moment of
     * use. Storing the platform id instead would silently route to whatever inherited the number.
     */
    private var live: Map<String, AudioDeviceInfo> = emptyMap()

    /**
     * Begins watching, and takes the player this router routes for.
     *
     * Called when the service creates its player, and again after any recreation — PLAY-006 recreates it to
     * apply a buffer preset, and a preference set on a player that no longer exists routes nothing.
     */
    fun attach(target: ExoPlayer) {
        player = target
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        if (callback == null) {
            val registered = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
            }
            manager.registerAudioDeviceCallback(registered, null)
            callback = registered
        }
        refresh()
        applicationScope.launch { apply(_selectedId.value) }
    }

    /** Releases the player. The callback stays registered: the list outlives any one player. */
    fun detach() {
        player = null
    }

    /**
     * Chooses an output, or `null` for *Automatic*.
     *
     * Unknown ids are ignored rather than treated as *Automatic*: a stale row in a car's cached browse tree
     * naming a device that has since disconnected should leave the route alone, not silently move audio.
     */
    override fun select(id: String?) {
        if (id != null && !live.containsKey(id)) {
            logger.info(
                LogCategory.Playback,
                "An output was chosen that is no longer connected",
                LogField.Public("kind", kindOf(id)),
            )
            return
        }
        _selectedId.value = id
        publish()
        applicationScope.launch { apply(id) }
    }

    private suspend fun apply(id: String?) = withContext(mainDispatcher) {
        val target = player ?: return@withContext
        val device = id?.let(live::get)
        target.setPreferredAudioDevice(device)
        logger.info(
            LogCategory.Playback,
            "The audio output was set",
            // The *kind*, never the device's name: a Bluetooth product name is one a person chose and can
            // identify them (PRODUCT_SPEC 14.5). "automatic" is the no-preference case.
            LogField.Public("output", if (id == null) "automatic" else kindOf(id)),
            LogField.Public("connected", (device != null).toString()),
        )
    }

    /**
     * Rebuilds the list, and drops a selection whose device has gone.
     *
     * Falling back to *Automatic* rather than holding the choice is what PLAY-002 requires of this class:
     * the device that left may have been the one audio was going to, and a preference pointing at nothing
     * would leave the player asking for an output that cannot answer. `handleAudioBecomingNoisy` is what
     * pauses in that case; this only stops the app insisting on a route that no longer exists.
     */
    private fun refresh() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val found = LinkedHashMap<String, AudioDeviceInfo>()
        devices.forEach { info ->
            // First wins. Two devices can share an id — the wired category by design, and two identically
            // named headsets as the documented cost of asking for no Bluetooth permission (ROUTE-002).
            val output = OutputDevices.outputOf(info.type, info.productName) ?: return@forEach
            found.putIfAbsent(output.id, info)
        }
        live = found
        val chosen = _selectedId.value
        if (chosen != null && !found.containsKey(chosen)) {
            _selectedId.value = null
            logger.info(
                LogCategory.Playback,
                "The chosen audio output disconnected, so routing is automatic again",
                LogField.Public("kind", kindOf(chosen)),
            )
            applicationScope.launch { apply(null) }
        }
        publish()
    }

    private fun publish() {
        val chosen = _selectedId.value
        _outputs.value = live.mapNotNull { (id, info) ->
            OutputDevices.outputOf(info.type, info.productName, isActive = id == chosen)
        }
    }

    override fun available(): List<AudioOutput> = _outputs.value

    override fun selected(): String? = _selectedId.value

    /**
     * The kind half of an id, for a log line.
     *
     * [OutputDevices] builds ids as `kind:name`, and the name is the half that must never be logged. The
     * wired and car sentinels carry no colon and are already only a kind.
     */
    private fun kindOf(id: String): String = id.substringBefore(':')
}
