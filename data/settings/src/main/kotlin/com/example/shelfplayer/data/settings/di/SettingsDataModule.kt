package com.example.shelfplayer.data.settings.di

import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
import com.example.shelfplayer.data.settings.DefaultDeviceRepository
import com.example.shelfplayer.data.settings.DefaultDiagnosticsRepository
import com.example.shelfplayer.data.settings.DefaultPlaybackDeviceIdentity
import com.example.shelfplayer.data.settings.DefaultPlaybackSettingsRepository
import com.example.shelfplayer.data.settings.DefaultPreferencesRepository
import com.example.shelfplayer.data.settings.DefaultSleepTimerRepository
import com.example.shelfplayer.data.settings.transfer.DefaultSettingsTransferRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.repository.SettingsTransferRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** PRODUCT_SPEC 9.3 — data modules implement the domain's repository interfaces. */
@Module
@InstallIn(SingletonComponent::class)
interface SettingsDataModule {
    @Binds
    @Singleton
    fun bindsPlaybackSettingsRepository(impl: DefaultPlaybackSettingsRepository): PlaybackSettingsRepository

    /**
     * PRODUCT_SPEC ROUTE-002 — the known-device list, which is a setting and so lives here.
     *
     * Separate from [bindsPlaybackSettingsRepository] because what it stores is a set of rows the user
     * manages rather than a handful of switches.
     */
    @Binds
    @Singleton
    fun bindsDeviceRepository(impl: DefaultDeviceRepository): DeviceRepository

    @Binds
    @Singleton
    fun bindsDiagnosticsRepository(impl: DefaultDiagnosticsRepository): DiagnosticsRepository

    @Binds
    @Singleton
    fun bindsPreferencesRepository(impl: DefaultPreferencesRepository): PreferencesRepository

    /**
     * PRODUCT_SPEC PLAY-001 — not a repository, and here anyway.
     *
     * The seam belongs to `:core:network`, but the value it answers with is persisted, and this is the
     * module that owns the settings store. The alternative was `:app` naming `AppSettingsDataSource`,
     * which is exactly the dependency PRODUCT_SPEC 9.3 keeps closed.
     */
    @Binds
    @Singleton
    fun bindsPlaybackDeviceIdentity(impl: DefaultPlaybackDeviceIdentity): PlaybackDeviceIdentity

    @Binds
    @Singleton
    fun bindsSleepTimerRepository(impl: DefaultSleepTimerRepository): SleepTimerRepository

    /**
     * PRODUCT_SPEC SET-001 — moving the settings to a file and back.
     *
     * Here rather than in `:app` because it reads the settings store and the profile table, and both of
     * those stop at this module by design.
     */
    @Binds
    @Singleton
    fun bindsSettingsTransferRepository(impl: DefaultSettingsTransferRepository): SettingsTransferRepository
}
