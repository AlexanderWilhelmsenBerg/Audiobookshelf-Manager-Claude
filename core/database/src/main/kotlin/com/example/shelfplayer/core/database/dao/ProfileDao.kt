package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY displayName COLLATE NOCASE ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE profileId = :profileId")
    fun observeProfile(profileId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE profileId = :profileId")
    suspend fun findProfile(profileId: String): ProfileEntity?

    @Query("SELECT * FROM servers WHERE serverId = :serverId")
    suspend fun findServer(serverId: String): ServerEntity?

    @Query("SELECT * FROM servers WHERE serverId = :serverId")
    fun observeServer(serverId: String): Flow<ServerEntity?>

    @Query("SELECT * FROM servers ORDER BY displayName COLLATE NOCASE ASC")
    fun observeServers(): Flow<List<ServerEntity>>

    /** PRODUCT_SPEC AUTH-002 — two accounts on one server must produce one row here, not two. */
    @Query("SELECT COUNT(*) FROM servers")
    fun observeServerCount(): Flow<Int>

    /**
     * `@Upsert`, not `@Insert(REPLACE)`: `servers` is the parent of `profiles` and `libraries`, and a
     * `REPLACE` conflict is a delete plus an insert, which runs `ON DELETE CASCADE`. Signing a second
     * account into a server would otherwise delete the first account's profile — and with it, its
     * progress. PRODUCT_SPEC AUTH-002: removing one profile does not remove another profile's data, and
     * *not* removing one certainly must not either.
     */
    @Upsert
    suspend fun upsertServer(server: ServerEntity)

    /** Same reason as [upsertServer]: `media_progress` cascades from `profiles`. */
    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity)

    /**
     * PRODUCT_SPEC AUTH-001 — an existing server row keeps the capability handshake it already has.
     *
     * Signing a second account in to a known server must not blank the columns the first sign-in
     * filled: `REPLACE` would, because the caller building a [ServerEntity] from a fresh probe has no
     * handshake to put in them. Identity and probe results are updated; everything the handshake owns
     * is left alone (PRODUCT_SPEC SYNC-001).
     */
    @Query(
        """
        UPDATE servers
        SET displayName = :displayName,
            baseUrl = :baseUrl,
            detectedVersion = :detectedVersion,
            authMethodsJson = :authMethodsJson,
            lastFetchedAt = :fetchedAt
        WHERE serverId = :serverId
        """,
    )
    suspend fun updateServerIdentity(
        serverId: String,
        displayName: String,
        baseUrl: String,
        detectedVersion: String?,
        authMethodsJson: String,
        fetchedAt: Long,
    ): Int

    /** PRODUCT_SPEC SYNC-001 — the handshake result, written without disturbing anything else. */
    @Query(
        """
        UPDATE servers
        SET capabilitiesJson = :capabilitiesJson,
            authMethodsJson = :authMethodsJson,
            capabilitiesDetectedAt = :detectedAt,
            detectedVersion = :serverVersion
        WHERE serverId = :serverId
        """,
    )
    suspend fun updateServerCapabilities(
        serverId: String,
        capabilitiesJson: String,
        authMethodsJson: String,
        serverVersion: String?,
        detectedAt: Long,
    )

    /**
     * PRODUCT_SPEC AUTH-004 — the profile is *marked*, never removed.
     *
     * An expired session must leave downloads, local progress and preferences exactly where they are,
     * so that reauthenticating restores the account rather than rebuilding it.
     */
    @Query("UPDATE profiles SET requiresReauthentication = :required WHERE profileId = :profileId")
    suspend fun setRequiresReauthentication(profileId: String, required: Boolean)

    @Query("UPDATE profiles SET lastUsedAt = :usedAt WHERE profileId = :profileId")
    suspend fun setLastUsedAt(profileId: String, usedAt: Long)

    /**
     * PRODUCT_SPEC 5.2 — records what the server most recently said about this account.
     *
     * Separate from the profile upsert because all of this can change without anything else about the
     * profile changing: a `403` triggers a permission refresh, and re-writing the whole row from a stale
     * copy would undo whatever else had moved on.
     *
     * [role] travels with the grant rather than in its own statement because it comes from the same
     * response and answers the same question — what this account may do. An account demoted on the
     * server would otherwise keep offering actions it can no longer perform (PRODUCT_SPEC 5.1).
     */
    @Query(
        """
        UPDATE profiles
        SET accessibleLibrariesJson = :accessibleLibrariesJson,
            hasAllLibraryAccess = :hasAllLibraryAccess,
            hasAllTagAccess = :hasAllTagAccess,
            canDownload = :canDownload,
            role = :role
        WHERE profileId = :profileId
        """,
    )
    suspend fun setAccountState(
        profileId: String,
        accessibleLibrariesJson: String,
        hasAllLibraryAccess: Boolean,
        hasAllTagAccess: Boolean,
        canDownload: Boolean,
        role: String,
    )

    /**
     * PRODUCT_SPEC AUTH-002 — removing one profile must not remove another profile's data.
     *
     * The cascade deletes only rows keyed by this `profileId` (progress, sync state). Books and
     * libraries belong to the server, and other profiles on the same server keep them.
     */
    @Query("DELETE FROM profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)
}
