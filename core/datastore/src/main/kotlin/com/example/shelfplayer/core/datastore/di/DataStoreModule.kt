package com.example.shelfplayer.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.datastore.AppSettings
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    /**
     * The DataStore's own scope is the application scope plus the IO dispatcher.
     *
     * PRODUCT_SPEC 22.10 rules out `GlobalScope`, and DataStore needs a scope that outlives every
     * screen: a settings write started as the user backgrounds the app must still complete.
     */
    @Provides
    @Singleton
    fun providesAppSettingsDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @Dispatcher(ShelfDispatcher.Io) ioDispatcher: CoroutineDispatcher,
        serializer: AppSettingsSerializer,
    ): DataStore<AppSettings> = DataStoreFactory.create(
        serializer = serializer,
        scope = scope + ioDispatcher,
    ) {
        context.dataStoreFile(AppSettingsSerializer.FILE_NAME)
    }
}
