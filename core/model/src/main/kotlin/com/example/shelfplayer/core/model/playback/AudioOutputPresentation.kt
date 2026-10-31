package com.example.shelfplayer.core.model.playback

/** Read-only facts for the player chooser; contains no Android or playback-module types. */
data class AudioOutputPresentation(
    val requested: OutputDestination?,
    val requestedOutputId: String?,
    val measurement: OutputMeasurement,
    val measuredOutputId: String? = null,
    val transfer: OutputTransferStatus? = null,
    val hasRetainedPreviousLock: Boolean = false,
)

enum class OutputDestination { Automatic, Car, Output }

enum class OutputMeasurement { Unavailable, Endpoint, RemoteSubmix, Unresolved, Multiple }

enum class OutputTransferStatus { Waiting, Verifying, Verified, Delegated, Failed }
