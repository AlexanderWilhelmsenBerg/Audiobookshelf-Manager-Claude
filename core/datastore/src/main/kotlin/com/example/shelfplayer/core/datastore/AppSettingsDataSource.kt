package com.example.shelfplayer.core.datastore

import androidx.datastore.core.DataStore
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SET-001 — typed access to the settings store.
 *
 * Exposing a read-only [Flow] and suspending mutators (rather than the raw [DataStore]) is what
 * PRODUCT_SPEC 22.9 asks for: no caller can hand out a mutable stream, and every write goes through
 * a named operation that a test can assert on.
 */
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

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.updateData { current -> current.toBuilder().setThemeMode(mode).build() }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.updateData { current -> current.toBuilder().setDynamicColor(enabled).build() }
    }

    /**
     * PRODUCT_SPEC 20, Phase 0 — records that the bundled demo library has been written to Room.
     *
     * Persisting the flag rather than checking whether the database happens to be empty means a user
     * who deletes the demo content does not silently get it back on the next launch.
     */
    suspend fun markFixtureLibrarySeeded() {
        dataStore.updateData { current ->
            current.toBuilder().setFixtureLibrarySeeded(true).build()
        }
    }

    suspend fun current(): AppSettings = dataStore.updateData { it }
}
