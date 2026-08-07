package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.database.converter.StringListConverters
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.security.SessionTokenStore
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the counts, read straight from storage.
 *
 * Everything here is a `COUNT(*)`, and that is a privacy decision rather than an efficiency one: the
 * numbers that matter are about rows the *active profile may not see*, so returning the rows themselves
 * would breach the boundary the numbers exist to measure (PRODUCT_SPEC 5.2).
 *
 * The credential count comes from the Keystore-backed store, which will report how many files exist and
 * nothing else — no profile ids, no kinds, certainly no contents (PRODUCT_SPEC AUTH-003).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultDiagnosticsRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val tokens: SessionTokenStore,
    private val settings: AppSettingsDataSource,
) : DiagnosticsRepository {

    override fun observeStorage(): Flow<StorageDiagnostics> = combine(
        profileDao.observeServerCount(),
        profileDao.observeProfiles(),
        activeProfile(),
    ) { servers, profiles, active -> Triple(servers, profiles.size, active) }
        .flatMapLatest { (servers, profileCount, active) ->
            if (active == null) {
                flowOf(StorageDiagnostics(serversStored = servers, profilesStored = profileCount))
            } else {
                perProfile(servers, profileCount, active)
            }
        }

    private fun activeProfile(): Flow<ProfileEntity?> = settings.activeProfileId.flatMapLatest { profileId ->
        if (profileId == null) flowOf(null) else profileDao.observeProfile(profileId.value)
    }

    private fun perProfile(servers: Int, profileCount: Int, active: ProfileEntity): Flow<StorageDiagnostics> {
        val accessibleKeys = accessibleLibraryKeys(active)
        return combine(
            libraryDao.observeLibraryCount(active.serverId),
            libraryDao.observeBookCount(active.serverId, deleted = false),
            libraryDao.observeBookCount(active.serverId, deleted = true),
            libraryDao.observeVisibleBookCount(active.profileId, active.serverId),
            combine(
                progressDao.observeProgressCount(active.profileId),
                progressDao.observeUnsyncedCount(active.profileId),
            ) { total, unsynced -> total to unsynced },
        ) { libraries, live, deleted, accessibleBooks, progress ->
            StorageDiagnostics(
                serversStored = servers,
                profilesStored = profileCount,
                // Read once per emission rather than observed: a file count has no change notification, and
                // this flow already re-runs whenever anything it depends on moves.
                storedCredentials = tokens.storedCredentialCount(),
                librariesStored = libraries,
                librariesAccessible = accessibleKeys?.size ?: libraries,
                booksStored = live,
                booksAccessible = accessibleBooks,
                booksSoftDeleted = deleted,
                progressRecords = progress.first,
                unsyncedProgressRecords = progress.second,
            )
        }
    }

    /** `null` means "every library on this server", which is a different thing from "none". */
    private fun accessibleLibraryKeys(profile: ProfileEntity): List<String>? = when {
        profile.hasAllLibraryAccess -> null
        else -> StringListConverters.toStringList(profile.accessibleLibrariesJson)
            .filter(String::isNotBlank)
            .map { EntityKey.of(profile.serverId, it) }
    }
}
