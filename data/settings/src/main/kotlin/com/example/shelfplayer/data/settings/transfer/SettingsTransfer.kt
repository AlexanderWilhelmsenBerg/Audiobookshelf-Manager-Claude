package com.example.shelfplayer.data.settings.transfer

import com.example.shelfplayer.core.datastore.AppSettings
import com.example.shelfplayer.core.datastore.DeviceKind
import com.example.shelfplayer.core.datastore.DevicePolicy
import com.example.shelfplayer.core.datastore.FocusBehaviour
import com.example.shelfplayer.core.datastore.KnownDevice
import com.example.shelfplayer.core.datastore.ProfileSettings
import com.example.shelfplayer.core.datastore.StartupMode
import com.example.shelfplayer.core.datastore.ThemeMode
import kotlinx.serialization.json.Json

/**
 * PRODUCT_SPEC SET-001 — the settings store as a document, and back again.
 *
 * Pure, and deliberately so: everything here is `AppSettings` in and `SettingsDocument` out, or the
 * reverse. No `DataStore`, no `ContentResolver`, no clock. The decisions worth testing — that no identifier
 * of this install leaves, that an import keeps the device id it already had, that an unknown enum constant
 * falls back rather than throwing — are all decided in this file and all reachable from a plain JVM test.
 */
internal object SettingsTransfer {

    /**
     * `prettyPrint` because a user opening the file is the point (see [SettingsDocument]), and
     * `ignoreUnknownKeys` because a file from a newer build must still import what this one understands.
     * `encodeDefaults` so the file lists every setting including the ones left at their default — a reader
     * asking "is my sleep timer in here?" should not have to know that absence means default.
     */
    val JSON: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Every `AppSettings` field this document carries, named as the generated accessor spells it.
     *
     * Read by `SettingsTransferDriftTest`, which fails when the proto gains a field that appears in neither
     * this set nor [EXCLUDED_FIELDS]. Without that test a new setting would quietly stop being exported and
     * nothing anywhere would say so.
     */
    val EXPORTED_FIELDS: Set<String> = setOf(
        "ThemeMode",
        "DynamicColor",
        "AppLanguageTag",
        "DiagnosticsIncludeServerHost",
        "DiagnosticsIncludeMediaTitles",
        "SleepTimerDefaultMinutes",
        "SleepTimerFadeSeconds",
        "SleepTimerShakeToRestart",
        "SleepTimerRewindSeconds",
        "DefaultSpeedHundredths",
        "SkipBackSeconds",
        "SkipForwardSeconds",
        "AutoRewindEnabled",
        "AutoRewindConfigured",
        "AutoRewindShortSeconds",
        "AutoRewindMediumSeconds",
        "AutoRewindLongSeconds",
        "AutoRewindVeryLongSeconds",
        "BufferPreset",
        "FocusBehaviour",
        "StartupMode",
        "AutoPlayOnCarConnect",
        "AutoAdvanceSeriesDisabled",
        "StreamingCellularDisabled",
        "DownloadsCellularEnabled",
        "SmartDownloadsCellularEnabled",
        "SmartDownloadEnabled",
        "DeleteFinishedAfterDays",
        "DeletePreviousOnSmartDownload",
        "DownloadStorageVolumeUuid",
        "KnownDevices",
        "ProfileSettings",
    )

    /**
     * The fields that stay behind, each with the reason it stays.
     *
     * These describe **this install**, not the user's preferences. The reasons are values rather than
     * comments so that the drift test can print one when a field is missing, and so that adding a field
     * here is a decision somebody had to write down.
     */
    val EXCLUDED_FIELDS: Map<String, String> = mapOf(
        "PlaybackDeviceId" to
            "the identifier this install sends the server as `deviceInfo.deviceId`; copying it would make " +
            "two installs one device in the server's listening history",
        "ActiveProfileId" to
            "a locally generated id that names nothing on another install, and the importing device has " +
            "its own idea of who is signed in",
        "FixtureLibrarySeeded" to
            "whether *this* install has already written the demo library to its own database",
        "SchemaVersion" to
            "the settings store's own migration marker, owned by the store and not by the user",
    )

    /** The document for [settings], with the servers and per-profile keys the caller resolved. */
    fun document(
        settings: AppSettings,
        servers: List<ServerDocument>,
        exportedAt: String?,
        profileKeys: Map<String, ProfileKey>,
    ): SettingsDocument = SettingsDocument(
        exportedAt = exportedAt,
        servers = servers,
        settings = settings.toBody(profileKeys),
    )

    private fun AppSettings.toBody(profileKeys: Map<String, ProfileKey>): SettingsBody = SettingsBody(
        themeMode = themeMode.exportName(),
        dynamicColor = dynamicColor,
        appLanguageTag = appLanguageTag,
        diagnosticsIncludeServerHost = diagnosticsIncludeServerHost,
        diagnosticsIncludeMediaTitles = diagnosticsIncludeMediaTitles,
        sleepTimerDefaultMinutes = sleepTimerDefaultMinutes,
        sleepTimerFadeSeconds = sleepTimerFadeSeconds,
        sleepTimerShakeToRestart = sleepTimerShakeToRestart,
        sleepTimerRewindSeconds = sleepTimerRewindSeconds,
        defaultSpeedHundredths = defaultSpeedHundredths,
        skipBackSeconds = skipBackSeconds,
        skipForwardSeconds = skipForwardSeconds,
        autoRewindEnabled = autoRewindEnabled,
        autoRewindConfigured = autoRewindConfigured,
        autoRewindShortSeconds = autoRewindShortSeconds,
        autoRewindMediumSeconds = autoRewindMediumSeconds,
        autoRewindLongSeconds = autoRewindLongSeconds,
        autoRewindVeryLongSeconds = autoRewindVeryLongSeconds,
        bufferPreset = bufferPreset,
        focusBehaviour = focusBehaviour.exportName(),
        startupMode = startupMode.exportName(),
        autoPlayOnCarConnect = autoPlayOnCarConnect,
        autoAdvanceSeriesDisabled = autoAdvanceSeriesDisabled,
        streamingCellularDisabled = streamingCellularDisabled,
        downloadsCellularEnabled = downloadsCellularEnabled,
        smartDownloadsCellularEnabled = smartDownloadsCellularEnabled,
        smartDownloadEnabled = smartDownloadEnabled,
        deleteFinishedAfterDays = deleteFinishedAfterDays,
        deletePreviousOnSmartDownload = deletePreviousOnSmartDownload,
        downloadStorageVolumeUuid = downloadStorageVolumeUuid,
        knownDevices = knownDevicesMap.map { (id, device) ->
            DeviceDocument(
                id = id,
                displayName = device.displayName,
                kind = device.kind.exportName(),
                policy = device.policy.exportName(),
                lastSeenEpochMillis = device.lastSeenEpochMillis,
            )
        }.sortedBy(DeviceDocument::id),
        // Only profiles the caller could name. One whose row has gone is one nothing could key on, and a
        // block keyed by a local id would be a block no import could ever match.
        profileSettings = profileSettingsMap.mapNotNull { (profileId, stored) ->
            profileKeys[profileId]?.let { key ->
                ProfileDocument(
                    serverUrl = key.serverUrl,
                    username = key.username,
                    defaultLibraryId = stored.defaultLibraryId,
                    librarySortOrder = stored.librarySortOrderMap.toSortedMap(),
                    shelfSortOrder = stored.shelfSortOrder,
                )
            }
        }.sortedWith(compareBy(ProfileDocument::serverUrl, ProfileDocument::username)),
    )

    /**
     * [current] with everything [document] carries written over it.
     *
     * A merge and not a replacement: the four fields in [EXCLUDED_FIELDS] keep the values this install
     * already had, which is the whole reason they are excluded. Everything else the document names wins,
     * including a value that happens to equal the default — the user exported a state and expects that
     * state, not a three-way merge nobody could predict.
     *
     * The two maps — known devices, per-account preferences — are merged **by key** rather than replaced.
     * A device this phone has seen and the file has not is this phone's own fact and stays; a device the
     * file names takes the file's policy. Replacing them wholesale would make an import silently forget
     * the headphones the user paired yesterday.
     *
     * [profileIds] maps the file's account keys onto this device's profile ids. A key with no match is
     * dropped and counted; see `SettingsImport.profilePreferencesSkipped` for why that is reported rather
     * than hidden.
     */
    fun merged(current: AppSettings, document: SettingsDocument, profileIds: Map<ProfileKey, String>): AppSettings {
        val body = document.settings
        return current.toBuilder()
            .setThemeMode(body.themeMode.asEnum(ThemeMode.THEME_MODE_UNSPECIFIED))
            .setDynamicColor(body.dynamicColor)
            .setAppLanguageTag(body.appLanguageTag)
            .setDiagnosticsIncludeServerHost(body.diagnosticsIncludeServerHost)
            .setDiagnosticsIncludeMediaTitles(body.diagnosticsIncludeMediaTitles)
            .setSleepTimerDefaultMinutes(body.sleepTimerDefaultMinutes)
            .setSleepTimerFadeSeconds(body.sleepTimerFadeSeconds)
            .setSleepTimerShakeToRestart(body.sleepTimerShakeToRestart)
            .setSleepTimerRewindSeconds(body.sleepTimerRewindSeconds)
            .setDefaultSpeedHundredths(body.defaultSpeedHundredths)
            .setSkipBackSeconds(body.skipBackSeconds)
            .setSkipForwardSeconds(body.skipForwardSeconds)
            .setAutoRewindEnabled(body.autoRewindEnabled)
            .setAutoRewindConfigured(body.autoRewindConfigured)
            .setAutoRewindShortSeconds(body.autoRewindShortSeconds)
            .setAutoRewindMediumSeconds(body.autoRewindMediumSeconds)
            .setAutoRewindLongSeconds(body.autoRewindLongSeconds)
            .setAutoRewindVeryLongSeconds(body.autoRewindVeryLongSeconds)
            .setBufferPreset(body.bufferPreset)
            .setFocusBehaviour(body.focusBehaviour.asEnum(FocusBehaviour.FOCUS_BEHAVIOUR_PAUSE))
            .setStartupMode(body.startupMode.asEnum(StartupMode.STARTUP_MODE_ON_MEDIA_COMMAND))
            .setAutoPlayOnCarConnect(body.autoPlayOnCarConnect)
            .setAutoAdvanceSeriesDisabled(body.autoAdvanceSeriesDisabled)
            .setStreamingCellularDisabled(body.streamingCellularDisabled)
            .setDownloadsCellularEnabled(body.downloadsCellularEnabled)
            .setSmartDownloadsCellularEnabled(body.smartDownloadsCellularEnabled)
            .setSmartDownloadEnabled(body.smartDownloadEnabled)
            .setDeleteFinishedAfterDays(body.deleteFinishedAfterDays)
            .setDeletePreviousOnSmartDownload(body.deletePreviousOnSmartDownload)
            .setDownloadStorageVolumeUuid(body.downloadStorageVolumeUuid)
            .putAllKnownDevices(body.knownDevices.associate { it.id to it.toProto() })
            .putAllProfileSettings(body.profileSettings.matched(profileIds))
            .build()
    }

    /**
     * How many accounts in [document] were restored, and how many had nowhere to go.
     *
     * Counted from the same `matched` the merge uses rather than from the list's length, so two blocks
     * naming the same account — which this app never writes but a hand-edited file can hold — cannot make
     * the two numbers disagree with what was actually stored.
     */
    fun profileOutcome(document: SettingsDocument, profileIds: Map<ProfileKey, String>): ProfileOutcome {
        val blocks = document.settings.profileSettings
        val applied = blocks.matched(profileIds).size
        return ProfileOutcome(applied = applied, skipped = blocks.count { profileIds[it.key()] == null })
    }

    private fun List<ProfileDocument>.matched(profileIds: Map<ProfileKey, String>): Map<String, ProfileSettings> =
        mapNotNull { entry -> profileIds[entry.key()]?.let { id -> id to entry.toProto() } }.toMap()

    private fun ProfileDocument.key() = ProfileKey(serverUrl = serverUrl, username = username)

    private fun ProfileDocument.toProto(): ProfileSettings = ProfileSettings.newBuilder()
        .setDefaultLibraryId(defaultLibraryId)
        .putAllLibrarySortOrder(librarySortOrder)
        .setShelfSortOrder(shelfSortOrder)
        .build()

    private fun DeviceDocument.toProto(): KnownDevice = KnownDevice.newBuilder()
        .setDisplayName(displayName)
        .setKind(kind.asEnum(DeviceKind.DEVICE_KIND_UNSPECIFIED))
        .setPolicy(policy.asEnum(DevicePolicy.DEVICE_POLICY_UNSPECIFIED))
        .setLastSeenEpochMillis(lastSeenEpochMillis)
        .build()

    /**
     * The constant's name, or empty for protobuf's `UNRECOGNIZED`.
     *
     * `UNRECOGNIZED` is what a lite enum reads back as when the stored number is one this build has never
     * heard of — a file written by a newer version, or a downgrade. Writing that word into the document
     * would produce a file that cannot be imported anywhere, so it is written as absent instead, and absent
     * reads back as the field's default.
     */
    private fun <E : Enum<E>> E.exportName(): String = name.takeIf { it != UNRECOGNIZED }.orEmpty()

    /**
     * The constant this name refers to, or [fallback].
     *
     * Never `UNRECOGNIZED`: the generated setters throw for it, so a file naming a constant this build does
     * not have would crash the import rather than degrade it.
     */
    private inline fun <reified E : Enum<E>> String.asEnum(fallback: E): E =
        enumValues<E>().firstOrNull { it.name == this && it.name != UNRECOGNIZED } ?: fallback

    private const val UNRECOGNIZED = "UNRECOGNIZED"
}

/**
 * What identifies one account across two installs of this app.
 *
 * Not the profile id, which is generated locally and differs on every install (see `Profile`). The server's
 * address and the username are the pair that names the same person's shelf on the same server, which is
 * what a settings file has to key on if its per-account preferences are to survive a reinstall at all.
 */
internal data class ProfileKey(val serverUrl: String, val username: String)

/** What an import did with the file's per-account blocks. */
internal data class ProfileOutcome(val applied: Int, val skipped: Int)
