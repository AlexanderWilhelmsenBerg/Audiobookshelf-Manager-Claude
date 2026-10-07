package com.example.shelfplayer.garmin

internal data class GarminDeviceRef(
    val identifier: Long,
    val name: String,
)

internal sealed interface GarminSdkState {
    data object Starting : GarminSdkState
    data class Unavailable(val reason: String) : GarminSdkState
    data object NoDevice : GarminSdkState
    data class DeviceDisconnected(val device: GarminDeviceRef) : GarminSdkState
    data class DeviceConnected(val device: GarminDeviceRef) : GarminSdkState
    data class AppNotInstalled(val device: GarminDeviceRef) : GarminSdkState
    data class AppUnavailable(
        val device: GarminDeviceRef,
        val reason: String,
    ) : GarminSdkState
    data class AppAvailable(val device: GarminDeviceRef) : GarminSdkState
}

sealed interface GarminBridgeState {
    data class SdkUnavailable(val reason: String) : GarminBridgeState
    data object NoDevice : GarminBridgeState
    data class DeviceDisconnected(val deviceName: String) : GarminBridgeState
    data class DeviceConnected(val deviceName: String) : GarminBridgeState
    data class AppNotInstalled(val deviceName: String) : GarminBridgeState
    data class AppUnavailable(
        val deviceName: String,
        val reason: String,
    ) : GarminBridgeState
    data class AppAvailable(val deviceName: String) : GarminBridgeState
    data class ProtocolIncompatible(val deviceName: String) : GarminBridgeState
    data class Ready(val deviceName: String) : GarminBridgeState
}

internal fun GarminSdkState.toBridgeState(): GarminBridgeState = when (this) {
    GarminSdkState.Starting -> GarminBridgeState.SdkUnavailable("INITIALIZING")
    is GarminSdkState.Unavailable -> GarminBridgeState.SdkUnavailable(reason)
    GarminSdkState.NoDevice -> GarminBridgeState.NoDevice
    is GarminSdkState.DeviceDisconnected -> GarminBridgeState.DeviceDisconnected(device.name)
    is GarminSdkState.DeviceConnected -> GarminBridgeState.DeviceConnected(device.name)
    is GarminSdkState.AppNotInstalled -> GarminBridgeState.AppNotInstalled(device.name)
    is GarminSdkState.AppUnavailable -> GarminBridgeState.AppUnavailable(device.name, reason)
    is GarminSdkState.AppAvailable -> GarminBridgeState.AppAvailable(device.name)
}
