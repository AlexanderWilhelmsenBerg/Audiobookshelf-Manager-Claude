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

    val providerState: StateFlow<GarminSdkState> get() = UnavailableProvider.state
    val providerMessages: SharedFlow<Any> get() = UnavailableProvider.messages
    fun sendProvider(payload: Map<String, Any>): Boolean = false

    fun start()
    fun send(payload: Map<String, Any>): Boolean
    fun shutdown()
}

@Singleton
internal class ConnectIqGarminMobileSdk @Inject constructor(@param:ApplicationContext private val context: Context) :
    GarminMobileSdk {
    private val _state = MutableStateFlow<GarminSdkState>(GarminSdkState.Starting)
    override val state: StateFlow<GarminSdkState> = _state.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Any>(extraBufferCapacity = 32)
    override val incomingMessages: SharedFlow<Any> = _incomingMessages.asSharedFlow()

    private val _lastSendStatus = MutableStateFlow<String?>(null)
    override val lastSendStatus: StateFlow<String?> = _lastSendStatus.asStateFlow()

    private val providerMutableState = MutableStateFlow<GarminSdkState>(GarminSdkState.Starting)
    override val providerState = providerMutableState.asStateFlow()
    private val providerMutableMessages = MutableSharedFlow<Any>(extraBufferCapacity = 32)
    override val providerMessages = providerMutableMessages.asSharedFlow()
    private var providerApp: IQApp? = null

    private var connectIq: ConnectIQ? = null
    private var selectedDevice: IQDevice? = null
    private var selectedApp: IQApp? = null
    private var started = false
    private var lifecycle = 0L
    private var resolution = 0L

    private fun listener(token: Long) = object : ConnectIQ.ConnectIQListener {
        override fun onInitializeError(errStatus: ConnectIQ.IQSdkErrorStatus) {
            if (started && lifecycle == token) {
                _state.value = GarminSdkState.Unavailable(errStatus.name)
                providerMutableState.value = _state.value
            }
        }

        override fun onSdkReady() {
            if (started && lifecycle == token) refreshDevices()
        }

        override fun onSdkShutDown() {
            if (!started || lifecycle != token) return
            resolution++
            selectedDevice = null
            selectedApp = null
            providerApp = null
            _state.value = GarminSdkState.Unavailable("SDK_SHUT_DOWN")
            providerMutableState.value = _state.value
        }
    }

    override fun start() {
        if (started) return
        started = true
        lifecycle++
        _state.value = GarminSdkState.Starting
        providerMutableState.value = _state.value

        try {
            val sdk = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
            connectIq = sdk
            // A process-scoped integration object has no Activity that should own Garmin error UI.
            // false keeps missing/outdated Garmin Connect Mobile as explicit bridge state.
            sdk.initialize(context, false, listener(lifecycle))
        } catch (_: RuntimeException) {
            _state.value = GarminSdkState.Unavailable("SDK_INITIALIZE_FAILED")
            providerMutableState.value = _state.value
        }
    }

    override fun send(payload: Map<String, Any>): Boolean = sendTo(payload, false)

    override fun sendProvider(payload: Map<String, Any>): Boolean = sendTo(payload, true)

    private fun sendTo(payload: Map<String, Any>, provider: Boolean): Boolean {
        val sdk = connectIq ?: return false
        val device = selectedDevice
        val app = if (provider) providerApp else selectedApp
        val targetState = if (provider) providerMutableState else _state
        if (device == null || app == null || targetState.value !is GarminSdkState.AppAvailable) return false

        return try {
            val token = lifecycle
            val currentResolution = resolution
            sdk.sendMessage(device, app, payload) { _, _, status ->
                if (started && lifecycle == token &&
                    resolution == currentResolution
                ) {
                    _lastSendStatus.value = status.name
                }
            }
            true
        } catch (_: InvalidStateException) {
            _lastSendStatus.value = "INVALID_STATE"
            targetState.value = GarminSdkState.AppUnavailable(device.toRef(), "INVALID_STATE")
            false
        } catch (_: RuntimeException) {
            _lastSendStatus.value = "SEND_FAILED"
            false
        } catch (_: ServiceUnavailableException) {
            _lastSendStatus.value = "SERVICE_UNAVAILABLE"
            targetState.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
            false
        }
    }

    override fun shutdown() {
        val sdk = connectIq
        lifecycle++
        resolution++
        _lastSendStatus.value = null
        connectIq = null
        selectedDevice = null
        selectedApp = null
        providerApp = null
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
        providerMutableState.value = _state.value
    }

    private fun refreshDevices() {
        if (!started) return
        resolution++
        val sdk = connectIq ?: return
        try {
            val devices = sdk.knownDevices.orEmpty()
            registerDeviceListeners(sdk, devices)
            selectDevice(sdk, devices)
        } catch (_: InvalidStateException) {
            _state.value = GarminSdkState.Unavailable("INVALID_STATE")
            providerMutableState.value = _state.value
        } catch (_: ServiceUnavailableException) {
            _state.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
            providerMutableState.value = _state.value
        }
    }

    private fun registerDeviceListeners(sdk: ConnectIQ, devices: List<IQDevice>) {
        devices.forEach { device ->
            sdk.unregisterForDeviceEvents(device)
            val token = lifecycle
            sdk.registerForDeviceEvents(device) { _, _ ->
                if (started && lifecycle == token && connectIq === sdk) refreshDevices()
            }
        }
    }

    private fun selectDevice(sdk: ConnectIQ, devices: List<IQDevice>) {
        if (devices.isEmpty()) {
            clearApplicationListener(sdk)
            selectedDevice = null
            _state.value = GarminSdkState.NoDevice
            providerMutableState.value = _state.value
            return
        }
        val ordered = devices.sortedBy { it.deviceIdentifier }
        val connected = ordered.firstOrNull { sdk.getDeviceStatus(it) == IQDevice.IQDeviceStatus.CONNECTED }
        if (connected == null) {
            clearApplicationListener(sdk)
            selectedDevice = ordered.first()
            _state.value = GarminSdkState.DeviceDisconnected(ordered.first().toRef())
            providerMutableState.value = _state.value
            return
        }
        if (selectedDevice?.deviceIdentifier != connected.deviceIdentifier) clearApplicationListener(sdk)
        selectedDevice = connected
        _state.value = GarminSdkState.DeviceConnected(connected.toRef())
        providerMutableState.value = GarminSdkState.DeviceConnected(connected.toRef())
        resolveCompanion(sdk, connected, false)
        resolveCompanion(sdk, connected, true)
    }

    private fun isCurrent(sdk: ConnectIQ, token: Long): Boolean = started && connectIq === sdk && resolution == token

    private fun resolveCompanion(sdk: ConnectIQ, device: IQDevice, provider: Boolean) {
        val targetState = if (provider) providerMutableState else _state
        val token = resolution
        try {
            sdk.getApplicationInfo(
                if (provider) AUDIO_PROVIDER_APPLICATION_ID else GarminBridgeConfig.COMPANION_APPLICATION_ID,
                device,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(app: IQApp) {
                        if (!isCurrent(sdk, token) ||
                            selectedDevice?.deviceIdentifier != device.deviceIdentifier
                        ) {
                            return
                        }
                        clearOneApplication(sdk, provider)
                        if (provider) providerApp = app else selectedApp = app
                        val registrationError = registerForAppEvents(sdk, device, app, provider)
                        targetState.value = if (registrationError == null) {
                            GarminSdkState.AppAvailable(device.toRef())
                        } else {
                            GarminSdkState.AppUnavailable(device.toRef(), registrationError)
                        }
                    }

                    override fun onApplicationNotInstalled(applicationId: String) {
                        if (!isCurrent(sdk, token) ||
                            selectedDevice?.deviceIdentifier != device.deviceIdentifier
                        ) {
                            return
                        }
                        clearOneApplication(sdk, provider)
                        targetState.value = GarminSdkState.AppNotInstalled(device.toRef())
                    }
                },
            )
        } catch (_: InvalidStateException) {
            targetState.value = GarminSdkState.AppUnavailable(device.toRef(), "INVALID_STATE")
        } catch (_: ServiceUnavailableException) {
            targetState.value = GarminSdkState.Unavailable("SERVICE_UNAVAILABLE")
        }
    }

    private fun registerForAppEvents(sdk: ConnectIQ, device: IQDevice, app: IQApp, provider: Boolean): String? = try {
        val token = resolution
        sdk.registerForAppEvents(device, app) { incomingDevice, incomingApp, messages, status ->
            if (!isCurrent(sdk, token) ||
                selectedDevice?.deviceIdentifier != incomingDevice.deviceIdentifier ||
                status != ConnectIQ.IQMessageStatus.SUCCESS
            ) {
                return@registerForAppEvents
            }
            if (!incomingApp.applicationId.equals(app.applicationId, ignoreCase = true)) return@registerForAppEvents
            messages.forEach { message ->
                if (message != null) {
                    if (provider) providerMutableMessages.tryEmit(message) else _incomingMessages.tryEmit(message)
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
        clearOneApplication(sdk, false)
        clearOneApplication(sdk, true)
    }

    private fun clearOneApplication(sdk: ConnectIQ, provider: Boolean) {
        val device = selectedDevice
        val app = if (provider) providerApp else selectedApp
        if (provider) providerApp = null else selectedApp = null
        if (device == null || app == null) return

        try {
            sdk.unregisterForApplicationEvents(device, app)
            restoreOtherApplication(sdk, device, provider)
        } catch (_: InvalidStateException) {
            // The listener is already unusable if the SDK is no longer valid.
        }
    }

    private fun restoreOtherApplication(sdk: ConnectIQ, device: IQDevice, provider: Boolean) {
        // SDK 2.4.0 per-app unregister clears ALL local listeners for the device.
        // Restore the survivor using the current lifecycle/resolution guards.
        val other = (if (provider) selectedApp else providerApp) ?: return
        val error = registerForAppEvents(sdk, device, other, !provider) ?: return
        val target = if (provider) _state else providerMutableState
        target.value = GarminSdkState.AppUnavailable(device.toRef(), error)
    }

    private fun IQDevice.toRef() = GarminDeviceRef(
        identifier = deviceIdentifier,
        name = friendlyName,
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal interface GarminMobileSdkModule {
    @Binds
    @Singleton
    fun bindGarminMobileSdk(implementation: ConnectIqGarminMobileSdk): GarminMobileSdk
}

internal const val AUDIO_PROVIDER_APPLICATION_ID = "0a5435b5995c4c10826cc11606e31350"

private object UnavailableProvider {
    val state = MutableStateFlow<GarminSdkState>(GarminSdkState.Unavailable("NOT_SUPPORTED"))
    val messages = MutableSharedFlow<Any>()
}
