package com.example.shelfplayer.data.settings.di

import com.example.shelfplayer.data.settings.DefaultSettingsRepository
import com.example.shelfplayer.domain.repository.SettingsRepository
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
    fun bindsSettingsRepository(impl: DefaultSettingsRepository): SettingsRepository
}
