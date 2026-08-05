package com.example.shelfplayer.core.network.di

import com.example.shelfplayer.core.network.api.AbsAudiobookshelfGateway
import com.example.shelfplayer.core.network.api.AbsAuthApi
import com.example.shelfplayer.core.network.api.AbsCapabilityResolver
import com.example.shelfplayer.core.network.api.AbsLibraryApi
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.LibraryApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Distinguishes the Retrofit-backed gateway from any other implementation in the graph.
 *
 * `:app` decides which one `AudiobookshelfGateway` resolves to (PRODUCT_SPEC 9.3), and it can only make
 * that choice if both candidates are reachable. Without the qualifier the two bindings would collide and
 * the decision would move into this module — the wrong module to make it in.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RemoteGateway

/**
 * The Retrofit-backed gateway and its sub-APIs.
 *
 * The sub-API bindings are unqualified because nothing else implements those interfaces: the fake gateway
 * implements the *gateway*, serving itself as each sub-API, so it never competes for these.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface GatewayModule {
    @Binds
    @Singleton
    @RemoteGateway
    fun bindsRemoteGateway(impl: AbsAudiobookshelfGateway): AudiobookshelfGateway

    @Binds
    @Singleton
    fun bindsAuthApi(impl: AbsAuthApi): AuthApi

    @Binds
    @Singleton
    fun bindsCapabilityResolver(impl: AbsCapabilityResolver): CapabilityResolver

    @Binds
    @Singleton
    fun bindsLibraryApi(impl: AbsLibraryApi): LibraryApi
}
