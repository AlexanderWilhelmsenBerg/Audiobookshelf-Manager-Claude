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
    data class AppAvailable(val device: GarminDeviceRef) : GarminSdkState
}

sealed interface GarminBridgeState {
    data class SdkUnavailable(val reason: String) : GarminBridgeState
    data object NoDevice : GarminBridgeState
    data class DeviceDisconnected(val deviceName: String) : GarminBridgeState
    data class DeviceConnected(val deviceName: String) : GarminBridgeState
    data class AppNotInstalled(val deviceName: String) : GarminBridgeState
    data class AppAvailable(val deviceName: String) : GarminBridgeState
    data class ProtocolIncompatible(val deviceName: String) : GarminBridgeState
    data class Ready(val deviceName: String) : GarminBridgeState
}
