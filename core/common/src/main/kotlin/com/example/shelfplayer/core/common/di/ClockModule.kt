package com.example.shelfplayer.core.common.di

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.common.time.SystemAppClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindsAppClock(impl: SystemAppClock): AppClock
}
