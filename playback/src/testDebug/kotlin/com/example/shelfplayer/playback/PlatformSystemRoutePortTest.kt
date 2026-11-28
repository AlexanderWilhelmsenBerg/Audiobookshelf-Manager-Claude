package com.example.shelfplayer.playback

import android.Manifest
import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Looper
import androidx.annotation.RequiresApi
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CALLBACK_TIMEOUT_SECONDS = 5L

@RunWith(RobolectricTestRunner::class)
@android.annotation.SuppressLint("UseSdkSuppress") // Robolectric @Config selects SDK, not an instrumented runner.
@RequiresApi(34)
@Config(
    sdk = [34],
    shadows = [PlatformSystemRoutePortTest.RouterShadow::class, PlatformSystemRoutePortTest.ControllerShadow::class],
)
class PlatformSystemRoutePortTest {
    @Before
    fun prepare() {
        RouterShadow.instance = Shadow.newInstanceOf(MediaRouter2::class.java)
        RouterShadow.controller = Shadow.newInstanceOf(MediaRouter2.RoutingController::class.java)
        RouterShadow.routes = listOf(route("private-headset", "Secret headset"), route("private-car", "Secret car"))
        RouterShadow.selected = listOf(RouterShadow.routes.last())
        RouterShadow.transferable = emptyList()
        RouterShadow.requests.clear()
        RouterShadow.routeCallback = null
        RouterShadow.transferCallback = null
        RouterShadow.controllerCallback = null
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    }

    @Test
    @Config(sdk = [35])
    fun transferableListUsesTheControllerOfferEvenWhenBothDevicesAreVisible() {
        val port = PlatformSystemRoutePort(RuntimeEnvironment.getApplication())
        port.open({}, { _, _ -> })
        assertTrue(port.routes().size == 2)
        assertTrue(port.transferableRouteIds().isEmpty())
        RouterShadow.transferable = listOf(RouterShadow.routes.first())
        assertEquals(setOf("private-headset"), port.transferableRouteIds())
        port.close()
    }

    @Test
    fun existingSystemControllerUpdateReachesPolicyWithoutRequiringNewController() {
        val experiment = experiment()
        experiment.open()
        assertFalse(requireNotNull(RouterShadow.preference).shouldPerformActiveScan())
        assertTrue(RouterShadow.requests.isEmpty())
        experiment.transfer("r1")
        assertEquals("private-headset", RouterShadow.requests.single().id)
        assertEquals(SystemRouteStatus.Requested, experiment.state.value.status)
        RouterShadow.selected = listOf(RouterShadow.routes.first())
        requireNotNull(RouterShadow.controllerCallback).onControllerUpdated(RouterShadow.controller)
        assertEquals(SystemRouteStatus.Selected, experiment.state.value.status)
        assertTrue(experiment.state.value.routes.first().selected)
        experiment.close()
        assertEquals(null, RouterShadow.routeCallback)
        assertEquals(null, RouterShadow.transferCallback)
        assertEquals(null, RouterShadow.controllerCallback)
    }

    @Test
    fun missingSystemCallbackTimesOutWithoutRetrying() {
        val experiment = experiment()
        experiment.open()
        experiment.transfer("r1")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(CALLBACK_TIMEOUT_SECONDS))
        assertEquals(SystemRouteStatus.TimedOut, experiment.state.value.status)
        assertEquals(1, RouterShadow.requests.size)
        experiment.close()
    }

    private fun experiment() = SystemRouteExperiment(
        PlatformSystemRoutePort(RuntimeEnvironment.getApplication()),
        TestAppClock(),
    ) { _, _, _ -> }

    private fun route(id: String, name: String): MediaRoute2Info {
        val builder = MediaRoute2Info.Builder(id, name)
            .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
            .setType(MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        // System provenance is framework-owned and not settable by an ordinary app.
        ReflectionHelpers.callInstanceMethod<Any>(
            builder,
            "setSystemRoute",
            ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        return builder.build()
    }

    @Implements(MediaRouter2::class)
    @Suppress("UNUSED_PARAMETER") // Shadow signatures must match the framework's callback API.
    class RouterShadow {
        @Implementation fun getRoutes() = routes

        @Implementation fun getSystemController() = controller

        @Implementation fun transferTo(route: MediaRoute2Info) {
            requests += route
        }

        @Implementation fun registerRouteCallback(
            executor: Executor,
            callback: MediaRouter2.RouteCallback,
            discovery: RouteDiscoveryPreference,
        ) {
            routeCallback = callback
            preference = discovery
        }

        @Implementation fun registerTransferCallback(executor: Executor, callback: MediaRouter2.TransferCallback) {
            transferCallback = callback
        }

        @Implementation fun registerControllerCallback(executor: Executor, callback: MediaRouter2.ControllerCallback) {
            controllerCallback = callback
        }

        @Implementation fun unregisterRouteCallback(callback: MediaRouter2.RouteCallback) {
            if (routeCallback == callback) routeCallback = null
        }

        @Implementation fun unregisterTransferCallback(callback: MediaRouter2.TransferCallback) {
            if (transferCallback == callback) transferCallback = null
        }

        @Implementation fun unregisterControllerCallback(callback: MediaRouter2.ControllerCallback) {
            if (controllerCallback == callback) controllerCallback = null
        }

        companion object {
            lateinit var instance: MediaRouter2
            lateinit var controller: MediaRouter2.RoutingController
            var routes = emptyList<MediaRoute2Info>()
            var selected = emptyList<MediaRoute2Info>()
            var transferable = emptyList<MediaRoute2Info>()
            val requests = mutableListOf<MediaRoute2Info>()
            var preference: RouteDiscoveryPreference? = null
            var routeCallback: MediaRouter2.RouteCallback? = null
            var transferCallback: MediaRouter2.TransferCallback? = null
            var controllerCallback: MediaRouter2.ControllerCallback? = null

            @JvmStatic @Implementation
            fun getInstance(context: Context): MediaRouter2 = instance
        }
    }

    @Implements(MediaRouter2.RoutingController::class)
    class ControllerShadow {
        @Suppress("FunctionOnlyReturningConstant") // Framework method, fixed identity of the system test controller.
        @Implementation
        fun getId() = "system"

        @Implementation fun getSelectedRoutes() = RouterShadow.selected

        @Implementation(minSdk = 35)
        fun getTransferableRoutes() = RouterShadow.transferable
    }
}
