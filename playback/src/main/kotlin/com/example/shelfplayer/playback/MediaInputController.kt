package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/** Caller category describes the requesting software, never the physical button or speaker. */
internal data class MediaInputController(
    val caller: Caller = Caller.Unattributed,
    val version: Int? = null,
    val interfaceVersion: Int? = null,
    val trusted: Boolean? = null,
    val packageVerified: Boolean? = null,
) {
    enum class Caller { Unattributed, AndroidAuto, BluetoothService, SameApp, System, Legacy, External }

    fun fields(): String = "caller=$caller controllerVersion=${version ?: "unknown"} " +
        "interfaceVersion=${interfaceVersion ?: "unknown"} controllerTrusted=${trusted ?: "unknown"} " +
        "packageVerified=${packageVerified ?: "unknown"}"

    companion object {
        @OptIn(UnstableApi::class)
        fun capture(
            controller: MediaSession.ControllerInfo?,
            ownUid: Int,
            carPackages: Set<String>,
        ): MediaInputController {
            if (controller == null) return MediaInputController()
            val caller = when {
                controller.packageName in carPackages -> Caller.AndroidAuto
                controller.packageName == "com.android.bluetooth" -> Caller.BluetoothService
                controller.uid == ownUid -> Caller.SameApp
                controller.packageName in setOf("android", "com.android.systemui") -> Caller.System
                controller.packageName == "android.media.session.MediaController" -> Caller.Legacy
                else -> Caller.External
            }
            return MediaInputController(
                caller,
                controller.controllerVersion,
                controller.interfaceVersion,
                controller.isTrusted,
                controller.isPackageNameVerified,
            )
        }
    }
}
