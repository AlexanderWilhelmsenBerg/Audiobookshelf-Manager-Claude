package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC MGR-005 — `Remove from Audiobookshelf database`, and optionally the local copy too.
 *
 * ### Two removals, kept apart on purpose
 *
 * MGR-005 makes the local download "a separate checkbox, unchecked by default", and this is why the two
 * live in different places rather than in one repository method: they act on different copies, owned by
 * different things, and either can succeed while the other fails.
 *
 * The order is fixed. The server goes first, because the requirement says the item leaves Room "only after
 * server confirmation" — and because the reverse order turns a dropped connection into a book that vanished
 * from the phone and not from the library.
 *
 * ### A failed local delete is not a failed removal
 *
 * If the server accepted the removal and the files could not be deleted, the item *is* gone from the
 * server. Reporting that as a failure would invite the user to press the button again, against an item that
 * no longer exists, and get a `404` for their trouble. The download is left for the storage screen's
 * orphan sweep, which is what that sweep is for.
 */
class RemoveFromDatabaseUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val metadata: MetadataRepository,
    private val removeDownload: RemoveDownloadUseCase,
) {
    /**
     * @param alsoRemoveDownload the separate, unchecked checkbox MGR-005 asks for.
     */
    suspend operator fun invoke(bookId: LibraryItemId, alsoRemoveDownload: Boolean): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server first."))

        return when (val removed = metadata.removeFromDatabase(profile.id, bookId)) {
            is AppResult.Failure -> removed
            is AppResult.Success -> {
                // Deliberately not propagated. See the class comment: the server's copy is already gone.
                if (alsoRemoveDownload) removeDownload(bookId)
                AppResult.Success(Unit)
            }
        }
    }
}
