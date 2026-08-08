package com.example.shelfplayer.data.settings

import android.os.Build
import com.example.shelfplayer.core.common.AppBuild
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.network.gateway.PlaybackDevice
import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 / 14.5 — what this install tells the server about itself.
 *
 * ### The identifier
 *
 * A random UUID, generated on first use and kept in the settings store. Not `ANDROID_ID`, not an
 * advertising id, not a serial: the server's use for it is to group one device's listening sessions, and
 * a random value does that exactly as well while handing a self-hosted deployment nothing it could
 * correlate with anything else. The alternative — regenerating per process — would show the user a new
 * device in their own session list every time the app restarted.
 *
 * ### The rest
 *
 * `Build.MANUFACTURER` and `Build.MODEL` are the strings the user would recognise as their phone, which
 * is the whole reason the server records them. They are already in the `User-Agent` of every request an
 * Android app makes by default, so they reveal nothing new.
 *
 * Living in `:data:settings` rather than in `:app` is what keeps the store out of the wiring module:
 * PRODUCT_SPEC 9.3 has `:core:datastore` stopping at a data module, and `AppSettingsDataSource` is named
 * here and nowhere else.
 */
@Singleton
class DefaultPlaybackDeviceIdentity @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val appBuild: AppBuild,
) : PlaybackDeviceIdentity {

    override suspend fun describe(): PlaybackDevice = PlaybackDevice(
        clientName = appBuild.clientName,
        clientVersion = appBuild.version,
        deviceId = settings.playbackDeviceId { UUID.randomUUID().toString() },
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        sdkVersion = Build.VERSION.SDK_INT,
    )
}
