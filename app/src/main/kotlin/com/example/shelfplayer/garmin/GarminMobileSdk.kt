package com.example.shelfplayer.garmin

import android.content.Context
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

internal interface GarminMobileSdk {
    val state: StateFlow<GarminSdkState>
    val incomingMessages: SharedFlow<Any>
    val lastSendStatus: StateFlow<String?>

    fun start()
    fun send(payload: Map<String, Any>): Boolean
    fun shutdown()
}

@Singleton
internal class ConnectIqGarminMobileSdk @Inject constructor(
    @ApplicationContext private val context: Context,
) : GarminMobileSdk {
    private val _state = MutableStateFlow<GarminSdkState>(GarminSdkState.Starting)
    override val state: StateFlow<GarminSdkState> = _state.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Any>(extraBufferCapacity = 32)
    override val incomingMessages: SharedFlow<Any> = _incomingMessages.asSharedFlow()

    private val _lastSendStatus = MutableStateFlow<String?>(null)
    override val lastSendStatus: StateFlow<String?> = _lastSendStatus.asStateFlow()

    private var connectIq: ConnectIQ? = null
    private var selectedDevice: IQDevice? = null
    private var selectedApp: IQApp? = null
    private var started = false

    private val listener = object : ConnectIQ.ConnectIQListener {
        override fun onInitializeError(errStatus: ConnectIQ.IQSdkErrorStatus) {
            _state.value = GarminSdkState.Unavailable(errStatus.name)
        }

        override fun onSdkReady() {
            refreshDevices()
        }

        override fun onSdkShutDown() {
            selectedDevice = null
            selectedApp = null
            _state.value = GarminSdkState.Unavailable("SDK_SHUT_DOWN")
        }
    }

    override fun start() {
        if (started) return
        started = true
        _state.value = GarminSdkState.Starting

        try {
            val sdk = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
            connectIq = sdk
            // A process-scoped integration object has no Activity that should own Garmin error UI.
            // false keeps missing/outdated Garmin Connect Mobile as explicit bridge state.
            sdk.initialize(context, false, listener)
        } catch (error: RuntimeException) {
            _state.value = GarminSdkState.Unavailable(error.javaClass.simpleName)
        }
    }

    override fun send(payload: Map<String, Any>): Boolean {
        val sdk = connectIq ?: return false
        val device = selectedDevice ?: return false
        val app = selectedApp ?: return false
        if (_state.value !is GarminSdkState.AppAvailable) return false

        return try {
            sdk.sendMessage(device, app, payload) { _, _, status ->
                _lastSendStatus.value = status.name
            }
            true
        } catch (_: InvalidStateException) {
            _lastSendStatus.value = "INVALID_STATE"
            _state.value = GarminSdkState.AppUnavailable(device.toRef(), "INVALID_STATE")
            false
        } catch (_: ServiceUnavailableException) {
            _lastSendStatus.value = "SERVICE_UNAVAILABLE"
            _state.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
            false
        }
    }

    override fun shutdown() {
        val sdk = connectIq
        connectIq = null
        selectedDevice = null
        selectedApp = null
        started = false
        if (sdk != null) {
            try {
                sdk.unregisterAllForEvents()
                sdk.shutdown(context)
            } catch (_: InvalidStateException) {
                // Already shut down is equivalent to the requested state.
            }
        }
        _state.value = GarminSdkState.Unavailable("SDK_STOPPED")
    }

    private fun refreshDevices() {
        val sdk = connectIq ?: return
        val devices = try {
            sdk.knownDevices.orEmpty()
        } catch (_: InvalidStateException) {
            _state.value = GarminSdkState.Unavailable("INVALID_STATE")
            return
        } catch (_: ServiceUnavailableException) {
            _state.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
            return
        }

        if (devices.isEmpty()) {
            clearApplicationListener(sdk)
            selectedDevice = null
            _state.value = GarminSdkState.NoDevice
            return
        }

        devices.forEach { device ->
            try {
                sdk.unregisterForDeviceEvents(device)
                sdk.registerForDeviceEvents(device) { _, _ -> refreshDevices() }
            } catch (_: InvalidStateException) {
                // A later SDK/device callback will refresh the state.
            }
        }

        var connected: IQDevice? = null
        for (device in devices.sortedBy { it.deviceIdentifier }) {
            val status = try {
                sdk.getDeviceStatus(device)
            } catch (_: InvalidStateException) {
                _state.value = GarminSdkState.Unavailable("INVALID_STATE")
                return
            } catch (_: ServiceUnavailableException) {
                _state.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
                return
            }
            if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                connected = device
                break
            }
        }

        if (connected == null) {
            clearApplicationListener(sdk)
            val first = devices.minByOrNull { it.deviceIdentifier } ?: return
            selectedDevice = first
            _state.value = GarminSdkState.DeviceDisconnected(first.toRef())
            return
        }

        if (selectedDevice?.deviceIdentifier != connected.deviceIdentifier) {
            clearApplicationListener(sdk)
        }
        selectedDevice = connected
        _state.value = GarminSdkState.DeviceConnected(connected.toRef())
        resolveCompanion(sdk, connected)
    }

    private fun resolveCompanion(
        sdk: ConnectIQ,
        device: IQDevice,
    ) {
        try {
            sdk.getApplicationInfo(
                GarminBridgeConfig.COMPANION_APPLICATION_ID,
                device,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(app: IQApp) {
                        if (selectedDevice?.deviceIdentifier != device.deviceIdentifier) return
                        clearApplicationListener(sdk)
                        selectedApp = app
                        val registrationError = registerForAppEvents(sdk, device, app)
                        _state.value = if (registrationError == null) {
                            GarminSdkState.AppAvailable(device.toRef())
                        } else {
                            GarminSdkState.AppUnavailable(device.toRef(), registrationError)
                        }
                    }

                    override fun onApplicationNotInstalled(applicationId: String) {
                        if (selectedDevice?.deviceIdentifier != device.deviceIdentifier) return
                        clearApplicationListener(sdk)
                        _state.value = GarminSdkState.AppNotInstalled(device.toRef())
                    }
                },
            )
        } catch (_: InvalidStateException) {
            _state.value = GarminSdkState.AppUnavailable(device.toRef(), "INVALID_STATE")
        } catch (_: ServiceUnavailableException) {
            _state.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
        }
    }

    private fun registerForAppEvents(
        sdk: ConnectIQ,
        device: IQDevice,
        app: IQApp,
    ): String? = try {
        sdk.registerForAppEvents(device, app) { _, _, messages, _ ->
            messages.forEach { message ->
                if (message != null) {
                    _incomingMessages.tryEmit(message)
                }
            }
        }
        null
    } catch (_: InvalidStateException) {
        "INVALID_STATE"
    } catch (_: ServiceUnavailableException) {
        "SERVICE_UNAVAILABLE"
    }

    private fun clearApplicationListener(sdk: ConnectIQ) {
        val device = selectedDevice
        val app = selectedApp
        selectedApp = null
        if (device == null || app == null) return

        try {
            sdk.unregisterForApplicationEvents(device, app)
        } catch (_: InvalidStateException) {
            // The listener is already unusable if the SDK is no longer valid.
        }
    }

    private fun IQDevice.toRef() = GarminDeviceRef(
        identifier = deviceIdentifier,
        name = friendlyName,
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GarminMobileSdkModule {
    @Binds
    @Singleton
    abstract fun bindGarminMobileSdk(implementation: ConnectIqGarminMobileSdk): GarminMobileSdk
}
