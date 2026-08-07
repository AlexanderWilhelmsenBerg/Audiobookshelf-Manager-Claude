package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 — "sync status is visible but non-blocking".
 *
 * ### Why this exists
 *
 * The sync state was already being written and never read. `DefaultLibraryRepository` records every
 * attempt — including the one `SignInUseCase` runs immediately after a successful sign-in — into the
 * `sync_state` table, with the error that caused a failure. Home derived its own status from an in-memory
 * flag instead, which is only populated by a refresh the *user* started.
 *
 * The consequence was reported from a real device: sign in, land on an empty library, no explanation. The
 * initial sync had run and its outcome was sitting in the database, invisible. A refresh then populated
 * the library, because that path did set the in-memory flag.
 *
 * Reading the persisted state is what makes a failed *initial* sync say so.
 */
class ObserveSyncStateUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<SyncState?> = profileRepository.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) flowOf(null) else libraryRepository.observeSyncState(profile.id)
    }
}
