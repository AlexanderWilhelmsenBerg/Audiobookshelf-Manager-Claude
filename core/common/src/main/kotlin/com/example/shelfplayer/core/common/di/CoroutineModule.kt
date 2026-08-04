package com.example.shelfplayer.core.common.di

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * The single place in the application that is allowed to name a concrete [CoroutineDispatcher].
 *
 * PRODUCT_SPEC 16.3 forbids raw dispatchers elsewhere; detekt's `InjectDispatcher` rule enforces it
 * and is suppressed here — and only here — because this module *is* the injected provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides
    @Dispatcher(ShelfDispatcher.Default)
    @Suppress("InjectDispatcher")
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Dispatcher(ShelfDispatcher.Io)
    @Suppress("InjectDispatcher")
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(ShelfDispatcher.Main)
    @Suppress("InjectDispatcher")
    fun providesMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @Dispatcher(ShelfDispatcher.MainImmediate)
    @Suppress("InjectDispatcher")
    fun providesMainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * PRODUCT_SPEC 22.10 — the sanctioned alternative to `GlobalScope`.
     *
     * [SupervisorJob] keeps one failed background flush from cancelling every other one, which is
     * what "never lose progress" requires (PRODUCT_SPEC 2.2).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun providesApplicationScope(
        @Dispatcher(ShelfDispatcher.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
