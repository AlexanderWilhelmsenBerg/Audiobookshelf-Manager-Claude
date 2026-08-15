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
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.settings.ProfilePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
            autoPlayOnCarConnect = stored.autoPlayOnCarConnect,
            buffer = BufferPreset.byNameOrDefault(stored.bufferPreset.takeIf(String::isNotBlank)),
        )
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
    suspend fun setAutoPlayOnCarConnect(enabled: Boolean) {
        dataStore.updateData { current -> current.toBuilder().setAutoPlayOnCarConnect(enabled).build() }
    }

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
