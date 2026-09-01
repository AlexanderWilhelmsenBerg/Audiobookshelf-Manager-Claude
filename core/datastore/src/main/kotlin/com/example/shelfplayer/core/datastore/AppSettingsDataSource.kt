package com.example.shelfplayer.core.datastore

import androidx.datastore.core.DataStore
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.FocusBehaviour
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.StartupMode
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.ProfilePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import com.example.shelfplayer.core.datastore.DeviceKind as StoredDeviceKind
import com.example.shelfplayer.core.datastore.DevicePolicy as StoredDevicePolicy
import com.example.shelfplayer.core.datastore.FocusBehaviour as StoredFocusBehaviour
import com.example.shelfplayer.core.datastore.KnownDevice as StoredKnownDevice
import com.example.shelfplayer.core.datastore.StartupMode as StoredStartupMode

/**
 * PRODUCT_SPEC SET-001 — typed access to the settings store.
 *
 * Exposing a read-only [Flow] and suspending mutators (rather than the raw [DataStore]) is what
 * PRODUCT_SPEC 22.9 asks for: no caller can hand out a mutable stream, and every write goes through
 * a named operation that a test can assert on.
 */
/*
 * `TooManyFunctions` is suppressed, and it is the right call for this one class.
 *
 * It is the settings store: one reader per group and one writer per setting, and the count grows by one
 * every time the product gains a preference. The alternatives are both worse — splitting it leaves two
 * classes writing the same `DataStore` with no owner of the file, and a partial split leaves a reader
 * guessing which half a setting is in. The rule is protecting against a class that does many *kinds* of
 * thing; this one does one kind, many times.
 */
@Suppress("TooManyFunctions")
@Singleton
class AppSettingsDataSource @Inject constructor(
    private val dataStore: DataStore<AppSettings>,
    private val logger: Logger,
) {
    /**
     * An I/O failure yields the defaults instead of cancelling the stream.
     *
     * PRODUCT_SPEC 2.1: a settings read failing must not take the UI down with it — the app stays
     * usable with product defaults, and the failure is reported through diagnostics.
     */
    val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                logger.warn(
                    LogCategory.Settings,
                    "Falling back to default settings after a read failure",
                    throwable = throwable,
                )
                emit(AppSettings.getDefaultInstance())
            } else {
                throw throwable
            }
        }

    val activeProfileId: Flow<ProfileId?> = settings.map { stored ->
        stored.activeProfileId.takeIf(String::isNotBlank)?.let(::ProfileId)
    }

    suspend fun setActiveProfile(profileId: ProfileId) {
        dataStore.updateData { current ->
            current.toBuilder().setActiveProfileId(profileId.value).build()
        }
    }

    /**
     * PRODUCT_SPEC AUTH-002 — leaves no selection at all.
     *
     * Called when the active profile is removed. Pointing the selection at some other saved profile
     * would switch accounts without the user asking, so the app shows the profile picker instead.
     */
    suspend fun clearActiveProfile() {
        dataStore.updateData { current -> current.toBuilder().clearActiveProfileId().build() }
    }

    /**
     * PRODUCT_SPEC SET-001 — one profile's view preferences, defaults when it has none stored.
     *
     * A profile with no entry reads as [ProfilePreferences.Empty] rather than as an error: not having
     * chosen a sort order is the normal state of a new account, and the caller resolves the names.
     */
    fun profilePreferences(profileId: ProfileId): Flow<ProfilePreferences> = settings.map { stored ->
        stored.profileSettingsMap[profileId.value]?.toPreferences() ?: ProfilePreferences.Empty
    }

    /** PRODUCT_SPEC 6.1 step 9 — `null` clears the choice and returns the profile to every library. */
    suspend fun setDefaultLibrary(profileId: ProfileId, libraryId: LibraryId?) {
        updateProfile(profileId) { current ->
            if (libraryId == null) {
                current.clearDefaultLibraryId()
            } else {
                current.setDefaultLibraryId(libraryId.value)
            }
        }
    }

    suspend fun setLibrarySortOrder(profileId: ProfileId, libraryId: LibraryId, order: String) {
        updateProfile(profileId) { current -> current.putLibrarySortOrder(libraryId.value, order) }
    }

    suspend fun setShelfSortOrder(profileId: ProfileId, order: String) {
        updateProfile(profileId) { current -> current.setShelfSortOrder(order) }
    }

    /**
     * PRODUCT_SPEC AUTH-002 — removing a profile takes its preferences with it.
     *
     * Otherwise the map grows a dead entry per removed account, and a profile id reissued by a server
     * would inherit the arrangement of the account it replaced.
     */
    suspend fun clearProfilePreferences(profileId: ProfileId) {
        dataStore.updateData { current ->
            current.toBuilder().removeProfileSettings(profileId.value).build()
        }
    }

    /**
     * Read-modify-write of one profile's entry inside `updateData`, so two screens writing at once
     * cannot lose each other's change — DataStore serializes the whole transform.
     */
    private suspend fun updateProfile(
        profileId: ProfileId,
        transform: (ProfileSettings.Builder) -> ProfileSettings.Builder,
    ) {
        dataStore.updateData { current ->
            val existing = current.profileSettingsMap[profileId.value] ?: ProfileSettings.getDefaultInstance()
            current.toBuilder()
                .putProfileSettings(profileId.value, transform(existing.toBuilder()).build())
                .build()
        }
    }

    private fun ProfileSettings.toPreferences() = ProfilePreferences(
        defaultLibraryId = defaultLibraryId.takeIf(String::isNotBlank)?.let(::LibraryId),
        libraryOrders = librarySortOrderMap.filterValues(String::isNotBlank),
        shelfOrder = shelfSortOrder.takeIf(String::isNotBlank),
    )

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.updateData { current -> current.toBuilder().setThemeMode(mode).build() }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.updateData { current -> current.toBuilder().setDynamicColor(enabled).build() }
    }

    /**
     * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the language the app draws itself in.
     *
     * The tag is stored, not the enum's name. `AppLanguage.ofTag` documents why: the set of languages is a
     * resource fact rather than a domain one, and a tag the next build no longer ships falls back to the
     * device's language instead of to a missing `values` directory.
     */
    suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.updateData { current -> current.toBuilder().setAppLanguageTag(language.tag).build() }
    }

    // `fixture_library_seeded` is no longer written. The demo-library bootstrapper it guarded is gone —
    // the app talks to a real server — and the proto field stays reserved rather than removed, because a
    // field number that comes back with a new meaning would be reinterpreted from old bytes on an
    // upgrading device (see the note in app_settings.proto).

    /**
     * PRODUCT_SPEC PLAY-001 / 14.5 — the id this install identifies itself by when opening a session.
     *
     * Generated on first call and kept, so the user's own server sees one device rather than one per
     * app start. [newId] supplies the value so that a test can be deterministic without this class
     * reaching for a random source of its own.
     *
     * The read and the write are one `updateData` transform, which DataStore serializes: two callers
     * racing on first launch get the same id rather than two, and the second does not overwrite the
     * first.
     */
    suspend fun playbackDeviceId(newId: () -> String): String = dataStore.updateData { current ->
        if (current.playbackDeviceId.isNotBlank()) {
            current
        } else {
            current.toBuilder().setPlaybackDeviceId(newId()).build()
        }
    }.playbackDeviceId

    /**
     * PRODUCT_SPEC SET-002 (Playback) / PLAY-008 — the sleep timer's defaults.
     *
     * A stored `0` means "never chosen" and reads back as [SleepTimerSettings.Default], which is what
     * lets a changed product default reach a user who has not opened the setting. Both durations are
     * clamped to the ranges PLAY-008 states, so a value written by an older build — or a corrupted
     * file — cannot produce a two-second timer or a fade longer than the timer itself.
     */
    val sleepTimer: Flow<SleepTimerSettings> = settings.map { stored ->
        SleepTimerSettings(
            defaultLength = stored.sleepTimerDefaultMinutes.takeIf { it > 0 }?.minutes
                ?.coerceIn(SleepTimerSettings.LengthRange)
                ?: SleepTimerSettings.Default.defaultLength,
            // -1 is "off", 0 is "never chosen". See the proto comment: PLAY-008 calls the fade optional,
            // and zero was already spoken for by the never-chosen convention every other field here uses.
            fadeLength = when {
                stored.sleepTimerFadeSeconds < 0 -> Duration.ZERO
                stored.sleepTimerFadeSeconds > 0 ->
                    stored.sleepTimerFadeSeconds.seconds.coerceIn(SleepTimerSettings.FadeRange)
                else -> SleepTimerSettings.Default.fadeLength
            },
            shakeToRestart = stored.sleepTimerShakeToRestart,
            // Zero is off *and* never-chosen, which are the same thing here and always will be — the app
            // does not acquire a default that moves a position nobody asked it to move.
            rewindOnStop = stored.sleepTimerRewindSeconds.takeIf { it > 0 }?.seconds
                ?.coerceIn(SleepTimerSettings.RewindOnStopRange)
                ?: Duration.ZERO,
        )
    }

    suspend fun setSleepTimerDefaultLength(length: Duration) {
        val minutes = length.coerceIn(SleepTimerSettings.LengthRange).inWholeMinutes.toInt()
        dataStore.updateData { current -> current.toBuilder().setSleepTimerDefaultMinutes(minutes).build() }
    }

    suspend fun setSleepTimerFadeLength(length: Duration) {
        val seconds = if (length <= Duration.ZERO) {
            FADE_OFF
        } else {
            length.coerceIn(SleepTimerSettings.FadeRange).inWholeSeconds.toInt()
        }
        dataStore.updateData { current -> current.toBuilder().setSleepTimerFadeSeconds(seconds).build() }
    }

    /** PRODUCT_SPEC PLAY-008 / PLAY-009 — how far to rewind when the timer stops the book. Zero is off. */
    suspend fun setSleepTimerRewindOnStop(length: Duration) {
        val seconds = if (length <= Duration.ZERO) {
            0
        } else {
            length.coerceIn(SleepTimerSettings.RewindOnStopRange).inWholeSeconds.toInt()
        }
        dataStore.updateData { current -> current.toBuilder().setSleepTimerRewindSeconds(seconds).build() }
    }

    suspend fun setSleepTimerShakeToRestart(enabled: Boolean) {
        dataStore.updateData { current -> current.toBuilder().setSleepTimerShakeToRestart(enabled).build() }
    }

    /**
     * PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — the playback controls.
     *
     * Every read clamps and every unset value falls back to the model's default, for the reason
     * [sleepTimer] gives: a build that changes a default should reach a user who never opened the setting,
     * and a value written by an older build must not be able to produce an unplayable one.
     */
    val playback: Flow<PlaybackSettings> = settings.map { stored ->
        PlaybackSettings(
            defaultSpeed = stored.defaultSpeedHundredths.takeIf { it > 0 }
                ?.let { PlaybackSpeed.of(it / HUNDREDTHS) }
                ?: PlaybackSettings.Default.defaultSpeed,
            skips = SkipIntervals.of(
                back = stored.skipBackSeconds.takeIf { it > 0 }?.seconds ?: SkipIntervals.Default.back,
                forward = stored.skipForwardSeconds.takeIf { it > 0 }?.seconds
                    ?: SkipIntervals.Default.forward,
            ),
            autoRewind = stored.autoRewind(),
            buffer = BufferPreset.byNameOrDefault(stored.bufferPreset.takeIf(String::isNotBlank)),
            focusBehaviour = when (stored.focusBehaviour) {
                StoredFocusBehaviour.FOCUS_BEHAVIOUR_DUCK -> FocusBehaviour.Duck
                // The zero value and anything a newer build wrote. Both mean pause, which is the option
                // that cannot surprise anybody.
                StoredFocusBehaviour.FOCUS_BEHAVIOUR_PAUSE,
                StoredFocusBehaviour.UNRECOGNIZED,
                -> FocusBehaviour.Pause
            },
            startupMode = when (stored.startupMode) {
                StoredStartupMode.STARTUP_MODE_RESTORE_PAUSED -> StartupMode.RestorePaused
                StoredStartupMode.STARTUP_MODE_RESUME_ON_OPEN -> StartupMode.ResumeOnOpen
                // ROUTE-003: "App launch alone never starts playback by default." An unset field and an
                // unknown one both land here, so no upgrade path can produce a build that plays on open.
                StoredStartupMode.STARTUP_MODE_ON_MEDIA_COMMAND,
                StoredStartupMode.UNRECOGNIZED,
                -> StartupMode.OnMediaCommand
            },
            // Inverted in the store so proto3's `false` means the default, which is *on*. See the proto's
            // own comment on `auto_advance_series_disabled` for why the field is named for the other state.
            autoAdvanceSeries = !stored.autoAdvanceSeriesDisabled,
        )
    }

    suspend fun setAutoAdvanceSeries(enabled: Boolean) {
        dataStore.updateData { current ->
            current.toBuilder().setAutoAdvanceSeriesDisabled(!enabled).build()
        }
    }

    suspend fun setFocusBehaviour(behaviour: FocusBehaviour) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setFocusBehaviour(
                    when (behaviour) {
                        FocusBehaviour.Pause -> StoredFocusBehaviour.FOCUS_BEHAVIOUR_PAUSE
                        FocusBehaviour.Duck -> StoredFocusBehaviour.FOCUS_BEHAVIOUR_DUCK
                    },
                )
                .build()
        }
    }

    suspend fun setStartupMode(mode: StartupMode) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setStartupMode(
                    when (mode) {
                        StartupMode.OnMediaCommand -> StoredStartupMode.STARTUP_MODE_ON_MEDIA_COMMAND
                        StartupMode.RestorePaused -> StoredStartupMode.STARTUP_MODE_RESTORE_PAUSED
                        StartupMode.ResumeOnOpen -> StoredStartupMode.STARTUP_MODE_RESUME_ON_OPEN
                    },
                )
                .build()
        }
    }

    /**
     * The four amounts, or the requirement's own bands if the user has never set them.
     *
     * `autoRewindConfigured` carries what the zeros cannot: proto3 gives no way to tell a stored `0` from
     * an unwritten field, and zero seconds is a legitimate choice for the short bucket. Without the flag,
     * a user who deliberately set every band to zero would have the defaults restored under them.
     */
    private fun AppSettings.autoRewind(): AutoRewind = if (!autoRewindConfigured) {
        AutoRewind.Default.copy(isEnabled = autoRewindEnabled)
    } else {
        AutoRewind(
            isEnabled = autoRewindEnabled,
            afterShortPause = autoRewindShortSeconds.seconds.coerceIn(AutoRewind.Range),
            afterMediumPause = autoRewindMediumSeconds.seconds.coerceIn(AutoRewind.Range),
            afterLongPause = autoRewindLongSeconds.seconds.coerceIn(AutoRewind.Range),
            afterVeryLongPause = autoRewindVeryLongSeconds.seconds.coerceIn(AutoRewind.Range),
        )
    }

    suspend fun setDefaultSpeed(speed: PlaybackSpeed) {
        // Stored in hundredths so the grid PLAY-007 defines survives a round trip. A float would come back
        // as 1.8499999 and the next increment would land off-grid and stay there.
        val hundredths = (speed.value * HUNDREDTHS).toInt()
        dataStore.updateData { current -> current.toBuilder().setDefaultSpeedHundredths(hundredths).build() }
    }

    suspend fun setSkipIntervals(skips: SkipIntervals) {
        val clamped = SkipIntervals.of(skips.back, skips.forward)
        dataStore.updateData { current ->
            current.toBuilder()
                .setSkipBackSeconds(clamped.back.inWholeSeconds.toInt())
                .setSkipForwardSeconds(clamped.forward.inWholeSeconds.toInt())
                .build()
        }
    }

    suspend fun setAutoRewind(rewind: AutoRewind) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setAutoRewindEnabled(rewind.isEnabled)
                .setAutoRewindConfigured(true)
                .setAutoRewindShortSeconds(rewind.afterShortPause.coerceIn(AutoRewind.Range).inWholeSeconds.toInt())
                .setAutoRewindMediumSeconds(rewind.afterMediumPause.coerceIn(AutoRewind.Range).inWholeSeconds.toInt())
                .setAutoRewindLongSeconds(rewind.afterLongPause.coerceIn(AutoRewind.Range).inWholeSeconds.toInt())
                .setAutoRewindVeryLongSeconds(
                    rewind.afterVeryLongPause.coerceIn(AutoRewind.Range).inWholeSeconds.toInt(),
                )
                .build()
        }
    }

    suspend fun setBufferPreset(preset: BufferPreset) {
        dataStore.updateData { current -> current.toBuilder().setBufferPreset(preset.name).build() }
    }

    /** PRODUCT_SPEC ROUTE-001 / ROUTE-002 — auto-play when a car connects. Off unless explicitly chosen. */

    /**
     * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — which categories may spend cellular data.
     *
     * Streaming is stored **inverted**, as a *disabled* flag. Proto3 has no way to tell an unwritten
     * boolean from a stored `false`, so the field that defaults to *on* has to be the one whose zero value
     * means "on". Reading it any other way would silently turn streaming on cellular off for every install
     * that has never opened this screen — which is all of them, on the build this ships in.
     */
    val networkPolicy: Flow<NetworkPolicy> = settings.map { stored ->
        NetworkPolicy(
            streamingOnCellular = !stored.streamingCellularDisabled,
            downloadsOnCellular = stored.downloadsCellularEnabled,
            smartDownloadsOnCellular = stored.smartDownloadsCellularEnabled,
        )
    }

    /**
     * PRODUCT_SPEC DL-005 / DL-006 / ADR-0018 decisions 1 and 7 — the two unattended behaviours.
     *
     * Every field's zero value is the off state, so a device that has never opened this screen reads back
     * as "do nothing without asking" — which is the only default either of these may safely have.
     */
    val housekeeping: Flow<DownloadHousekeeping> = settings.map { stored ->
        DownloadHousekeeping(
            smartDownload = stored.smartDownloadEnabled,
            deleteFinishedAfterDays = stored.deleteFinishedAfterDays.coerceAtLeast(0),
            deletePreviousOnSmartDownload = stored.deletePreviousOnSmartDownload,
        )
    }

    suspend fun setHousekeeping(housekeeping: DownloadHousekeeping) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setSmartDownloadEnabled(housekeeping.smartDownload)
                .setDeleteFinishedAfterDays(housekeeping.deleteFinishedAfterDays.coerceAtLeast(0))
                .setDeletePreviousOnSmartDownload(housekeeping.deletePreviousOnSmartDownload)
                .build()
        }
    }

    /**
     * PRODUCT_SPEC DL-003 / ADR-0020 — the volume new downloads are written to.
     *
     * Empty is internal storage, which is proto3's zero value and the product default at once — the one
     * download setting that needs no inversion, because "unset" and "the default" really are the same
     * thing here.
     */
    val downloadVolumeUuid: Flow<String> = settings.map { stored -> stored.downloadStorageVolumeUuid }

    suspend fun setDownloadVolumeUuid(uuid: String) {
        dataStore.updateData { current ->
            current.toBuilder().setDownloadStorageVolumeUuid(uuid).build()
        }
    }

    /**
     * PRODUCT_SPEC ROUTE-002 — the devices this app has seen, most recently connected first.
     *
     * Ordered here rather than at the screen because the order is a property of the data — a settings list
     * of output devices is only navigable if the one you just plugged in is at the top.
     */
    val knownDevices: Flow<List<KnownDevice>> = settings.map { stored ->
        stored.knownDevicesMap
            .map { (id, device) -> device.toModel(id) }
            .withCarSeededFromLegacyFlag(stored.autoPlayOnCarConnect)
            .sortedByDescending(KnownDevice::lastSeenAt)
    }

    /**
     * Records that a device connected, creating it with the default policy if it is new.
     *
     * Deliberately does **not** overwrite an existing policy. This runs on every connection, and a device
     * whose policy was set to `Never` last week must not quietly become `Arm only` because it was plugged
     * in again. The name and the timestamp are refreshed; the decision is not.
     */
    suspend fun rememberDevice(device: KnownDevice) {
        dataStore.updateData { current ->
            val existing = current.knownDevicesMap[device.id]
            current.toBuilder()
                .putKnownDevices(
                    device.id,
                    device.toProto(policy = existing?.policy ?: device.policy.toProto()),
                )
                .build()
        }
    }

    suspend fun setDevicePolicy(deviceId: String, policy: DevicePolicy) {
        dataStore.updateData { current ->
            val existing = current.knownDevicesMap[deviceId] ?: return@updateData current
            current.toBuilder()
                .putKnownDevices(deviceId, existing.toBuilder().setPolicy(policy.toProto()).build())
                .build()
        }
    }

    /** PRODUCT_SPEC SET-002 — forgetting a device somebody no longer owns. It returns as new if it comes back. */
    suspend fun forgetDevice(deviceId: String) {
        dataStore.updateData { current -> current.toBuilder().removeKnownDevices(deviceId).build() }
    }

    private fun StoredKnownDevice.toModel(id: String) = KnownDevice(
        id = id,
        displayName = displayName,
        // Exhaustive rather than `else`, so adding a kind to the proto fails to compile here instead of
        // silently mapping to `Other` on somebody's device. `UNRECOGNIZED` is protobuf's own value for a
        // number a newer build wrote and this one has never heard of — it genuinely is "some other device".
        kind = when (kind) {
            StoredDeviceKind.DEVICE_KIND_WIRED -> DeviceKind.Wired
            StoredDeviceKind.DEVICE_KIND_BLUETOOTH -> DeviceKind.Bluetooth
            StoredDeviceKind.DEVICE_KIND_CAR -> DeviceKind.Car
            StoredDeviceKind.DEVICE_KIND_HEARING_AID -> DeviceKind.HearingAid
            StoredDeviceKind.DEVICE_KIND_SPEAKER -> DeviceKind.Speaker
            StoredDeviceKind.DEVICE_KIND_OTHER,
            StoredDeviceKind.DEVICE_KIND_UNSPECIFIED,
            StoredDeviceKind.UNRECOGNIZED,
            -> DeviceKind.Other
        },
        policy = when (policy) {
            StoredDevicePolicy.DEVICE_POLICY_NEVER -> DevicePolicy.Never
            StoredDevicePolicy.DEVICE_POLICY_AUTO_PLAY -> DevicePolicy.AutoPlay
            StoredDevicePolicy.DEVICE_POLICY_ASK -> DevicePolicy.Ask
            // `ARM_ONLY`, the unset zero value, and a policy written by a newer build all land on the
            // product default. That is the point of the proto's ordering, and it fails safe: an unknown
            // policy readies a book rather than starting one.
            StoredDevicePolicy.DEVICE_POLICY_ARM_ONLY,
            StoredDevicePolicy.DEVICE_POLICY_UNSPECIFIED,
            StoredDevicePolicy.UNRECOGNIZED,
            -> DevicePolicy.ArmOnly
        },
        lastSeenAt = Instant.ofEpochMilli(lastSeenEpochMillis),
    )

    private fun KnownDevice.toProto(policy: StoredDevicePolicy): StoredKnownDevice = StoredKnownDevice.newBuilder()
        .setDisplayName(displayName)
        .setKind(
            when (kind) {
                DeviceKind.Wired -> StoredDeviceKind.DEVICE_KIND_WIRED
                DeviceKind.Bluetooth -> StoredDeviceKind.DEVICE_KIND_BLUETOOTH
                DeviceKind.Car -> StoredDeviceKind.DEVICE_KIND_CAR
                DeviceKind.HearingAid -> StoredDeviceKind.DEVICE_KIND_HEARING_AID
                DeviceKind.Speaker -> StoredDeviceKind.DEVICE_KIND_SPEAKER
                DeviceKind.Other -> StoredDeviceKind.DEVICE_KIND_OTHER
            },
        )
        .setPolicy(policy)
        .setLastSeenEpochMillis(lastSeenAt.toEpochMilli())
        .build()

    private fun DevicePolicy.toProto(): StoredDevicePolicy = when (this) {
        DevicePolicy.Never -> StoredDevicePolicy.DEVICE_POLICY_NEVER
        DevicePolicy.ArmOnly -> StoredDevicePolicy.DEVICE_POLICY_ARM_ONLY
        DevicePolicy.AutoPlay -> StoredDevicePolicy.DEVICE_POLICY_AUTO_PLAY
        DevicePolicy.Ask -> StoredDevicePolicy.DEVICE_POLICY_ASK
    }

    suspend fun setNetworkPolicy(policy: NetworkPolicy) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setStreamingCellularDisabled(!policy.streamingOnCellular)
                .setDownloadsCellularEnabled(policy.downloadsOnCellular)
                .setSmartDownloadsCellularEnabled(policy.smartDownloadsOnCellular)
                .build()
        }
    }

    suspend fun current(): AppSettings = dataStore.updateData { it }

    /**
     * PRODUCT_SPEC SET-001 — the settings import's one write, as a single transaction.
     *
     * The only member of this class that takes a whole-message transform rather than one named setting,
     * and it is named for its single caller so that it stays that way. An import replaces most of the
     * store at once; doing it as a read from [current] followed by a write would put a concurrent
     * setting change between the two and lose it.
     *
     * What survives the transform is the transform's business — the caller is handed the current message
     * precisely so that it can keep this install's own identity (`playbackDeviceId` above all) rather
     * than adopting the exporting device's. See `SettingsTransfer.EXCLUDED_FIELDS`.
     */
    suspend fun importSettings(merge: (AppSettings) -> AppSettings): AppSettings = dataStore.updateData(merge)

    private companion object {

        /** The speed's storage unit. One place, so the write and the read cannot disagree. */
        const val HUNDREDTHS = 100f

        /**
         * The stored value that means "do not fade at all".
         *
         * A sentinel rather than a second boolean field: zero already means "never chosen" for every
         * numeric setting in this file, and PLAY-008's *optional* fade needs a third state.
         */
        const val FADE_OFF = -1
    }
}

/**
 * PRODUCT_SPEC ROUTE-002 — the retired global "auto-play when a car connects" switch, as a policy.
 *
 * ### Why a seed rather than a migration
 *
 * Until this landed there were two controls for one decision: this boolean, read by
 * `PlaybackService.onPostConnect`, and the per-device policy, read by `OutputDeviceWatcher`. The boolean
 * bypassed the policy's warning and its `Arm only` default, so a listener who had set their car to
 * *Never react* could still be auto-played by a switch on a different screen. The boolean is gone from
 * the model and from Settings; only its stored bit survives, and only as the answer to one question:
 * what should the car's policy be for somebody who had already turned it on?
 *
 * A **pure read-time seed** rather than a `updateData` migration, for two reasons. It writes nothing, so
 * a read cannot fail or race a concurrent write; and it is self-retiring — the moment the car has a
 * stored policy of its own, that policy wins and this function stops mattering. A migration would have
 * to run exactly once and prove it, which is a lot of machinery for one boolean.
 *
 * The proto field is deliberately **not** removed. Field 26 stays reserved by remaining in the schema,
 * because a number reused later would silently read this bit as something else.
 */
internal fun List<KnownDevice>.withCarSeededFromLegacyFlag(legacyAutoPlay: Boolean): List<KnownDevice> {
    // A stored car policy is the user's own, later decision. It always wins.
    if (!legacyAutoPlay || any { it.id == KnownDevice.CAR_ID }) return this
    return this + KnownDevice(
        id = KnownDevice.CAR_ID,
        kind = DeviceKind.Car,
        displayName = KnownDevice.CAR_DISPLAY_NAME,
        policy = DevicePolicy.AutoPlay,
        // Epoch, so the seeded row sorts last: it describes a setting somebody once chose, not a
        // connection that just happened, and putting it at the top of the list would be a lie about
        // what the list is ordered by.
        lastSeenAt = Instant.EPOCH,
    )
}
