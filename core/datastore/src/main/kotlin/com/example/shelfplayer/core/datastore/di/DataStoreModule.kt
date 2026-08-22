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
import com.example.shelfplayer.core.datastore.security.KeystoreLockCipher
import com.example.shelfplayer.core.datastore.security.KeystoreTokenCipher
import com.example.shelfplayer.core.datastore.security.LockCipher
import com.example.shelfplayer.core.datastore.security.TokenCipher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-003 — the only sanctioned [TokenCipher] binding.
 *
 * A separate module from [DataStoreModule] so that "what encrypts the credential" is one line in one
 * file. Anything other than [KeystoreTokenCipher] bound here would move user credentials out of
 * Keystore-backed key material, which PRODUCT_SPEC 15 does not permit.
 */
@Module
@InstallIn(SingletonComponent::class)
interface SecurityModule {
    @Binds
    @Singleton
    fun bindsTokenCipher(impl: KeystoreTokenCipher): TokenCipher

    /**
     * AUTH-005 — the only sanctioned [LockCipher] binding.
     *
     * A second cipher rather than a second user of the first, under its own Keystore alias. [LockCipher]
     * lists the three bugs that sharing one would have caused; the shortest of them is that
     * `SessionTokenStore.clearAll()` destroys the session key, which would leave every passcode-protected
     * profile permanently locked the moment somebody signed out of everything.
     */
    @Binds
    @Singleton
    fun bindsLockCipher(impl: KeystoreLockCipher): LockCipher
}

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
