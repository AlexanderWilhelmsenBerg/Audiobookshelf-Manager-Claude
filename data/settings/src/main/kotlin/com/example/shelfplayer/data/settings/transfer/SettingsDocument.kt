package com.example.shelfplayer.data.settings.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PRODUCT_SPEC SET-001 — the on-disk shape of an exported settings file.
 *
 * ### Why a hand-written mirror of the proto, and not the proto's own bytes
 *
 * Base64 of `AppSettings.toByteArray()` would be five lines and could never drift. It would also be
 * unreadable, and this file's whole privacy claim — *no credential is in it* — is one the user should be
 * able to check by opening it. A self-hosted audience is exactly the audience that will.
 *
 * The cost of the choice is drift: a new proto field that nobody adds here is a setting that silently stops
 * being exported, and nothing would fail. `SettingsTransferDriftTest` is what makes that fail instead —
 * it enumerates the generated message's own accessors and refuses to pass until every field is either
 * mirrored below or named in [SettingsTransfer.EXCLUDED_FIELDS] with a reason.
 *
 * ### Every property is optional with a default
 *
 * A file written by an older build is missing fields; a file written by a newer one has fields this build
 * has never heard of. Both must import. Defaults cover the first and [SettingsTransfer.JSON]'s
 * `ignoreUnknownKeys` covers the second — a settings file is not a network contract, and refusing to import
 * somebody's own file because it mentions a setting from a later build would be the wrong trade every time.
 */
@Serializable
internal data class SettingsDocument(
    /**
     * The **document** format, not the settings schema. Bumped only when the meaning of something below
     * changes, which is the one case an older build cannot be trusted to read.
     */
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    /** ISO-8601, or absent. Informational: shown after an import so the user knows which file they picked. */
    @SerialName("exported_at") val exportedAt: String? = null,
    /**
     * What this file is, in words, for whoever opens it in a text editor.
     *
     * A comment field rather than a JSON comment, because JSON has none. It is written on export and
     * ignored on import.
     */
    @SerialName("about") val about: String = ABOUT,
    /** Server addresses this device knew. Never a credential — see `SettingsExport`. */
    @SerialName("servers") val servers: List<ServerDocument> = emptyList(),
    @SerialName("settings") val settings: SettingsBody = SettingsBody(),
) {
    companion object {
        const val FORMAT_VERSION = 1

        const val ABOUT: String =
            "BookWave settings export. Server addresses and app preferences only — no password, " +
                "no access token, and no app passcode. Importing it does not sign you in."
    }
}

/**
 * A server this device had reached, by address.
 *
 * [detectedVersion] is recorded for the reader's benefit and is deliberately *not* trusted on import: the
 * sign-in screen re-probes every address before it shows a password field, for the reason `KnownServers`
 * gives — a remembered version is a claim the app stopped checking.
 */
@Serializable
internal data class ServerDocument(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("detected_version") val detectedVersion: String? = null,
)

/**
 * One account's view preferences, keyed by something that survives a reinstall.
 *
 * The store keys these by this app's locally generated profile id, which is regenerated on a fresh install
 * and would therefore match nothing. The address and the username are what identify the same account on the
 * same server, so that is the key in the file.
 */
@Serializable
internal data class ProfileDocument(
    @SerialName("server_url") val serverUrl: String,
    @SerialName("username") val username: String,
    @SerialName("default_library_id") val defaultLibraryId: String = "",
    @SerialName("library_sort_order") val librarySortOrder: Map<String, String> = emptyMap(),
    @SerialName("shelf_sort_order") val shelfSortOrder: String = "",
)

/** PRODUCT_SPEC ROUTE-002 — one remembered output device and what connecting it should do. */
@Serializable
internal data class DeviceDocument(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("kind") val kind: String = "",
    @SerialName("policy") val policy: String = "",
    @SerialName("last_seen_epoch_millis") val lastSeenEpochMillis: Long = 0,
)

/**
 * The settings themselves, mirroring `AppSettings` field for field — minus what
 * [SettingsTransfer.EXCLUDED_FIELDS] excludes and says why.
 *
 * Names match the proto's, so the file reads the same way the schema does and a reviewer can put the two
 * side by side. Enums travel as their proto constant names rather than as numbers: a renumbered constant
 * would read back as a different setting, silently, which is the same reasoning `library_sort_order` and
 * `buffer_preset` already use inside the store.
 */
@Suppress("LongParameterList")
@Serializable
internal data class SettingsBody(
    @SerialName("theme_mode") val themeMode: String = "",
    @SerialName("dynamic_color") val dynamicColor: Boolean = false,
    @SerialName("app_language_tag") val appLanguageTag: String = "",
    @SerialName("diagnostics_include_server_host") val diagnosticsIncludeServerHost: Boolean = false,
    @SerialName("diagnostics_include_media_titles") val diagnosticsIncludeMediaTitles: Boolean = false,
    @SerialName("sleep_timer_default_minutes") val sleepTimerDefaultMinutes: Int = 0,
    @SerialName("sleep_timer_fade_seconds") val sleepTimerFadeSeconds: Int = 0,
    @SerialName("sleep_timer_shake_to_restart") val sleepTimerShakeToRestart: Boolean = false,
    @SerialName("sleep_timer_rewind_seconds") val sleepTimerRewindSeconds: Int = 0,
    @SerialName("default_speed_hundredths") val defaultSpeedHundredths: Int = 0,
    @SerialName("skip_back_seconds") val skipBackSeconds: Int = 0,
    @SerialName("skip_forward_seconds") val skipForwardSeconds: Int = 0,
    @SerialName("auto_rewind_enabled") val autoRewindEnabled: Boolean = false,
    @SerialName("auto_rewind_configured") val autoRewindConfigured: Boolean = false,
    @SerialName("auto_rewind_short_seconds") val autoRewindShortSeconds: Int = 0,
    @SerialName("auto_rewind_medium_seconds") val autoRewindMediumSeconds: Int = 0,
    @SerialName("auto_rewind_long_seconds") val autoRewindLongSeconds: Int = 0,
    @SerialName("auto_rewind_very_long_seconds") val autoRewindVeryLongSeconds: Int = 0,
    @SerialName("buffer_preset") val bufferPreset: String = "",
    @SerialName("focus_behaviour") val focusBehaviour: String = "",
    @SerialName("startup_mode") val startupMode: String = "",
    @SerialName("auto_play_on_car_connect") val autoPlayOnCarConnect: Boolean = false,
    @SerialName("auto_advance_series_disabled") val autoAdvanceSeriesDisabled: Boolean = false,
    @SerialName("streaming_cellular_disabled") val streamingCellularDisabled: Boolean = false,
    @SerialName("downloads_cellular_enabled") val downloadsCellularEnabled: Boolean = false,
    @SerialName("smart_downloads_cellular_enabled") val smartDownloadsCellularEnabled: Boolean = false,
    @SerialName("smart_download_enabled") val smartDownloadEnabled: Boolean = false,
    @SerialName("delete_finished_after_days") val deleteFinishedAfterDays: Int = 0,
    @SerialName("delete_previous_on_smart_download") val deletePreviousOnSmartDownload: Boolean = false,
    @SerialName("download_storage_volume_uuid") val downloadStorageVolumeUuid: String = "",
    @SerialName("known_devices") val knownDevices: List<DeviceDocument> = emptyList(),
    @SerialName("profile_settings") val profileSettings: List<ProfileDocument> = emptyList(),
)
