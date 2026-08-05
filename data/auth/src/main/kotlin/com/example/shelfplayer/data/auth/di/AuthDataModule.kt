package com.example.shelfplayer.data.auth.di

import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.data.auth.DefaultAuthRepository
import com.example.shelfplayer.data.auth.SessionTokenProvider
import com.example.shelfplayer.domain.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 9.3 — data modules implement the domain's repository interfaces.
 *
 * The [TokenProvider] binding lives here rather than in `:app`. `:app` is where *final* wiring belongs,
 * and this is not final wiring: it is the credential store answering the HTTP layer, and both ends of
 * that seam are inside this module. Binding it here also means nothing outside `:data:auth` needs to
 * name [SessionTokenProvider] — the class that holds a decrypted token is unreachable from the UI
 * layer by construction (PRODUCT_SPEC AUTH-003).
 *
 * `NoTokenProvider` in `:core:network` stays available for a graph that has no credential store at all.
 */
@Module
@InstallIn(SingletonComponent::class)
interface AuthDataModule {
    @Binds
    @Singleton
    fun bindsAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    fun bindsTokenProvider(impl: SessionTokenProvider): TokenProvider
}
