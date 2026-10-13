package com.example.shelfplayer.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AUTH-002 / PRODUCT_SPEC 6.5 — serialize full profile-switch transactions across callers.
 *
 * The phone, passcode unlock, and Android Auto can inject distinct SwitchProfileUseCase instances.
 * Their screen-local locks cannot protect the handover → active profile → credential transition.
 * This singleton owns only ordering; ProfileRepository remains the selection source of truth.
 */
@Singleton
class ProfileSwitchCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }
}
