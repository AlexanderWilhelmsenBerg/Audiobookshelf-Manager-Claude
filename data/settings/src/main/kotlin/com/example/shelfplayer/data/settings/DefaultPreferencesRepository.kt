package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.model.settings.ProfilePreferences
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SET-001 — preferences keyed by whichever profile is active.
 *
 * The scoping lives here rather than in the callers, which is the whole point of the seam: a screen
 * that had to name the profile could name the wrong one, and the symptom — one account's sort order on
 * another's shelf — is the kind that looks like a UI glitch rather than the boundary violation it is
 * (PRODUCT_SPEC 5.2).
 */
@Singleton
class DefaultPreferencesRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val profiles: ProfileRepository,
    private val logger: Logger,
) : PreferencesRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePreferences(): Flow<ProfilePreferences> =
        profiles.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(ProfilePreferences.Empty) else settings.profilePreferences(profile.id)
        }

    override suspend fun setDefaultLibrary(libraryId: LibraryId?): AppResult<Unit> = write { profileId ->
        settings.setDefaultLibrary(profileId, libraryId)
    }

    override suspend fun setSortOrder(libraryId: LibraryId?, order: String): AppResult<Unit> = write { profileId ->
        if (libraryId == null) {
            settings.setShelfSortOrder(profileId, order)
        } else {
            settings.setLibrarySortOrder(profileId, libraryId, order)
        }
    }

    override suspend fun forget(profileId: ProfileId): AppResult<Unit> = resultOf(onError = ::storeFailure) {
        settings.clearProfilePreferences(profileId)
    }

    /**
     * A write with no active profile is refused rather than dropped.
     *
     * It should not happen — every screen that writes a preference is behind a signed-in profile — and
     * silently succeeding would make a future path that *can* reach here look like it worked.
     */
    private suspend fun write(block: suspend (ProfileId) -> Unit): AppResult<Unit> =
        resultOf(onError = ::storeFailure) {
            val profileId = profiles.activeProfileId()
                ?: return AppResult.Failure(
                    AppError.Validation(summary = "There is no active profile to save this preference for."),
                )
            block(profileId)
        }

    /**
     * ADR-0003 — the repository boundary is where a throwable becomes an [AppError].
     *
     * [AppError.Storage] carries no cause, so the throwable is logged on the way past rather than
     * dropped: a preference that will not save is a disk problem, and the stack is the only thing that
     * says which.
     *
     * PRODUCT_SPEC 14.5 — the summary names the operation and never the value. A default library id is
     * a server-side identifier for a self-hosted library, and this string can reach a diagnostics
     * report.
     */
    private fun storeFailure(throwable: Throwable): AppError {
        logger.warn(LogCategory.Settings, "A profile preference could not be written", throwable = throwable)
        return AppError.Storage(summary = "That preference could not be saved.")
    }
}
