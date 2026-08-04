package com.example.shelfplayer.core.common.di

import com.example.shelfplayer.core.common.log.CorrelationIdGenerator
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.common.log.Redactor
import com.example.shelfplayer.core.common.log.UuidCorrelationIdGenerator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {
    @Binds
    @Singleton
    abstract fun bindsLogger(impl: RedactingLogger): Logger

    @Binds
    @Singleton
    abstract fun bindsRedactor(impl: DefaultRedactor): Redactor

    @Binds
    @Singleton
    abstract fun bindsCorrelationIdGenerator(impl: UuidCorrelationIdGenerator): CorrelationIdGenerator

    companion object {
        /**
         * PRODUCT_SPEC SET-002 — both diagnostics opt-ins default to off.
         *
         * Phase 6 replaces this with a value read from Proto DataStore so the user can widen it in
         * the diagnostics screen. Until that exists, the safe value is the only value.
         */
        @Provides
        @Singleton
        fun providesRedactionPolicy(): RedactionPolicy = RedactionPolicy.Default
    }
}
