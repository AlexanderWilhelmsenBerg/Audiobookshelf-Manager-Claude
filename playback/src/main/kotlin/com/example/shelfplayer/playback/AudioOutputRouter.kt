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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-002 — sending the current book to a chosen output, and saying which one that is.
 *
 * `ExoPlayer.setPreferredAudioDevice` is a preference, not a promise. Android may decline it. API 33+
 * supplies the framework-reported media route through `getAudioDevicesForAttributes`; below that the
 * explicit request is the best available fallback.
 *
 * The complete output flow deliberately still contains the phone speaker so routing safety can observe it.
 * The [AutoLibrary.Outputs] view excludes speakers: BookWave never offers the built-in speaker as a manual
 * destination even though Android's own system output switcher may still do so.
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioOutputRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) : AutoLibrary.Outputs {

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())

    /** Every output connected right now, including speakers needed for route observation. */
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)

    /** The chosen output id, or `null` for Automatic — let Android route. */
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private var player: ExoPlayer? = null
    private var callback: AudioDeviceCallback? = null

    /** Live platform handles keyed by BookWave's stable, permission-free identity. */
    private var live: Map<String, AudioDeviceInfo> = emptyMap()

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

    /** Releases the player. The device callback stays registered because the connected list outlives it. */
    fun detach() {
        player = null
    }

    /**
     * Chooses an output, or `null` for Automatic.
     *
     * A stale id is ignored rather than converted to Automatic. The speaker is additionally rejected here
     * even if an old cached car browse row sends its id: removing the row from today's UI must not leave a
     * stale route into the destination the product explicitly forbids.
     */
    override fun select(id: String?) {
        if (id != null) {
            val info = live[id]
            val output = info?.let { OutputDevices.outputOf(it.type, it.productName) }
            if (info == null || output?.isSpeaker == true) {
                logger.info(
                    LogCategory.Playback,
                    if (output?.isSpeaker == true) {
                        "A phone-speaker output choice was refused"
                    } else {
                        "An output was chosen that is no longer connected"
                    },
                    LogField.Public("kind", kindOf(id)),
                )
                return
            }
        }
        _selectedId.value = id
        publish()
        applicationScope.launch { apply(id) }
    }

    private suspend fun apply(id: String?) = withContext(mainDispatcher) {
        val target = player ?: return@withContext
        val device = id?.let(live::get)
        target.setPreferredAudioDevice(device)
        publish()
        val routed = routedIds()
        logger.info(
            LogCategory.Playback,
            "The audio output was set",
            LogField.Public("asked", if (id == null) "automatic" else kindOf(id)),
            LogField.Public("connected", (device != null).toString()),
            LogField.Public("routedTo", routed?.joinToString("+") { kindOf(it) } ?: "unknown"),
            LogField.Public(
                "honoured",
                when {
                    routed == null -> "unknown"
                    id == null -> "automatic"
                    else -> routed.contains(id).toString()
                },
            ),
        )
        settle()
    }

    /** Re-read once because route changes have no corresponding framework callback. */
    private suspend fun settle() {
        delay(ROUTE_SETTLE_DELAY)
        publish()
    }

    private fun refresh() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val found = LinkedHashMap<String, AudioDeviceInfo>()
        devices.forEach { info ->
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
        val routed = routedIds()
        val chosen = _selectedId.value
        _outputs.value = live.mapNotNull { (id, info) ->
            OutputDevices.outputOf(
                type = info.type,
                productName = info.productName,
                isActive = routed?.contains(id) ?: (id == chosen),
            )
        }
    }

    /** Framework-reported devices for BookWave's media attributes, or `null` before API 33. */
    private fun routedIds(): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return manager.getAudioDevicesForAttributes(attributes)
            .mapNotNull { info -> OutputDevices.outputOf(info.type, info.productName)?.id }
            .toSet()
    }

    /**
     * The selectable list exposed to the car browse tree. Speakers remain in [outputs] above but are absent
     * here by construction.
     */
    override fun available(): List<AudioOutput> = _outputs.value.filterNot(AudioOutput::isSpeaker)

    override fun selected(): String? = _selectedId.value

    /** Kind half only; product names never reach logs. */
    private fun kindOf(id: String): String = id.substringBefore(':')

    private companion object {
        val ROUTE_SETTLE_DELAY = 400.milliseconds
    }
}
