package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.domain.repository.DeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC ROUTE-002 — noticing that an output device connected, and doing what it is allowed to do.
 *
 * ### Why `AudioDeviceCallback` and not a Bluetooth broadcast
 *
 * It reports wired, Bluetooth, hearing aids and USB through one callback, in terms of *audio outputs* —
 * which is the thing a policy is actually about — and it needs no permission. The Bluetooth broadcasts
 * would need `BLUETOOTH_CONNECT` on API 31+ to read a device's name, and would say nothing about a 3.5mm
 * jack. ROUTE-002 asks for the minimum Nearby Devices permission; this needs none at all.
 *
 * ### What it will not do
 *
 * Start audio unless the device's own policy is [DevicePolicy.AutoPlay], which is never a default and can
 * only be set per device. And never when something is already loaded: a connection arriving mid-book is a
 * route change, not a request to start something.
 */
@Singleton
class OutputDeviceWatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val devices: DeviceRepository,
    private val clock: AppClock,
    private val logger: Logger,
) {

    /**
     * What the service can do about a connection.
     *
     * An interface rather than the player itself, because every decision in this class is testable and none
     * of the Media3 calls are. The service supplies the three verbs; this decides which one, and whether.
     */
    interface Actions {

        /** Whether the player already holds a book. A connection mid-book must change nothing. */
        fun isBusy(): Boolean

        /** ROUTE-002's `Arm only`: load the last book **paused**, ready for a headset Play. */
        suspend fun arm()

        /** ROUTE-002's `Auto-play`. The only path in this app that starts audio unasked. */
        suspend fun armAndPlay()
    }

    private val connections = DeviceConnections()
    private var callback: AudioDeviceCallback? = null

    fun start(scope: CoroutineScope, actions: Actions) {
        if (callback != null) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val registered = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                addedDevices.orEmpty().forEach { info -> scope.launch { onConnected(info, actions) } }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                removedDevices.orEmpty()
                    .mapNotNull { info -> OutputDevices.of(info.type, info.productName, clock.now()) }
                    .forEach { device -> connections.onDisconnected(device.id) }
            }
        }
        // Null handler: delivered on the main looper, which is where every `Player` read has to happen
        // anyway. The work itself is launched onto `scope`, so the callback thread does no I/O.
        manager.registerAudioDeviceCallback(registered, null)
        callback = registered
    }

    fun stop() {
        val registered = callback ?: return
        context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(registered)
        callback = null
    }

    private suspend fun onConnected(info: AudioDeviceInfo, actions: Actions) {
        // Outputs only. An input — a headset's microphone announces itself separately — is not somebody
        // settling down with a book, and acting on it would double every wired connection.
        if (!info.isSink) return
        val device = OutputDevices.of(info.type, info.productName, clock.now()) ?: return
        if (!connections.shouldAct(device.id, clock.now())) return

        devices.remember(device)
        // Read *after* the debounce and the remember, so a first-ever connection has been stored and gets
        // the default rather than falling through a gap between the two.
        val policy = devices.policyFor(device.id)
        // A connection arriving mid-book is a route change, not a request to start something.
        if (actions.isBusy()) return

        when (policy) {
            DevicePolicy.AutoPlay -> {
                logger.log(device, "A device connected and its policy is to start playing")
                actions.armAndPlay()
            }
            // `Ask` arms as well, and the paused media session is what puts a resume control in the shade —
            // which is the notification action ROUTE-002 asks for, using the session the app already has
            // rather than a second notification competing with it.
            DevicePolicy.ArmOnly, DevicePolicy.Ask -> {
                logger.log(device, "A device connected and the last book was made ready")
                actions.arm()
            }

            DevicePolicy.Never -> Unit
        }
    }

    /**
     * The device's *kind* and the policy, never its name.
     *
     * PRODUCT_SPEC 14.5 — "Ada's AirPods" names a person as surely as a username does. The kind is what a
     * diagnostic reader needs and identifies nobody.
     */
    private fun Logger.log(device: KnownDevice, message: String) = info(
        LogCategory.Playback,
        message,
        LogField.Public("kind", device.kind.name),
    )
}
