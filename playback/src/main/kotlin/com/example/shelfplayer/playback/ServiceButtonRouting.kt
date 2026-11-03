package com.example.shelfplayer.playback

import android.os.Handler
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.playback.PlaybackSettings

/** Bridges Media3's key callback to the existing Play boundary. Nothing here sends Play or changes focus. */
@OptIn(UnstableApi::class)
internal class ServiceButtonRouting(
    private val outputs: AudioOutputRouter,
    private val clock: AppClock,
    private val currentScope: () -> ButtonPlayScope,
    private val carPresent: () -> Boolean,
    private val carPackages: Set<String>,
) {
    private val requests = ButtonPlayRequests()
    private var enabled = true
    private var sonyEnabled = false
    var sony: SonyTapMonitor? = null

    fun settings(settings: PlaybackSettings) {
        if (enabled != settings.headsetButtonRouting || sonyEnabled != settings.sonyTapDetection) requests.clear()
        enabled = settings.headsetButtonRouting
        sonyEnabled = settings.sonyTapDetection
        updateMonitor()
    }

    fun updateMonitor() {
        val listen = enabled && sonyEnabled && carPresent()
        sony?.update(listen, if (listen) outputs.localOutputs.buttonCandidates() else emptySet())
    }

    fun clear() = requests.clear()

    fun sonyTap(id: String) {
        if (enabled && sonyEnabled && carPresent()) requests.sonyTap(id, currentScope(), now())
    }

    fun key(session: MediaSession, controller: MediaSession.ControllerInfo, event: KeyEvent?) {
        if (event?.action == KeyEvent.ACTION_UP && !event.isCanceled) return
        val trusted = controller.isPackageNameVerified && carPresent()
        if (event == null || !trusted || !event.isInitialDown()) {
            clear()
            return
        }
        val car = controller.packageName in carPackages
        if (!enabled && !car) {
            clear()
            return
        }
        val ids = outputs.localOutputs.buttonCandidates()
        val claim = requests.down(
            ButtonDown(
                event.keyCode,
                controller.caller(),
                car,
                currentScope(),
                ids,
                now(),
                outputs.localOutputs.soleButtonHeadset(ids),
            ),
        ) ?: return
        // Media3 1.11 can deliver an explicit Play as its own notification controller, inline in this
        // dispatch. Expire relay permission on the next looper turn. Delayed toggles need the original caller.
        Handler(session.player.applicationLooper).post { requests.endDispatch(claim) }
    }

    fun transport(play: Boolean, session: MediaSession?, player: Player): ButtonDestination? {
        val controller = session?.controllerForCurrentRequest
        val directCar = controller != null && controller.packageName in carPackages && controller.isPackageNameVerified
        val freshPlay = play && (!player.playWhenReady || player.playerError != null)
        if (directCar || !freshPlay || !carPresent()) {
            clear()
            return null
        }
        return requests.consume(
            ButtonCommand(
                true,
                controller?.caller(),
                controller?.isPackageNameVerified == true && session.isMediaNotificationController(controller),
            ),
            currentScope(),
            outputs.localOutputs.buttonCandidates(),
            now(),
        )
    }

    /** Only listener intent is submitted. The caller's existing guarded Play owns any player start. */
    fun apply(target: ButtonDestination?): Boolean {
        if (outputs.outputLock.value != null || target == null) return true
        return when (target) {
            ButtonDestination.Car -> outputs.selectCarFromGenericController()

            ButtonDestination.Refused -> false

            is ButtonDestination.Headset -> {
                val id = target.id
                val candidates = outputs.localOutputs.buttonCandidates()
                val valid = if (target.exact) {
                    sony?.canUseTap(id) == true
                } else {
                    outputs.localOutputs.soleButtonHeadset(candidates) == id
                }
                valid && id in candidates &&
                    (
                        outputs.explicitSelection.value?.outputId == id ||
                            outputs.handoffs.select(id, OutputIntentOwner.Destination.Output)
                        )
            }
        }
    }

    fun close() {
        clear()
        sony?.close()
        sony = null
    }

    private fun now() = clock.elapsed().inWholeMilliseconds
    private fun KeyEvent.isInitialDown() = action == KeyEvent.ACTION_DOWN && repeatCount == 0 && !isCanceled
    private fun MediaSession.ControllerInfo.caller() = ButtonController(packageName, uid)
}
