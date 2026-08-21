package com.example.shelfplayer.data.auth.di

import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.data.auth.DefaultAuthRepository
import com.example.shelfplayer.data.auth.DefaultCapabilityRepository
import com.example.shelfplayer.data.auth.DefaultProfileConnectionResolver
import com.example.shelfplayer.data.auth.DefaultProfileLockRepository
import com.example.shelfplayer.data.auth.DefaultServerUserRepository
import com.example.shelfplayer.data.auth.SessionTokenProvider
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ServerUserRepository
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
    fun bindsCapabilityRepository(impl: DefaultCapabilityRepository): CapabilityRepository

    @Binds
    @Singleton
    fun bindsTokenProvider(impl: SessionTokenProvider): TokenProvider

    /** PRODUCT_SPEC EPIC USER — the server's own accounts, read fresh and never cached. */
    @Binds
    @Singleton
    fun bindsServerUserRepository(impl: DefaultServerUserRepository): ServerUserRepository

    /**
     * PRODUCT_SPEC 5.2 — how the gateway learns a profile's address, credential and grant.
     *
     * Bound here for the same reason as [TokenProvider]: the answer comes from the profile row and the
     * encrypted token, both of which live behind a boundary `:core:network` does not cross.
     */
    @Binds
    @Singleton
    fun bindsProfileConnectionResolver(impl: DefaultProfileConnectionResolver): ProfileConnectionResolver

    /** PRODUCT_SPEC AUTH-005 — the profile passcode lock. */
    @Binds
    @Singleton
    fun bindsProfileLockRepository(impl: DefaultProfileLockRepository): ProfileLockRepository

    /**
     * PRODUCT_SPEC ROUTE-002 — the same object, behind the one-method interface `:playback` may hold.
     *
     * Two bindings of one `@Singleton` implementation, deliberately. The media service must be able to
     * ask "is the active profile locked" and must not be able to reach `submitPasscode` — there is no
     * window in `OutputDeviceWatcher` to draw a passcode field in, so offering it would offer a
     * capability with nowhere to put a UI.
     */
    @Binds
    @Singleton
    fun bindsProfileLockGuard(impl: DefaultProfileLockRepository): ProfileLockGuard
}
