package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-001/003, AUTH-002, §5.2 and PD-004 — credential ownership of a shared transfer.
 *
 * Queued ownership is a preference, not authority after its claim disappears. Each request checks current
 * claims, server identity, authentication, download permission and catalogue visibility. The active profile
 * is irrelevant. Audio also has to remain in the claimant's downloadable file list; the manifest is never
 * replanned or replaced by that lookup.
 *
 * Only authentication/authorization failures may try another claimant. Each invocation considers a bounded
 * claim snapshot; [rejectedOwners] belongs to one book run and prevents blind retrying the same denied
 * account on later files or artwork. No WorkManager request, network constraint or durable owner is changed.
 */
class DownloadClaimAccess @Inject constructor(
    private val downloads: DownloadRepository,
    private val profiles: ProfileRepository,
    private val assets: BookAssetSource,
) {
    @Suppress("ReturnCount")
    suspend fun <T> withOwner(
        book: OfflineBook,
        preferredOwner: ProfileId,
        rejectedOwners: MutableSet<ProfileId>,
        fileId: String? = null,
        transfer: suspend (ProfileId) -> AppResult<T>,
    ): AppResult<T> {
        val claims = downloads.observe(book.serverId, book.itemId).first()?.requestedBy.orEmpty()
        val candidates = claims.sortedWith(compareBy<ProfileId> { it != preferredOwner }.thenBy { it.value })
        var lastError: AppError = noClaimAccess()
        for (owner in candidates.filterNot { it in rejectedOwners }) {
            val accessError = authorize(book, owner, fileId)
            if (accessError != null) {
                if (!accessError.isAccountFailure()) return AppResult.Failure(accessError)
                lastError = accessError
                rejectedOwners += owner
                continue
            }
            when (val result = transfer(owner)) {
                is AppResult.Success -> return result

                is AppResult.Failure -> {
                    if (!result.error.isAccountFailure()) return result
                    lastError = result.error
                    rejectedOwners += owner
                }
            }
        }
        return AppResult.Failure(lastError)
    }

    private suspend fun authorize(book: OfflineBook, owner: ProfileId, fileId: String?): AppError? {
        checkClaim(book, owner)?.let { return it }
        val assetError = when (val visible = assets.assetsFor(owner, book.itemId)) {
            is AppResult.Failure -> visible.error

            is AppResult.Success -> if (fileId != null && visible.value.files.none { it.remoteFileId == fileId }) {
                AppError.Authorization(summary = "This audio file is no longer available to this profile.")
            } else {
                null
            }
        }
        // The catalogue lookup can suspend while a claim/profile/permission is removed. Check again at the
        // request boundary. An already authorized HTTP request is not revoked or restarted mid-body.
        return assetError ?: checkClaim(book, owner)
    }

    private suspend fun checkClaim(book: OfflineBook, owner: ProfileId): AppError? {
        val profile = profiles.observeProfiles().first().firstOrNull { it.id == owner }
        return when {
            profile == null || profile.requiresReauthentication -> AppError.Authentication()
            profile.serverId != book.serverId || !profile.canDownload -> noClaimAccess()
            owner !in downloads.observe(book.serverId, book.itemId).first()?.requestedBy.orEmpty() -> noClaimAccess()
            else -> null
        }
    }

    private fun noClaimAccess(): AppError.Authorization = AppError.Authorization(
        summary = "No eligible profile still claims this download.",
        missingPermission = "download",
    )

    private fun AppError.isAccountFailure(): Boolean = this is AppError.Authentication || this is AppError.Authorization
}
