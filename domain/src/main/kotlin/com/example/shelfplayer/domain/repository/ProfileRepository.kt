package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC AUTH-002 / 6.5 — the active profile, and switching between saved ones.
 *
 * The playback service reads the active profile from here rather than from Activity state
 * (PRODUCT_SPEC 9.4), which is why this is a repository and not a UI-scoped holder.
 *
 * Phase 0 implements only the read side plus fixture seeding. Adding a profile from real
 * credentials is AUTH-001 and lands in Phase 1.
 */
interface ProfileRepository {
    fun observeProfiles(): Flow<List<Profile>>

    fun observeActiveProfile(): Flow<Profile?>

    suspend fun activeProfileId(): ProfileId?

    /**
     * PRODUCT_SPEC 6.5 — the switch is atomic: either the whole profile context changes or none of
     * it does. Phase 2 extends this to flush playback progress before the swap.
     */
    suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit>
}
