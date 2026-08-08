package com.example.shelfplayer.data.settings.di

import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
import com.example.shelfplayer.data.settings.DefaultDiagnosticsRepository
import com.example.shelfplayer.data.settings.DefaultPlaybackDeviceIdentity
import com.example.shelfplayer.data.settings.DefaultPreferencesRepository
import com.example.shelfplayer.data.settings.DefaultSleepTimerRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
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
}
