package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServer(server: ServerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
            capabilitiesDetectedAt = :detectedAt,
            detectedVersion = :serverVersion
        WHERE serverId = :serverId
        """,
    )
    suspend fun updateServerCapabilities(
        serverId: String,
        capabilitiesJson: String,
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
     * PRODUCT_SPEC AUTH-002 — removing one profile must not remove another profile's data.
     *
     * The cascade deletes only rows keyed by this `profileId` (progress, sync state). Books and
     * libraries belong to the server, and other profiles on the same server keep them.
     */
    @Query("DELETE FROM profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)
}
