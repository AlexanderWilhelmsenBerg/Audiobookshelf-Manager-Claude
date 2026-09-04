package com.example.shelfplayer.data.settings.di

import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
import com.example.shelfplayer.data.settings.BundledBackgroundThemeCatalog
import com.example.shelfplayer.data.settings.DefaultAppearanceRepository
import com.example.shelfplayer.data.settings.DefaultDeviceRepository
import com.example.shelfplayer.data.settings.DefaultDiagnosticsRepository
import com.example.shelfplayer.data.settings.DefaultPlaybackDeviceIdentity
import com.example.shelfplayer.data.settings.DefaultPlaybackSettingsRepository
import com.example.shelfplayer.data.settings.DefaultPreferencesRepository
import com.example.shelfplayer.data.settings.DefaultSleepTimerRepository
import com.example.shelfplayer.domain.repository.AppearanceRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import com.example.shelfplayer.domain.settings.BackgroundThemeCatalog
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
    fun bindsAppearanceRepository(impl: DefaultAppearanceRepository): AppearanceRepository

    @Binds
    @Singleton
    fun bindsPlaybackSettingsRepository(impl: DefaultPlaybackSettingsRepository): PlaybackSettingsRepository

    @Binds
    @Singleton
    fun bindsBackgroundThemeCatalog(impl: BundledBackgroundThemeCatalog): BackgroundThemeCatalog

    @Binds
    @Singleton
    fun bindsDeviceRepository(impl: DefaultDeviceRepository): DeviceRepository

    @Binds
    @Singleton
    fun bindsDiagnosticsRepository(impl: DefaultDiagnosticsRepository): DiagnosticsRepository

    @Binds
    @Singleton
    fun bindsPreferencesRepository(impl: DefaultPreferencesRepository): PreferencesRepository

    @Binds
    @Singleton
    fun bindsPlaybackDeviceIdentity(impl: DefaultPlaybackDeviceIdentity): PlaybackDeviceIdentity

    @Binds
    @Singleton
    fun bindsSleepTimerRepository(impl: DefaultSleepTimerRepository): SleepTimerRepository
}
