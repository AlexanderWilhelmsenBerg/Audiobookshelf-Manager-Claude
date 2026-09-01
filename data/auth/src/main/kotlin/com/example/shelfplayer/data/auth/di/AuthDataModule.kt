package com.example.shelfplayer.data.auth.di

import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.data.auth.CoalescingAuthRepository
import com.example.shelfplayer.data.auth.DefaultAuthRepository
import com.example.shelfplayer.data.auth.DefaultCapabilityRepository
import com.example.shelfplayer.data.auth.DefaultProfileConnectionResolver
import com.example.shelfplayer.data.auth.DefaultProfileLockRepository
import com.example.shelfplayer.data.auth.DefaultServerUserRepository
import com.example.shelfplayer.data.auth.SessionTokenProvider
import com.example.shelfplayer.domain.lock.LockedProfileRecovery
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ServerUserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
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

    /** AUTH-005 — the profile passcode lock. */
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

    /**
     * AUTH-005 — the same object again, for the question `SwitchProfileUseCase` asks.
     *
     * `@Provides` rather than `@Binds`, and that is a tool limitation rather than a design choice worth
     * defending. Dagger's KSP processor cannot resolve a Kotlin `fun interface` whose single method takes a
     * `value class` parameter — it reports the type as unresolvable. `ProfileLockGuard` binds normally
     * because its method takes none, so the two look inconsistent for a reason that is entirely external.
     *
     * The `fun interface` is kept because it is what lets a test supply a lambda instead of a fake, which
     * is the whole reason the interface is one method wide. Trading that away to satisfy `@Binds` would be
     * letting a processor bug pick the architecture.
     */
    companion object {
        /**
         * AUTH-004 — every feature sees one renewal boundary.
         *
         * The wrapper coalesces refresh-token rotation across playback range requests, playback-session
         * opens and ordinary sync. Binding the delegate directly would give those callers separate locks
         * again and re-introduce the rotating-token race.
         */
        @Provides
        @Singleton
        fun providesAuthRepository(
            impl: DefaultAuthRepository,
            tokens: SessionTokenProvider,
        ): AuthRepository = CoalescingAuthRepository(impl, tokens)

        @Provides
        @Singleton
        fun providesProfileActivationGuard(impl: DefaultProfileLockRepository): ProfileActivationGuard = impl

        /** AUTH-005 — same object, same `@Provides` reason as above. */
        @Provides
        @Singleton
        fun providesLockedProfileRecovery(impl: DefaultProfileLockRepository): LockedProfileRecovery = impl
    }
}
