package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Recheck identity, mutation generation and current grants at every asynchronous seam. */
internal class GarminDeviceAccess @Inject constructor(
    val profiles: ProfileRepository,
    val locks: ProfileLockRepository,
    val library: LibraryRepository,
    val downloads: DownloadRepository,
    val syncAccount: SyncAccountUseCase,
) {
    suspend fun current(): Profile? = profiles.observeActiveProfile().first()?.takeIf {
        allowed(it, profiles.activeProfileGeneration())
    }
    suspend fun allowed(profile: Profile, generation: Long): Boolean =
        !profile.isFixture && !profile.requiresReauthentication &&
            !locks.isLocked(profile.id) && profiles.activeProfileId() == profile.id &&
            profiles.activeProfileGeneration() == generation
    suspend fun eligible(profile: Profile): List<Book> {
        val generation = profiles.activeProfileGeneration()
        if (!profile.canDownload || !allowed(profile, generation)) return emptyList()
        val complete = downloads.observeCompletedFor(profile.id).first()
        val books = library.observeAccessibleBooks(profile.id).first().filter {
            it.serverId == profile.serverId &&
                it.id in complete
        }
        return if (allowed(profile, generation)) books else emptyList()
    }
}
