package com.example.shelfplayer.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release builds bind a hard-disabled implementation; there is no persistent fault-injection store. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ColdResumeDiagnosticModule {
    @Binds
    @Singleton
    abstract fun bindColdResumeDiagnostic(implementation: DisabledColdResumeDiagnostic): ColdResumeDiagnostic
}
