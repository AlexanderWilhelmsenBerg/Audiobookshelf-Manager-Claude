package com.example.shelfplayer.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Public SDK only. Passive discovery preference, no scan request and no Bluetooth profile writes. */
@RequiresApi(34)
internal class PlatformSystemRoutePort(private val context: Context) : SystemRoutePort {
    private val handler = Handler(Looper.getMainLooper())
    private var router: MediaRouter2? = null
    private var routeCallback: MediaRouter2.RouteCallback? = null
    private var transferCallback: MediaRouter2.TransferCallback? = null
    private var controllerCallback: MediaRouter2.ControllerCallback? = null
    private var timeout: Runnable? = null
    private var result: ((String, SystemRouteStatus) -> Unit)? = null

    override fun availability(): SystemRouteStatus =
        if (listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            SystemRouteStatus.Observing
        } else {
            SystemRouteStatus.PermissionRequired
        }

    override fun audioMode(): Int = context.getSystemService(AudioManager::class.java)?.mode ?: -1

    override fun transferableRouteIds(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptySet()
        return router?.systemController?.transferableRoutes.orEmpty().map { it.id }.toSet()
    }

    override fun routes(): List<SystemRouteCandidate> {
        val source = router ?: return emptyList()
        val selected = source.systemController.selectedRoutes.map { it.id }.toSet()
        return source.routes.map {
            SystemRouteCandidate(
                it.id,
                it.name.toString(),
                it.type in BLUETOOTH_TYPES,
                it.isSystemRoute,
                it.id in selected,
            )
        }
    }

    override fun open(changed: () -> Unit, result: (String, SystemRouteStatus) -> Unit) {
        this.result = result
        val source = MediaRouter2.getInstance(context)
        router = source
        val routeObserver = object : MediaRouter2.RouteCallback() {
            override fun onRoutesUpdated(routes: List<MediaRoute2Info>) = changed()
        }
        val transferObserver = object : MediaRouter2.TransferCallback() {
            override fun onTransfer(
                oldController: MediaRouter2.RoutingController,
                newController: MediaRouter2.RoutingController,
            ) {
                // A platform selection callback is not acoustic/AudioTrack proof.
                if (newController.id == source.systemController.id) {
                    newController.selectedRoutes.forEach { result(it.id, SystemRouteStatus.Selected) }
                }
                changed()
            }

            override fun onTransferFailure(requestedRoute: MediaRoute2Info) {
                result(requestedRoute.id, SystemRouteStatus.Failed)
            }
        }
        val controllerObserver = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
                // Transfers within the system provider can update the existing controller.
                if (controller.id == source.systemController.id) {
                    controller.selectedRoutes.forEach { result(it.id, SystemRouteStatus.Selected) }
                    changed()
                }
            }
        }
        routeCallback = routeObserver
        transferCallback = transferObserver
        controllerCallback = controllerObserver
        source.registerRouteCallback(
            ContextCompat.getMainExecutor(context),
            routeObserver,
            RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build(),
        )
        source.registerTransferCallback(ContextCompat.getMainExecutor(context), transferObserver)
        source.registerControllerCallback(ContextCompat.getMainExecutor(context), controllerObserver)
    }

    override fun transfer(id: String) {
        check(availability() == SystemRouteStatus.Observing)
        val source = checkNotNull(router)
        val target = source.routes.firstOrNull { it.id == id && it.isSystemRoute && it.type in BLUETOOTH_TYPES }
        requireNotNull(target)
        val deadline = Runnable { result?.invoke(id, SystemRouteStatus.TimedOut) }
        timeout?.let(handler::removeCallbacks)
        timeout = deadline
        handler.postDelayed(deadline, TRANSFER_TIMEOUT_MS)
        source.transferTo(target)
    }

    override fun close() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        router?.let { source ->
            routeCallback?.let { runCatching { source.unregisterRouteCallback(it) } }
            transferCallback?.let { runCatching { source.unregisterTransferCallback(it) } }
            controllerCallback?.let { runCatching { source.unregisterControllerCallback(it) } }
        }
        result = null
        router = null
        routeCallback = null
        transferCallback = null
        controllerCallback = null
    }

    private companion object {
        const val TRANSFER_TIMEOUT_MS = 5_000L
        val BLUETOOTH_TYPES = setOf(
            MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
            MediaRoute2Info.TYPE_BLE_HEADSET,
            MediaRoute2Info.TYPE_HEARING_AID,
        )
    }
}
