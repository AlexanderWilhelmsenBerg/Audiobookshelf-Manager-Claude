package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ManagementPermissions
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * PRODUCT_SPEC EPIC MGR / principle 4 — everything a management screen needs to decide what to offer.
 *
 * One use case rather than four flows into a `ViewModel`, for the reason `SettingsViewModel` gives:
 * separate flows into one screen are separate chances for it to hold one value from before a change and
 * three from after. Here that would show a menu built from the old profile's grants and the new profile's
 * book — which is exactly the profile-boundary mistake PRODUCT_SPEC 5.2 exists to prevent.
 *
 * The book is included because every caller needs it and because it is what makes the scope *about* an
 * item: a `null` book and a full set of permissions still means no action is available, and the caller
 * should not have to remember that.
 */
class ObserveManagementPermissionsUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val libraries: LibraryRepository,
    private val capabilities: CapabilityRepository,
    private val network: NetworkMonitor,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(bookId: LibraryItemId): Flow<ManagementScope?> =
        profiles.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                combine(
                    libraries.observeBook(profile.id, bookId),
                    capabilities.observeCapabilities(profile.serverId),
                    network.isOnline,
                ) { book, serverCapabilities, isOnline ->
                    ManagementScope(
                        profileId = profile.id,
                        book = book,
                        permissions = ManagementPermissions(
                            profileRole = profile.role,
                            canUpdate = profile.canUpdate,
                            canDelete = profile.canDelete,
                            canUpload = profile.canUpload,
                            capabilities = serverCapabilities,
                            isOnline = isOnline,
                        ),
                    )
                }
            }
        }
}

/** Who is asking, about which book, and what they are allowed to do to it. */
data class ManagementScope(val profileId: ProfileId, val book: Book?, val permissions: ManagementPermissions)
