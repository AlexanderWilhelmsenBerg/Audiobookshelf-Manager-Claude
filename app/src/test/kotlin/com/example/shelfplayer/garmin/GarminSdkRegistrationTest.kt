package com.example.shelfplayer.garmin

import android.app.Application
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Exercises the real SDK 2.4.0 listener table, including its device-wide unregister behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GarminSdkRegistrationTest {
    @Test fun replacingEitherAppKeepsBothMessageChannelsReachable() = runTest {
        val f = Fixture()
        val received = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.adapter.incomingMessages.collect { received += "companion" }
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.adapter.providerMessages.collect { received += "provider" }
        }
        try {
            for (isProvider in listOf(true, false)) {
                f.replace(isProvider)
                f.deliver(f.companion)
                f.deliver(f.provider)
                runCurrent()
            }
            assertEquals(listOf("companion", "provider", "companion", "provider"), received)
        } finally {
            f.sdk.unregisterAllForEvents()
        }
    }

    private class Fixture {
        private val context = RuntimeEnvironment.getApplication()
        val sdk: ConnectIQ = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.TETHERED)
        val adapter = ConnectIqGarminMobileSdk(context)
        val device = IQDevice(1, "Fixture watch")
        val companion = IQApp(GarminBridgeConfig.COMPANION_APPLICATION_ID)
        val provider = IQApp(AUDIO_PROVIDER_APPLICATION_ID)
        private val register = adapter.javaClass.getDeclaredMethod(
            "registerForAppEvents",
            ConnectIQ::class.java,
            IQDevice::class.java,
            IQApp::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        private val clear = adapter.javaClass.getDeclaredMethod(
            "clearOneApplication",
            ConnectIQ::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        init {
            ConnectIQ::class.java.getDeclaredField("mInitialized").apply { isAccessible = true }.set(sdk, true)
            val receiverClass = Class.forName("com.garmin.android.connectiq.IQMessageReceiver")
            val receiver = receiverClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            ConnectIQ::class.java.getDeclaredField("mMessageReceiver").apply { isAccessible = true }.set(sdk, receiver)
            field("started", true)
            field("connectIq", sdk)
            field("selectedDevice", device)
            field("selectedApp", companion)
            field("providerApp", provider)
            register.invoke(adapter, sdk, device, companion, false)
            register.invoke(adapter, sdk, device, provider, true)
        }

        fun replace(isProvider: Boolean) {
            clear.invoke(adapter, sdk, isProvider)
            val app = if (isProvider) provider else companion
            field(if (isProvider) "providerApp" else "selectedApp", app)
            register.invoke(adapter, sdk, device, app, isProvider)
        }

        fun deliver(app: IQApp) {
            val receiver = ConnectIQ::class.java.getDeclaredField("mMessageReceiver").apply {
                isAccessible = true
            }.get(sdk)
            val table = receiver.javaClass.getDeclaredMethod(
                "getDeviceListenerContainer",
                Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(receiver, 1L)
            val listener = table.javaClass.getDeclaredMethod("getAppListener", String::class.java).apply {
                isAccessible = true
            }.invoke(table, app.applicationId) as? ConnectIQ.IQApplicationEventListener
            assertNotNull(listener, "The other app's reconnect must not remove this channel")
            listener.onMessageReceived(device, app, listOf(mapOf("fixture" to true)), ConnectIQ.IQMessageStatus.SUCCESS)
        }

        private fun field(name: String, value: Any) =
            adapter.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(adapter, value)
    }
}
