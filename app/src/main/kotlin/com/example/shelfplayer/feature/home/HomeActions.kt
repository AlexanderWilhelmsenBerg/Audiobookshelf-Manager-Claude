package com.example.shelfplayer.feature.home

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.library.BookSortOrder

/**
 * Everything the home screen can do, in one parameter.
 *
 * A screen with a search field, a sort row, a refresh, two lists and three destinations has more
 * callbacks than a readable parameter list holds. Grouping them keeps `HomeScreen` a function of
 * `(state, actions)`, which is also what makes it previewable: a preview supplies one no-op instance
 * instead of eight lambdas.
 */
@Immutable
data class HomeActions(
    val onBookSelected: (LibraryItemId) -> Unit,
    val onLibrarySelected: (LibraryId) -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onOrderChanged: (BookSortOrder) -> Unit,
    val onRefresh: () -> Unit,
    val onProfilesSelected: () -> Unit,
    val onSettingsSelected: () -> Unit,
    val onSignInSelected: () -> Unit,
)
