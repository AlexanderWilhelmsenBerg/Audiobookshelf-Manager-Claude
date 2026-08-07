package com.example.shelfplayer.data.settings.di

import com.example.shelfplayer.data.settings.DefaultDiagnosticsRepository
import com.example.shelfplayer.data.settings.DefaultPreferencesRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
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
}
