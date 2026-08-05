package com.example.shelfplayer.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-001 — the Proto DataStore serializer.
 *
 * A corrupt file raises [CorruptionException] rather than being silently replaced by defaults: the
 * DataStore corruption handler is the one place allowed to decide what a reset means, and hiding the
 * corruption here would make an unexplained loss of the user's settings look like normal behavior.
 */
class AppSettingsSerializer @Inject constructor() : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings.newBuilder()
        .setSchemaVersion(CURRENT_SCHEMA_VERSION)
        .setThemeMode(ThemeMode.THEME_MODE_SYSTEM)
        .setDynamicColor(false)
        // PRODUCT_SPEC SET-002: both diagnostics opt-ins default to off.
        .setDiagnosticsIncludeServerHost(false)
        .setDiagnosticsIncludeMediaTitles(false)
        .setFixtureLibrarySeeded(false)
        .build()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        AppSettings.parseFrom(input)
    } catch (invalid: InvalidProtocolBufferException) {
        throw CorruptionException("Stored settings could not be read", invalid)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) = t.writeTo(output)

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val FILE_NAME: String = "app_settings.pb"
    }
}
