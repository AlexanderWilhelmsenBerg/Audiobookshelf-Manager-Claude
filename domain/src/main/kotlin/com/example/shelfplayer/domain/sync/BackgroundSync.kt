package com.example.shelfplayer.domain.sync

import com.example.shelfplayer.core.model.ProfileId

/**
 * PRODUCT_SPEC SYNC-003 — persistent refresh that outlives the process.
 *
 * The foreground already refreshes on resume, on reconnect and on a profile switch. This is for the
 * case none of those cover: the app has not been opened for a day, and the user expects the book they
 * finished on another device to be current when they do open it.
 *
 * ### Why an interface in `:domain`
 *
 * WorkManager needs a `Context` and `:domain` has none. The scheduling *policy* — which profiles, how
 * often, cancelled when — is domain logic, and the enqueueing is platform detail. Splitting them is
 * what lets the policy be read in one place instead of inferred from a builder chain.
 */
interface BackgroundSync {
    /**
     * Ensures a periodic refresh exists for [profileId].
     *
     * PRODUCT_SPEC SYNC-003: "work is uniquely named per profile/server to prevent duplicates".
     * Calling this repeatedly is safe and is expected — every sign-in and every switch calls it, and
     * the existing schedule is kept rather than restarted, or a user who switches accounts often would
     * push the next run permanently into the future.
     */
    suspend fun schedule(profileId: ProfileId)

    /**
     * PRODUCT_SPEC SYNC-003: "profile removal cancels its work".
     *
     * Without this a removed profile keeps waking the device to sync a credential that no longer
     * exists, failing every time, for as long as the app stays installed.
     */
    suspend fun cancel(profileId: ProfileId)
}
